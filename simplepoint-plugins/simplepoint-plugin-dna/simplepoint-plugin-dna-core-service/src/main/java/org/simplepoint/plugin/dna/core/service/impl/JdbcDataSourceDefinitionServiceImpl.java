package org.simplepoint.plugin.dna.core.service.impl;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDataSourceDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcManagedDataSourceCustomizer;
import org.simplepoint.plugin.dna.core.api.spi.JdbcManagedDataSourceFactory;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDataSourceConnectionResult;
import org.simplepoint.plugin.dna.core.service.support.JdbcDriverArtifactManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Managed datasource definition service implementation.
 */
@Service
public class JdbcDataSourceDefinitionServiceImpl
    extends BaseServiceImpl<JdbcDataSourceDefinitionRepository, JdbcDataSourceDefinition, String>
    implements JdbcDataSourceDefinitionService {

  private final JdbcDataSourceDefinitionRepository repository;

  private final JdbcDriverDefinitionRepository driverRepository;

  private final JdbcDriverArtifactManager artifactManager;

  private final List<JdbcManagedDataSourceFactory> factories;

  private final List<JdbcManagedDataSourceCustomizer> customizers;

  private final Map<String, SimpleDataSource> runtimeDataSources = new ConcurrentHashMap<>();

  /**
   * Creates a datasource definition service implementation.
   *
   * @param repository             datasource repository
   * @param detailsProviderService details provider service
   * @param driverRepository       driver repository
   * @param artifactManager        driver artifact manager
   * @param factories              datasource factories
   * @param customizers            datasource customizers
   */
  public JdbcDataSourceDefinitionServiceImpl(
      final JdbcDataSourceDefinitionRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDriverArtifactManager artifactManager,
      final List<JdbcManagedDataSourceFactory> factories,
      final List<JdbcManagedDataSourceCustomizer> customizers
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.driverRepository = driverRepository;
    this.artifactManager = artifactManager;
    this.factories = factories == null ? List.of() : List.copyOf(factories);
    this.customizers = customizers == null ? List.of() : List.copyOf(customizers);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<JdbcDataSourceDefinition> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<JdbcDataSourceDefinition> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public List<JdbcDataSourceDefinition> listEnabledDefinitions() {
    List<JdbcDataSourceDefinition> definitions = repository.findAllActive().stream()
        .filter(definition -> Boolean.TRUE.equals(definition.getEnabled()))
        .sorted(Comparator.comparing(
            JdbcDataSourceDefinition::getCode,
            Comparator.nullsLast(String::compareTo)
        ))
        .toList();
    decorate(definitions);
    return definitions;
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDataSourceConnectionResult connect(final String id) {
    JdbcDataSourceDefinition definition = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    JdbcDriverDefinition driver = resolveDriver(definition.getDriverId());
    Instant testedAt = Instant.now();
    try {
      SimpleDataSource simpleDataSource = obtainSimpleDataSource(definition, driver, false);
      try (Connection connection = simpleDataSource.getConnection()) {
        DatabaseMetaData metaData = connection.getMetaData();
        definition.setLastConnectStatus("SUCCESS");
        definition.setLastConnectMessage("连接成功");
        definition.setLastTestedAt(testedAt);
        definition.setDatabaseProductName(metaData.getDatabaseProductName());
        definition.setDatabaseProductVersion(metaData.getDatabaseProductVersion());
        JdbcDataSourceDefinition saved = repository.save(definition);
        decorate(saved);
        return new JdbcDataSourceConnectionResult(
            saved.getId(),
            saved.getCode(),
            saved.getDriverId(),
            saved.getDriverCode(),
            true,
            testedAt,
            saved.getLastConnectMessage(),
            saved.getDatabaseProductName(),
            saved.getDatabaseProductVersion(),
            saved.getJdbcUrl()
        );
      }
    } catch (Exception ex) {
      disconnect(definition.getId());
      definition.setLastConnectStatus("FAILED");
      definition.setLastConnectMessage(truncateMessage(ex.getMessage()));
      definition.setLastTestedAt(testedAt);
      repository.save(definition);
      throw new IllegalStateException("数据源连接失败: " + ex.getMessage(), ex);
    }
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SimpleDataSource> getCachedSimpleDataSource(final String id) {
    return Optional.ofNullable(runtimeDataSources.get(id));
  }

  /** {@inheritDoc} */
  @Override
  public SimpleDataSource requireSimpleDataSource(final String id) {
    JdbcDataSourceDefinition definition = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    JdbcDriverDefinition driver = resolveDriver(definition.getDriverId());
    return obtainSimpleDataSource(definition, driver, true);
  }

  /** {@inheritDoc} */
  @Override
  public SimpleDataSource createTransientSimpleDataSource(final String id, final String jdbcUrl) {
    JdbcDataSourceDefinition definition = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    JdbcDriverDefinition driver = resolveDriver(definition.getDriverId());
    ensureEnabled(definition, driver);
    JdbcDataSourceDefinition runtimeDefinition = copyForRuntime(definition);
    runtimeDefinition.setJdbcUrl(requireValue(jdbcUrl, "JDBC连接串不能为空"));
    if (!Pattern.compile(driver.getJdbcUrlPattern()).matcher(runtimeDefinition.getJdbcUrl()).matches()) {
      throw new IllegalArgumentException("JDBC连接串与驱动规则不匹配: " + runtimeDefinition.getJdbcUrl());
    }
    return createSimpleDataSource(runtimeDefinition, driver);
  }

  /** {@inheritDoc} */
  @Override
  public void disconnect(final String id) {
    if (id != null && !id.isBlank()) {
      closeQuietly(runtimeDataSources.remove(id));
    }
  }

  /** {@inheritDoc} */
  @Override
  public void disconnectByDriverId(final String driverId) {
    String normalizedDriverId = trimToNull(driverId);
    if (normalizedDriverId == null) {
      return;
    }
    repository.findAllActiveByDriverId(normalizedDriverId)
        .stream()
        .map(JdbcDataSourceDefinition::getId)
        .map(runtimeDataSources::remove)
        .forEach(JdbcDataSourceDefinitionServiceImpl::closeQuietly);
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDataSourceDefinition> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    normalizeLikeQuery(normalized, "jdbcUrl");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDataSourceDefinition> S create(final S entity) {
    normalizeAndValidate(entity, null, null);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    S saved = super.create(entity);
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends JdbcDataSourceDefinition> JdbcDataSourceDefinition modifyById(final S entity) {
    JdbcDataSourceDefinition current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId(), current);
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    entity.setLastConnectStatus(current.getLastConnectStatus());
    entity.setLastConnectMessage(current.getLastConnectMessage());
    entity.setLastTestedAt(current.getLastTestedAt());
    entity.setDatabaseProductName(current.getDatabaseProductName());
    entity.setDatabaseProductVersion(current.getDatabaseProductVersion());
    JdbcDataSourceDefinition updated = (JdbcDataSourceDefinition) super.modifyById(entity);
    disconnect(updated.getId());
    decorate(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    ids.forEach(this::disconnect);
    super.removeByIds(ids);
  }

  private void normalizeAndValidate(
      final JdbcDataSourceDefinition entity,
      final String currentId,
      final JdbcDataSourceDefinition current
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("数据源定义不能为空");
    }
    entity.setName(requireValue(entity.getName(), "数据源名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "数据源编码不能为空"));
    entity.setDriverId(requireValue(entity.getDriverId(), "驱动ID不能为空"));
    entity.setJdbcUrl(requireValue(entity.getJdbcUrl(), "JDBC连接串不能为空"));
    entity.setUsername(trimToNull(entity.getUsername()));
    String password = trimToNull(entity.getPassword());
    entity.setPassword(password == null && current != null ? current.getPassword() : password);
    entity.setConnectionProperties(trimToNull(entity.getConnectionProperties()));
    entity.setDescription(trimToNull(entity.getDescription()));
    if (entity.getConnectionProperties() != null) {
      parseConnectionProperties(entity.getConnectionProperties());
    }
    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("数据源编码已存在: " + entity.getCode());
        });
    JdbcDriverDefinition driver = resolveDriver(entity.getDriverId());
    if (!Pattern.compile(driver.getJdbcUrlPattern()).matcher(entity.getJdbcUrl()).matches()) {
      throw new IllegalArgumentException("JDBC连接串与驱动规则不匹配: " + entity.getJdbcUrl());
    }
  }

  private JdbcDriverDefinition resolveDriver(final String driverId) {
    return driverRepository.findActiveById(driverId)
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + driverId));
  }

  private SimpleDataSource obtainSimpleDataSource(
      final JdbcDataSourceDefinition definition,
      final JdbcDriverDefinition driver,
      final boolean requireEnabled
  ) {
    if (requireEnabled) {
      ensureEnabled(definition, driver);
    }
    return runtimeDataSources.computeIfAbsent(definition.getId(), ignored -> createSimpleDataSource(definition, driver));
  }

  private void ensureEnabled(
      final JdbcDataSourceDefinition definition,
      final JdbcDriverDefinition driver
  ) {
    if (!Boolean.TRUE.equals(definition.getEnabled())) {
      throw new IllegalStateException("数据源已禁用: " + definition.getCode());
    }
    if (!Boolean.TRUE.equals(driver.getEnabled())) {
      throw new IllegalStateException("驱动已禁用: " + driver.getCode());
    }
  }

  private SimpleDataSource createSimpleDataSource(
      final JdbcDataSourceDefinition definition,
      final JdbcDriverDefinition driver
  ) {
    JdbcDriverDefinition resolvedDriver = artifactManager.ensureDownloaded(driver);
    JdbcManagedDataSourceFactory factory = factories.stream()
        .filter(candidate -> candidate.supports(resolvedDriver))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("未找到可用的数据源工厂: " + resolvedDriver.getCode()));
    SimpleDataSource simpleDataSource = factory.create(resolvedDriver, definition);
    for (JdbcManagedDataSourceCustomizer customizer : customizers) {
      SimpleDataSource customized = customizer.customize(resolvedDriver, definition, simpleDataSource);
      if (customized == null) {
        throw new IllegalStateException("数据源定制器返回了空对象: " + customizer.getClass().getName());
      }
      simpleDataSource = customized;
    }
    return simpleDataSource;
  }

  private JdbcDataSourceDefinition decorate(final JdbcDataSourceDefinition definition) {
    if (definition == null) {
      return null;
    }
    decorate(List.of(definition));
    return definition;
  }

  private void decorate(final Collection<? extends JdbcDataSourceDefinition> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      return;
    }
    Set<String> driverIds = definitions.stream()
        .map(JdbcDataSourceDefinition::getDriverId)
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.toSet());
    Map<String, JdbcDriverDefinition> drivers = driverRepository.findAllByIds(driverIds).stream()
        .collect(Collectors.toMap(JdbcDriverDefinition::getId, driver -> driver, (left, right) -> left));
    definitions.forEach(definition -> {
      JdbcDriverDefinition driver = drivers.get(definition.getDriverId());
      if (driver != null) {
        definition.setDriverCode(driver.getCode());
        definition.setDriverName(driver.getName());
      }
    });
  }

  private static JdbcDataSourceDefinition copyForRuntime(final JdbcDataSourceDefinition source) {
    JdbcDataSourceDefinition runtime = new JdbcDataSourceDefinition();
    runtime.setId(source.getId());
    runtime.setName(source.getName());
    runtime.setCode(source.getCode());
    runtime.setDriverId(source.getDriverId());
    runtime.setDriverCode(source.getDriverCode());
    runtime.setDriverName(source.getDriverName());
    runtime.setJdbcUrl(source.getJdbcUrl());
    runtime.setUsername(source.getUsername());
    runtime.setPassword(source.getPassword());
    runtime.setConnectionProperties(source.getConnectionProperties());
    runtime.setEnabled(source.getEnabled());
    runtime.setDescription(source.getDescription());
    runtime.setLastConnectStatus(source.getLastConnectStatus());
    runtime.setLastConnectMessage(source.getLastConnectMessage());
    runtime.setLastTestedAt(source.getLastTestedAt());
    runtime.setDatabaseProductName(source.getDatabaseProductName());
    runtime.setDatabaseProductVersion(source.getDatabaseProductVersion());
    return runtime;
  }

  private static Properties parseConnectionProperties(final String rawProperties) {
    Properties properties = new Properties();
    try (StringReader reader = new StringReader(rawProperties)) {
      properties.load(reader);
      return properties;
    } catch (Exception ex) {
      throw new IllegalArgumentException("连接属性格式不正确，请使用 Java Properties 格式", ex);
    }
  }

  private static void normalizeLikeQuery(final Map<String, String> attributes, final String key) {
    String value = attributes.get(key);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(key, "like:" + value.trim());
    }
  }

  private static String requireEntityId(final JdbcDataSourceDefinition entity) {
    if (entity == null || entity.getId() == null || entity.getId().isBlank()) {
      throw new IllegalArgumentException("数据源ID不能为空");
    }
    return entity.getId();
  }

  private static String requireId(final String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("数据源ID不能为空");
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

  private static void closeQuietly(final SimpleDataSource dataSource) {
    if (dataSource == null) {
      return;
    }
    try {
      dataSource.close();
    } catch (RuntimeException ex) {
      throw ex;
    }
  }

  private static String truncateMessage(final String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "连接失败";
    }
    return normalized.length() > 1024 ? normalized.substring(0, 1024) : normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
