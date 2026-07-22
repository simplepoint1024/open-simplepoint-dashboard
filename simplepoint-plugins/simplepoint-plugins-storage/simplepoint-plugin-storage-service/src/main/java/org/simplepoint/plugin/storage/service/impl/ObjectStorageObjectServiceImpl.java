/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantContextType;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.model.ObjectStorageProviderDefinition;
import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceContent;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;
import org.simplepoint.plugin.storage.api.service.ObjectStorageObjectService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteRequest;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Object-storage metadata service.
 */
@Service
public class ObjectStorageObjectServiceImpl
    extends BaseServiceImpl<ObjectStorageObjectRepository, ObjectStorageObject, String>
    implements ObjectStorageObjectService, ObjectStorageSourceService {

  private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

  private static final long MAX_ROUTED_DOWNLOAD_BYTES = 64L * 1024L * 1024L;

  private final ObjectStorageObjectRepository repository;

  private final ObjectStorageTenantQuotaRepository quotaRepository;

  private final List<ObjectStorageDriver> drivers;

  private final ObjectStorageProperties properties;

  private final String applicationName;

  private ObjectStorageProviderConfigService providerConfigService;

  private TenantService tenantService;

  /**
   * Object Storage Object Service Impl.
   */
  public ObjectStorageObjectServiceImpl(
      final ObjectStorageObjectRepository repository,
      final DetailsProviderService detailsProviderService,
      final ObjectStorageTenantQuotaRepository quotaRepository,
      final List<ObjectStorageDriver> drivers,
      final ObjectStorageProperties properties,
      @Value("${spring.application.name:unknown}") final String applicationName
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.quotaRepository = quotaRepository;
    this.drivers = drivers == null ? List.of() : List.copyOf(drivers);
    this.properties = properties;
    this.applicationName = applicationName;
  }

  /**
   * Uses database-backed system-global OSS configurations when the module is available.
   *
   * @param providerConfigService provider configuration service
   */
  @Autowired
  public void setProviderConfigService(
      final ObjectStorageProviderConfigService providerConfigService
  ) {
    this.providerConfigService = providerConfigService;
  }

  /**
   * Uses the authenticated user's personal tenant when a caller omits the tenant header.
   *
   * @param tenantService tenant service
   */
  @Autowired(required = false)
  public void setTenantService(final TenantService tenantService) {
    this.tenantService = tenantService;
  }

  /**
   * Object metadata is isolated explicitly by tenantId in every user-facing
   * query. Applying the generic SELF/ORG business-row filter on top would hide
   * freshly uploaded objects because storage records are not department-owned.
   */
  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public Collection<ObjectStorageProviderDefinition> providers() {
    if (providerConfigService != null) {
      return providerConfigService.providers();
    }
    List<ObjectStorageProviderDefinition> results = new ArrayList<>();
    Map<String, ObjectStorageProperties.ProviderProperties> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      return List.of();
    }
    String defaultProvider = trimToNull(properties.getDefaultProvider());
    providers.forEach((code, provider) -> {
      if (provider != null && Boolean.TRUE.equals(provider.getEnabled())) {
        results.add(new ObjectStorageProviderDefinition(
            code,
            trimToNull(provider.getName()) == null ? code : provider.getName().trim(),
            provider.getType(),
            trimToNull(provider.getEndpoint()),
            trimToNull(provider.getBucket()),
            code.equals(defaultProvider)
        ));
      }
    });
    results.sort(Comparator.comparing(ObjectStorageProviderDefinition::getDefaultProvider, Comparator.reverseOrder())
        .thenComparing(ObjectStorageProviderDefinition::getCode));
    return results;
  }

  @Override
  public Optional<ObjectStorageObject> findActiveById(final String id) {
    return repository.findActiveByIdAndTenantId(id, requireCurrentTenantId());
  }

  @Override
  public ObjectStorageReadResult download(final String id) {
    ObjectStorageObject object = repository.findActiveByIdAndTenantId(id, requireCurrentTenantId())
        .orElseThrow(() -> new NoSuchElementException("对象不存在: " + id));
    return read(object);
  }

  @Override
  public ObjectStorageReadResult downloadImage(final String id) {
    ObjectStorageObject object = repository.findActiveById(id)
        .filter(candidate -> {
          String contentType = trimToNull(candidate.getContentType());
          return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
        })
        .filter(this::canRenderImage)
        .orElseThrow(() -> new NoSuchElementException("图片不存在: " + id));
    return read(object);
  }

  private boolean canRenderImage(final ObjectStorageObject object) {
    if ("rbac-avatar".equals(trimToNull(object.getSourceServiceName()))) {
      return true;
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return true;
    }
    String tenantId = currentTenantId();
    if (tenantId == null || tenantId.isBlank()) {
      tenantId = resolvePersonalTenantId();
    }
    return Objects.equals(trimToNull(object.getTenantId()), trimToNull(tenantId));
  }

  @Override
  public ObjectStorageSourceContent downloadSource(
      final String id,
      final String tenantId,
      final String sourceServiceName,
      final long maxBytes
  ) {
    String normalizedId = requireValue(id, "对象 ID 不能为空");
    String normalizedTenantId = requireValue(tenantId, "对象存储租户 ID 不能为空");
    String normalizedSourceService = requireValue(sourceServiceName, "对象来源服务不能为空");
    if (maxBytes <= 0 || maxBytes > MAX_ROUTED_DOWNLOAD_BYTES) {
      throw new IllegalArgumentException(
          "服务间对象下载上限必须在 1 到 " + MAX_ROUTED_DOWNLOAD_BYTES + " 字节之间"
      );
    }
    ObjectStorageObject object = repository
        .findActiveByIdAndTenantId(normalizedId, normalizedTenantId)
        .orElseThrow(() -> new NoSuchElementException("对象不存在或不属于指定租户: " + normalizedId));
    if (!normalizedSourceService.equals(trimToNull(object.getSourceServiceName()))) {
      throw new NoSuchElementException("对象不存在或不属于指定来源服务: " + normalizedId);
    }
    if (object.getContentLength() != null && object.getContentLength() > maxBytes) {
      throw new IllegalArgumentException("对象大小超过服务间下载上限");
    }
    ObjectStorageReadResult result = read(object);
    try (InputStream input = result.getInputStream()) {
      byte[] content = input.readNBytes(Math.toIntExact(maxBytes + 1));
      if (content.length > maxBytes) {
        throw new IllegalArgumentException("对象大小超过服务间下载上限");
      }
      return new ObjectStorageSourceContent(
          content,
          trimToNull(result.getFileName()) == null
              ? object.getOriginalFileName() : result.getFileName(),
          trimToNull(result.getContentType()) == null
              ? normalizeContentType(object.getContentType()) : result.getContentType(),
          content.length
      );
    } catch (IOException ex) {
      throw new IllegalStateException("读取对象内容失败: " + ex.getMessage(), ex);
    }
  }

  private ObjectStorageReadResult read(final ObjectStorageObject object) {
    ResolvedProvider provider = resolveProvider(object.getProviderCode(), true);
    return resolveDriver(provider.getProperties().getType()).read(
        provider.getProperties(),
        object.getBucket(),
        object.getObjectKey(),
        object.getOriginalFileName()
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ObjectStorageObject upload(final MultipartFile file, final ObjectStorageUploadRequest request) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("上传文件不能为空");
    }
    ResolvedProvider provider = resolveProvider(request == null ? null : request.getProviderCode(), false);
    String tenantId = requireCurrentTenantId();
    enforceQuota(tenantId, file.getSize());
    String fileName = resolveFileName(file, request);
    String objectKey = resolveObjectKey(tenantId, provider.getProperties(), fileName, request);
    ensureObjectKeyAvailable(provider.getCode(), provider.getProperties().getBucket(), objectKey);
    String sourceServiceName = resolveSourceServiceName(request);
    ObjectStorageWriteResult writeResult;
    try (InputStream inputStream = file.getInputStream()) {
      ObjectStorageWriteRequest writeRequest = new ObjectStorageWriteRequest(
          provider.getProperties().getBucket(),
          objectKey,
          fileName,
          normalizeContentType(file.getContentType()),
          file.getSize(),
          Map.of(
              "tenantId", tenantId,
              "sourceServiceName", sourceServiceName,
              "originalFileName", fileName
          ),
          inputStream
      );
      writeResult = resolveDriver(provider.getProperties().getType()).write(provider.getProperties(), writeRequest);
    } catch (Exception ex) {
      throw new IllegalStateException("对象上传失败: " + ex.getMessage(), ex);
    }
    ObjectStorageObject entity = new ObjectStorageObject();
    entity.setTenantId(tenantId);
    entity.setProviderCode(provider.getCode());
    entity.setProviderType(provider.getProperties().getType());
    entity.setBucket(writeResult.getBucket());
    entity.setObjectKey(writeResult.getObjectKey());
    entity.setOriginalFileName(fileName);
    entity.setContentType(normalizeContentType(file.getContentType()));
    entity.setContentLength(file.getSize());
    entity.setETag(trimToNull(writeResult.getETag()));
    entity.setAccessUrl(trimToNull(writeResult.getAccessUrl()));
    entity.setSourceServiceName(sourceServiceName);
    try {
      return repository.save(entity);
    } catch (RuntimeException ex) {
      resolveDriver(provider.getProperties().getType()).delete(
          provider.getProperties(),
          writeResult.getBucket(),
          writeResult.getObjectKey()
      );
      throw ex;
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<ObjectStorageObject> objects = repository.findAllActiveByIdsAndTenantId(
        ids,
        requireCurrentTenantId()
    );
    for (ObjectStorageObject object : objects) {
      ResolvedProvider provider = resolveProvider(object.getProviderCode(), true);
      resolveDriver(provider.getProperties().getType()).delete(
          provider.getProperties(),
          object.getBucket(),
          object.getObjectKey()
      );
    }
    super.removeByIds(objects.stream().map(ObjectStorageObject::getId).toList());
  }

  @Override
  public <S extends ObjectStorageObject> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    String tenantId = requireCurrentTenantId();
    normalized.put("tenantId", tenantId);
    String fileName = normalized.get("originalFileName");
    if (fileName != null && !fileName.isBlank() && !fileName.contains(":")) {
      normalized.put("originalFileName", "like:" + fileName.trim());
    }
    return super.limit(normalized, pageable);
  }

  private void enforceQuota(final String tenantId, final long newBytes) {
    quotaRepository.findActiveByTenantId(tenantId)
        .filter(quota -> Boolean.TRUE.equals(quota.getEnabled()))
        .ifPresent(quota -> {
          Long quotaBytes = quota.getQuotaBytes();
          if (quotaBytes == null) {
            return;
          }
          long usedBytes = Optional.ofNullable(repository.sumActiveContentLengthByTenantId(tenantId)).orElse(0L);
          if (usedBytes + Math.max(newBytes, 0L) > quotaBytes) {
            throw new IllegalStateException("租户对象存储配额不足");
          }
        });
  }

  private void ensureObjectKeyAvailable(final String providerCode, final String bucket, final String objectKey) {
    repository.findActiveByProviderCodeAndBucketAndObjectKey(providerCode, bucket, objectKey)
        .ifPresent(existing -> {
          throw new IllegalArgumentException("对象键已存在: " + objectKey);
        });
  }

  private String resolveFileName(final MultipartFile file, final ObjectStorageUploadRequest request) {
    String requested = request == null ? null : trimToNull(request.getFileName());
    if (requested != null) {
      return requested;
    }
    String original = trimToNull(file.getOriginalFilename());
    if (original != null) {
      return original;
    }
    return "unnamed-" + UUID.randomUUID();
  }

  private String resolveObjectKey(
      final String tenantId,
      final ObjectStorageProperties.ProviderProperties provider,
      final String fileName,
      final ObjectStorageUploadRequest request
  ) {
    String tenantRoot = "tenants/" + sanitizeTenantId(tenantId);
    String requestedKey = request == null ? null : trimToNull(request.getObjectKey());
    if (requestedKey != null) {
      return applyProviderBasePath(provider, tenantRoot + '/' + sanitizePath(requestedKey));
    }
    String directory = request == null ? null : trimToNull(request.getDirectory());
    StringBuilder path = new StringBuilder(tenantRoot).append('/');
    if (directory != null) {
      path.append(sanitizePath(directory)).append('/');
    }
    path.append(LocalDate.now().format(DATE_PATH)).append('/');
    path.append(UUID.randomUUID()).append('-').append(sanitizeFileName(fileName));
    return applyProviderBasePath(provider, path.toString());
  }

  private String applyProviderBasePath(
      final ObjectStorageProperties.ProviderProperties provider,
      final String objectKey
  ) {
    String basePath = trimToNull(provider.getBasePath());
    if (basePath == null) {
      return objectKey;
    }
    return sanitizePath(basePath) + '/' + sanitizePath(objectKey);
  }

  private String requireCurrentTenantId() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    String personalTenantId = resolvePersonalTenantId();
    if (personalTenantId != null) {
      return personalTenantId;
    }
    throw new IllegalArgumentException("租户ID不能为空，请选择租户或传递 X-Tenant-Id");
  }

  private String resolvePersonalTenantId() {
    if (tenantService == null) {
      return null;
    }
    Collection<NamedTenantVo> tenants = tenantService.getCurrentUserTenants();
    if (tenants == null || tenants.isEmpty()) {
      return null;
    }
    List<NamedTenantVo> validTenants = tenants.stream()
        .filter(Objects::nonNull)
        .filter(tenant -> trimToNull(tenant.tenantId()) != null)
        .toList();
    return validTenants.stream()
        .filter(tenant -> tenant.tenantType() == TenantContextType.PERSONAL)
        .map(NamedTenantVo::tenantId)
        .map(ObjectStorageObjectServiceImpl::trimToNull)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseGet(() -> validTenants.size() == 1
            ? trimToNull(validTenants.get(0).tenantId())
            : null);
  }

  private String resolveSourceServiceName(final ObjectStorageUploadRequest request) {
    String requested = request == null ? null : trimToNull(request.getSourceServiceName());
    return requested == null ? applicationName : requested;
  }

  private ResolvedProvider resolveProvider(final String requestedCode, final boolean allowDisabled) {
    if (providerConfigService != null) {
      org.simplepoint.plugin.storage.api.model.ResolvedObjectStorageProvider resolved =
          providerConfigService.resolve(requestedCode, allowDisabled);
      return new ResolvedProvider(resolved.code(), resolved.properties());
    }
    Map<String, ObjectStorageProperties.ProviderProperties> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      throw new IllegalStateException("未配置对象存储提供方");
    }
    String providerCode = trimToNull(requestedCode);
    if (providerCode == null) {
      providerCode = trimToNull(properties.getDefaultProvider());
    }
    if (providerCode == null) {
      List<String> enabledProviders = providers.entrySet().stream()
          .filter(entry -> entry.getValue() != null && Boolean.TRUE.equals(entry.getValue().getEnabled()))
          .map(Map.Entry::getKey)
          .sorted()
          .toList();
      if (enabledProviders.size() == 1) {
        providerCode = enabledProviders.get(0);
      }
    }
    if (providerCode == null) {
      throw new IllegalStateException("请先选择对象存储提供方");
    }
    ObjectStorageProperties.ProviderProperties provider = providers.get(providerCode);
    if (provider == null) {
      throw new NoSuchElementException("对象存储提供方不存在: " + providerCode);
    }
    if (!allowDisabled && !Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalStateException("对象存储提供方未启用: " + providerCode);
    }
    if (provider.getType() == null) {
      throw new IllegalStateException("对象存储提供方未配置平台类型: " + providerCode);
    }
    if (trimToNull(provider.getBucket()) == null) {
      throw new IllegalStateException("对象存储提供方未配置 bucket: " + providerCode);
    }
    provider.setBucket(provider.getBucket().trim());
    return new ResolvedProvider(providerCode, provider);
  }

  private ObjectStorageDriver resolveDriver(final ObjectStoragePlatformType type) {
    return drivers.stream()
        .filter(driver -> driver.supports(type))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("未找到可用的对象存储驱动: " + type));
  }

  private static String normalizeContentType(final String contentType) {
    String normalized = trimToNull(contentType);
    return normalized == null ? "application/octet-stream" : normalized;
  }

  private static String sanitizePath(final String value) {
    return value.replace('\\', '/')
        .replaceAll("/+", "/")
        .replaceAll("^/+", "")
        .replaceAll("/+$", "");
  }

  private static String sanitizeFileName(final String value) {
    return value.replaceAll("[\\r\\n]", "")
        .replaceAll("[/\\\\]+", "-")
        .replaceAll("\\s+", "-");
  }

  private static String sanitizeTenantId(final String value) {
    String normalized = value.replaceAll("[^A-Za-z0-9._-]", "-");
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("租户ID格式不正确");
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  @Getter
  private static final class ResolvedProvider implements Serializable {

    private final String code;

    private final ObjectStorageProperties.ProviderProperties properties;

    private ResolvedProvider(final String code, final ObjectStorageProperties.ProviderProperties properties) {
      this.code = code;
      this.properties = properties;
    }
  }
}
