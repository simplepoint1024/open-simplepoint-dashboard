package org.simplepoint.plugin.dna.core.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverDownloadResult;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverUploadRequest;
import org.simplepoint.plugin.dna.core.service.support.JdbcDriverArtifactManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * JDBC driver definition service implementation.
 */
@Service
public class JdbcDriverDefinitionServiceImpl
    extends BaseServiceImpl<JdbcDriverDefinitionRepository, JdbcDriverDefinition, String>
    implements JdbcDriverDefinitionService {

  private final JdbcDriverDefinitionRepository repository;

  private final JdbcDriverArtifactManager artifactManager;

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a driver definition service implementation.
   *
   * @param repository             driver repository
   * @param detailsProviderService details provider service
   * @param artifactManager        driver artifact manager
   * @param dataSourceService      datasource service
   */
  public JdbcDriverDefinitionServiceImpl(
      final JdbcDriverDefinitionRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDriverArtifactManager artifactManager,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.artifactManager = artifactManager;
    this.dataSourceService = dataSourceService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<JdbcDriverDefinition> findActiveById(final String id) {
    return repository.findActiveById(id);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<JdbcDriverDefinition> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code));
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDriverDefinition createByUpload(final MultipartFile file, final JdbcDriverUploadRequest request) {
    JdbcDriverDefinition entity = toEntity(request);
    normalizeAndValidateForUpload(entity, null);
    artifactManager.uploadDraft(entity, file);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDriverDownloadResult download(final String id) {
    JdbcDriverDefinition driver = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + id));
    JdbcDriverDefinition downloaded = artifactManager.download(driver);
    dataSourceService.disconnectByDriverId(downloaded.getId());
    return new JdbcDriverDownloadResult(
        downloaded.getId(),
        downloaded.getCode(),
        downloaded.getLocalJarPath(),
        downloaded.getDriverClassName(),
        downloaded.getJdbcUrlPattern(),
        downloaded.getVersion(),
        downloaded.getDownloadedAt(),
        downloaded.getLastDownloadMessage()
    );
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDriverDefinition upload(final String id, final MultipartFile file) {
    JdbcDriverDefinition driver = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + id));
    JdbcDriverDefinition uploaded = artifactManager.upload(driver, file);
    dataSourceService.disconnectByDriverId(uploaded.getId());
    return uploaded;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDriverDefinition> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    normalizeLikeQuery(normalized, "databaseType");
    return super.limit(normalized, pageable);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDriverDefinition> S create(final S entity) {
    normalizeAndValidateForCreate(entity, null);
    artifactManager.downloadDraft(entity);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDriverDefinition> JdbcDriverDefinition modifyById(final S entity) {
    JdbcDriverDefinition current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + entity.getId()));
    boolean artifactRefreshRequired = artifactRefreshRequired(current, entity);
    normalizeAndValidateForModify(entity, current.getId());
    if (artifactRefreshRequired) {
      artifactManager.downloadDraft(entity);
    } else {
      copyArtifactState(current, entity);
      if (artifactMetadataMissing(entity) && artifactManager.hasStoredArtifact(current)) {
        artifactManager.resolveLocalMetadata(entity);
      }
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    JdbcDriverDefinition updated = (JdbcDriverDefinition) super.modifyById(entity);
    dataSourceService.disconnectByDriverId(updated.getId());
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    ids.forEach(dataSourceService::disconnectByDriverId);
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(
      final JdbcDriverDefinition entity,
      final String currentId
  ) {
    normalizeCommonFields(entity, currentId);
    entity.setDownloadUrl(requireValue(entity.getDownloadUrl(), "驱动下载地址不能为空"));
    validateDownloadUrl(entity.getDownloadUrl());
  }

  private void normalizeAndValidateForCreate(
      final JdbcDriverDefinition entity,
      final String currentId
  ) {
    normalizeAndValidate(entity, currentId);
  }

  private void normalizeAndValidateForModify(
      final JdbcDriverDefinition entity,
      final String currentId
  ) {
    normalizeCommonFields(entity, currentId);
    entity.setDownloadUrl(trimToNull(entity.getDownloadUrl()));
    validateOptionalDownloadUrl(entity.getDownloadUrl());
  }

  private void normalizeAndValidateForUpload(
      final JdbcDriverDefinition entity,
      final String currentId
  ) {
    normalizeCommonFields(entity, currentId);
    entity.setDownloadUrl(trimToNull(entity.getDownloadUrl()));
    validateOptionalDownloadUrl(entity.getDownloadUrl());
  }

  private void normalizeCommonFields(
      final JdbcDriverDefinition entity,
      final String currentId
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("驱动定义不能为空");
    }
    entity.setName(requireValue(entity.getName(), "驱动名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "驱动编码不能为空"));
    entity.setDatabaseType(requireValue(entity.getDatabaseType(), "数据库类型不能为空"));
    entity.setVersion(trimToNull(entity.getVersion()));
    entity.setDriverClassName(trimToNull(entity.getDriverClassName()));
    entity.setJdbcUrlPattern(trimToNull(entity.getJdbcUrlPattern()));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("驱动编码已存在: " + entity.getCode());
        });
  }

  private static boolean artifactRefreshRequired(
      final JdbcDriverDefinition current,
      final JdbcDriverDefinition target
  ) {
    String targetDownloadUrl = trimToNull(target.getDownloadUrl());
    return targetDownloadUrl != null && !sameText(current.getDownloadUrl(), targetDownloadUrl);
  }

  private static void validateOptionalDownloadUrl(final String downloadUrl) {
    if (trimToNull(downloadUrl) == null) {
      return;
    }
    validateDownloadUrl(downloadUrl);
  }

  private static void validateDownloadUrl(final String downloadUrl) {
    try {
      String scheme = java.net.URI.create(downloadUrl).getScheme();
      if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException("驱动下载地址仅支持 HTTP 或 HTTPS");
      }
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("驱动下载地址格式不正确: " + downloadUrl, ex);
    }
  }

  private static void normalizeLikeQuery(final Map<String, String> attributes, final String key) {
    String value = attributes.get(key);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(key, "like:" + value.trim());
    }
  }

  private static String requireEntityId(final JdbcDriverDefinition entity) {
    if (entity == null || entity.getId() == null || entity.getId().isBlank()) {
      throw new IllegalArgumentException("驱动ID不能为空");
    }
    return entity.getId();
  }

  private static String requireId(final String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("驱动ID不能为空");
    }
    return id;
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
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

  private static boolean sameText(final String left, final String right) {
    return Optional.ofNullable(trimToNull(left)).equals(Optional.ofNullable(trimToNull(right)));
  }

  private static void copyArtifactState(
      final JdbcDriverDefinition source,
      final JdbcDriverDefinition target
  ) {
    target.setVersion(source.getVersion());
    target.setDriverClassName(source.getDriverClassName());
    target.setJdbcUrlPattern(source.getJdbcUrlPattern());
    target.setLocalJarPath(source.getLocalJarPath());
    target.setDownloadedAt(source.getDownloadedAt());
    target.setLastDownloadMessage(source.getLastDownloadMessage());
  }

  private static boolean artifactMetadataMissing(final JdbcDriverDefinition entity) {
    return trimToNull(entity.getDriverClassName()) == null || trimToNull(entity.getJdbcUrlPattern()) == null;
  }

  private static JdbcDriverDefinition toEntity(final JdbcDriverUploadRequest request) {
    JdbcDriverDefinition entity = new JdbcDriverDefinition();
    if (request == null) {
      return entity;
    }
    entity.setName(request.getName());
    entity.setCode(request.getCode());
    entity.setDatabaseType(request.getDatabaseType());
    entity.setDownloadUrl(request.getDownloadUrl());
    entity.setEnabled(request.getEnabled());
    entity.setDescription(request.getDescription());
    return entity;
  }
}
