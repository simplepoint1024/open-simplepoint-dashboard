/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.model.ObjectStorageConnectionTestResult;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.model.ObjectStorageProviderDefinition;
import org.simplepoint.plugin.storage.api.model.ResolvedObjectStorageProvider;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageProviderConfigRepository;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.service.security.ObjectStorageCredentialCipher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-global OSS provider configuration service.
 */
@Service
public class ObjectStorageProviderConfigServiceImpl
    extends BaseServiceImpl<ObjectStorageProviderConfigRepository, ObjectStorageProviderConfig, String>
    implements ObjectStorageProviderConfigService {

  private final ObjectStorageProviderConfigRepository repository;

  private final ObjectStorageObjectRepository objectRepository;

  private final ObjectStorageCredentialCipher credentialCipher;

  private final ObjectStorageProperties properties;

  private final List<ObjectStorageDriver> drivers;

  /**
   * Creates the provider configuration service.
   */
  public ObjectStorageProviderConfigServiceImpl(
      final ObjectStorageProviderConfigRepository repository,
      final DetailsProviderService detailsProviderService,
      final ObjectStorageObjectRepository objectRepository,
      final ObjectStorageCredentialCipher credentialCipher,
      final ObjectStorageProperties properties,
      final List<ObjectStorageDriver> drivers
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.objectRepository = objectRepository;
    this.credentialCipher = credentialCipher;
    this.properties = properties;
    this.drivers = drivers == null ? List.of() : List.copyOf(drivers);
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends ObjectStorageProviderConfig> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLike(normalized, "name");
    normalizeLike(normalized, "code");
    Page<S> page = super.limit(normalized, pageable);
    page.getContent().forEach(this::decorate);
    return page;
  }

  @Override
  public Collection<ObjectStorageProviderDefinition> providers() {
    if (!repository.existsAnyActive()) {
      return propertyProviderDefinitions();
    }
    return repository.findAllActiveEnabled().stream()
        .map(provider -> new ObjectStorageProviderDefinition(
            provider.getCode(),
            provider.getName(),
            provider.getType(),
            provider.getEndpoint(),
            provider.getBucket(),
            provider.getDefaultProvider()
        ))
        .toList();
  }

  @Override
  public ResolvedObjectStorageProvider resolve(
      final String requestedCode,
      final boolean allowDisabled
  ) {
    if (!repository.existsAnyActive()) {
      return resolvePropertyProvider(requestedCode, allowDisabled);
    }
    String providerCode = trimToNull(requestedCode);
    ObjectStorageProviderConfig provider;
    if (providerCode != null) {
      provider = repository.findActiveByCode(providerCode)
          .orElseThrow(() -> new NoSuchElementException("OSS 配置不存在: " + providerCode));
    } else {
      provider = repository.findDefaultEnabled().orElseGet(() -> {
        List<ObjectStorageProviderConfig> enabled = repository.findAllActiveEnabled();
        if (enabled.size() == 1) {
          return enabled.get(0);
        }
        throw new IllegalStateException("请先设置系统默认 OSS 配置");
      });
    }
    if (!allowDisabled && !Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalStateException("OSS 配置未启用: " + provider.getCode());
    }
    return new ResolvedObjectStorageProvider(provider.getCode(), toRuntimeProperties(provider));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends ObjectStorageProviderConfig> S create(final S entity) {
    normalizeAndValidate(entity, null, true);
    String ciphertext = credentialCipher.encrypt(entity.getSecretKey().trim());
    entity.setSecretKey(null);
    entity.setSecretKeyCiphertext(ciphertext);
    S saved = super.create(entity);
    if (!Objects.equals(ciphertext, saved.getSecretKeyCiphertext())) {
      saved.setSecretKeyCiphertext(ciphertext);
      saved = repository.save(saved);
    }
    applyDefault(saved);
    decorate(saved);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends ObjectStorageProviderConfig> ObjectStorageProviderConfig modifyById(final S entity) {
    if (entity == null || trimToNull(entity.getId()) == null) {
      throw new IllegalArgumentException("OSS 配置 ID 不能为空");
    }
    ObjectStorageProviderConfig current = repository.findActiveById(entity.getId())
        .orElseThrow(() -> new NoSuchElementException("OSS 配置不存在: " + entity.getId()));
    entity.setCode(current.getCode());
    normalizeAndValidate(entity, current.getId(), false);
    String ciphertext = current.getSecretKeyCiphertext();
    if (trimToNull(entity.getSecretKey()) != null) {
      ciphertext = credentialCipher.encrypt(entity.getSecretKey().trim());
    }
    entity.setSecretKey(null);
    entity.setSecretKeyCiphertext(current.getSecretKeyCiphertext());
    ObjectStorageProviderConfig updated = (ObjectStorageProviderConfig) super.modifyById(entity);
    if (!Objects.equals(ciphertext, updated.getSecretKeyCiphertext())) {
      updated.setSecretKeyCiphertext(ciphertext);
      updated = repository.save(updated);
    }
    applyDefault(updated);
    decorate(updated);
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (String id : ids) {
      ObjectStorageProviderConfig provider = repository.findActiveById(id)
          .orElseThrow(() -> new NoSuchElementException("OSS 配置不存在: " + id));
      if (objectRepository.existsActiveByProviderCode(provider.getCode())) {
        throw new IllegalStateException("OSS 配置已被文件引用，不能删除: " + provider.getCode());
      }
    }
    super.removeByIds(ids);
  }

  @Override
  public ObjectStorageConnectionTestResult testConnection(final String id) {
    ObjectStorageProviderConfig provider = repository.findActiveById(id)
        .orElseThrow(() -> new NoSuchElementException("OSS 配置不存在: " + id));
    ObjectStorageProperties.ProviderProperties runtimeProperties = toRuntimeProperties(provider);
    resolveDriver(provider.getType()).testConnection(runtimeProperties);
    return new ObjectStorageConnectionTestResult(true, "连接成功，Bucket 可访问");
  }

  private void applyDefault(final ObjectStorageProviderConfig provider) {
    if (Boolean.TRUE.equals(provider.getDefaultProvider())) {
      repository.clearDefaultExcept(provider.getId());
    }
  }

  private void normalizeAndValidate(
      final ObjectStorageProviderConfig entity,
      final String currentId,
      final boolean creating
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("OSS 配置不能为空");
    }
    entity.setCode(require(entity.getCode(), "配置编码不能为空").toLowerCase());
    if (!entity.getCode().matches("[a-z0-9][a-z0-9._-]{1,63}")) {
      throw new IllegalArgumentException("配置编码只能包含小写字母、数字、点、下划线和短横线");
    }
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("OSS 配置编码已存在: " + entity.getCode());
        });
    entity.setName(require(entity.getName(), "配置名称不能为空"));
    if (entity.getType() == null) {
      throw new IllegalArgumentException("存储平台不能为空");
    }
    entity.setAccessKey(require(entity.getAccessKey(), "Access Key 不能为空"));
    if (creating && trimToNull(entity.getSecretKey()) == null) {
      throw new IllegalArgumentException("Secret Key 不能为空");
    }
    entity.setBucket(require(entity.getBucket(), "Bucket 不能为空"));
    entity.setRegion(defaultString(entity.getRegion(), "us-east-1"));
    entity.setEndpoint(trimToNull(entity.getEndpoint()));
    if (entity.getType() != ObjectStoragePlatformType.S3 && entity.getEndpoint() == null) {
      throw new IllegalArgumentException("当前平台必须配置 Endpoint");
    }
    validateUrl(entity.getEndpoint(), "Endpoint");
    entity.setPublicBaseUrl(trimToNull(entity.getPublicBaseUrl()));
    validateUrl(entity.getPublicBaseUrl(), "公开访问地址");
    entity.setBasePath(normalizePath(entity.getBasePath()));
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    entity.setDefaultProvider(
        entity.getDefaultProvider() == null ? Boolean.FALSE : entity.getDefaultProvider()
    );
    if (Boolean.TRUE.equals(entity.getDefaultProvider()) && !Boolean.TRUE.equals(entity.getEnabled())) {
      throw new IllegalArgumentException("系统默认 OSS 配置必须启用");
    }
    entity.setDescription(trimToNull(entity.getDescription()));
  }

  private ObjectStorageProperties.ProviderProperties toRuntimeProperties(
      final ObjectStorageProviderConfig provider
  ) {
    ObjectStorageProperties.ProviderProperties runtime = new ObjectStorageProperties.ProviderProperties();
    runtime.setName(provider.getName());
    runtime.setType(provider.getType());
    runtime.setEndpoint(provider.getEndpoint());
    runtime.setRegion(provider.getRegion());
    runtime.setAccessKey(provider.getAccessKey());
    runtime.setSecretKey(credentialCipher.decrypt(provider.getSecretKeyCiphertext()));
    runtime.setBucket(provider.getBucket());
    runtime.setBasePath(provider.getBasePath());
    runtime.setPathStyleAccess(provider.getPathStyleAccess());
    runtime.setEnabled(provider.getEnabled());
    runtime.setPublicBaseUrl(provider.getPublicBaseUrl());
    return runtime;
  }

  private ResolvedObjectStorageProvider resolvePropertyProvider(
      final String requestedCode,
      final boolean allowDisabled
  ) {
    Map<String, ObjectStorageProperties.ProviderProperties> configured = properties.getProviders();
    if (configured == null || configured.isEmpty()) {
      throw new IllegalStateException("未配置可用的 OSS 连接");
    }
    String code = trimToNull(requestedCode);
    if (code == null) {
      code = trimToNull(properties.getDefaultProvider());
    }
    if (code == null) {
      List<String> enabled = configured.entrySet().stream()
          .filter(entry -> entry.getValue() != null && Boolean.TRUE.equals(entry.getValue().getEnabled()))
          .map(Map.Entry::getKey)
          .sorted()
          .toList();
      if (enabled.size() == 1) {
        code = enabled.get(0);
      }
    }
    if (code == null) {
      throw new IllegalStateException("请先设置系统默认 OSS 配置");
    }
    ObjectStorageProperties.ProviderProperties provider = configured.get(code);
    if (provider == null) {
      throw new NoSuchElementException("OSS 配置不存在: " + code);
    }
    if (!allowDisabled && !Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalStateException("OSS 配置未启用: " + code);
    }
    return new ResolvedObjectStorageProvider(code, provider);
  }

  private Collection<ObjectStorageProviderDefinition> propertyProviderDefinitions() {
    Map<String, ObjectStorageProperties.ProviderProperties> configured = properties.getProviders();
    if (configured == null || configured.isEmpty()) {
      return List.of();
    }
    String defaultProvider = trimToNull(properties.getDefaultProvider());
    List<ObjectStorageProviderDefinition> result = new ArrayList<>();
    configured.forEach((code, provider) -> {
      if (provider != null && Boolean.TRUE.equals(provider.getEnabled())) {
        result.add(new ObjectStorageProviderDefinition(
            code,
            defaultString(provider.getName(), code),
            provider.getType(),
            trimToNull(provider.getEndpoint()),
            trimToNull(provider.getBucket()),
            code.equals(defaultProvider)
        ));
      }
    });
    result.sort(Comparator
        .comparing(ObjectStorageProviderDefinition::getDefaultProvider, Comparator.reverseOrder())
        .thenComparing(ObjectStorageProviderDefinition::getCode));
    return result;
  }

  private ObjectStorageDriver resolveDriver(final ObjectStoragePlatformType type) {
    return drivers.stream()
        .filter(driver -> driver.supports(type))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("未找到可用的对象存储驱动: " + type));
  }

  private void decorate(final ObjectStorageProviderConfig provider) {
    provider.setSecretKey(null);
    provider.setSecretConfigured(trimToNull(provider.getSecretKeyCiphertext()) != null);
  }

  private static void normalizeLike(final Map<String, String> attributes, final String field) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }

  private static String normalizePath(final String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return normalized.replace('\\', '/').replaceAll("/+", "/")
        .replaceAll("^/+", "").replaceAll("/+$", "");
  }

  private static void validateUrl(final String value, final String label) {
    if (value == null) {
      return;
    }
    try {
      URI uri = URI.create(value);
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw new IllegalArgumentException(label + " 必须是完整的 HTTP(S) URL");
      }
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(label + " 格式不正确", ex);
    }
  }

  private static String require(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String defaultString(final String value, final String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
