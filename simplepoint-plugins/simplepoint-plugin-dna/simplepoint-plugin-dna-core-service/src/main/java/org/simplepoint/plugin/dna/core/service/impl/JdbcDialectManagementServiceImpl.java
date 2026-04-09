package org.simplepoint.plugin.dna.core.service.impl;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDialectSourceDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.support.JdbcDialectSourceArtifactManager;
import org.simplepoint.plugin.dna.core.service.support.JdbcDialectSourceArtifactManager.LoadedDialect;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * JDBC dialect discovery and management service implementation.
 */
@Service
public class JdbcDialectManagementServiceImpl implements JdbcDialectManagementService {

  private final JdbcDialectSourceDefinitionRepository sourceRepository;

  private final JdbcDriverDefinitionRepository driverRepository;

  private final JdbcDialectSourceArtifactManager artifactManager;

  /**
   * Creates a dialect management service implementation.
   *
   * @param sourceRepository source repository
   * @param driverRepository driver repository
   * @param artifactManager artifact manager
   */
  public JdbcDialectManagementServiceImpl(
      final JdbcDialectSourceDefinitionRepository sourceRepository,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectSourceArtifactManager artifactManager
  ) {
    this.sourceRepository = sourceRepository;
    this.driverRepository = driverRepository;
    this.artifactManager = artifactManager;
  }

  /** {@inheritDoc} */
  @Override
  public List<JdbcMetadataModels.DialectDescriptor> listLoadedDialects() {
    List<LoadedDialect> loadedDialects = discoverLoadedDialects();
    Map<String, List<String>> bindings = resolveBindings(loadedDialects);
    return loadedDialects.stream()
        .map(loaded -> toDescriptor(loaded, bindings.getOrDefault(descriptorKey(loaded), List.of())))
        .sorted(Comparator.comparingInt(JdbcMetadataModels.DialectDescriptor::order)
            .thenComparing(JdbcMetadataModels.DialectDescriptor::code))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public List<JdbcMetadataModels.DialectSourceSummary> listSources() {
    Map<String, List<LoadedDialect>> grouped = discoverLoadedDialects().stream()
        .filter(loaded -> loaded.sourceId() != null)
        .collect(Collectors.groupingBy(LoadedDialect::sourceId, LinkedHashMap::new, Collectors.toList()));
    return sourceRepository.findAllActive().stream()
        .map(source -> new JdbcMetadataModels.DialectSourceSummary(
            source.getId(),
            source.getName(),
            JdbcMetadataModels.SourceType.valueOf(source.getSourceType().toUpperCase()),
            source.getSourceUrl(),
            source.getLocalJarPath(),
            source.getEnabled(),
            source.getDescription(),
            source.getLoadedAt(),
            source.getLastLoadMessage(),
            grouped.getOrDefault(source.getId(), List.of()).stream()
                .map(loaded -> loaded.dialect().code())
                .distinct()
                .toList()
        ))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDialectSourceDefinition createUrlSource(final JdbcDialectSourceDefinition request) {
    JdbcDialectSourceDefinition source = normalizeSource(request, JdbcMetadataModels.SourceType.URL);
    JdbcDialectSourceDefinition loaded = artifactManager.downloadDraft(source);
    return sourceRepository.save(loaded);
  }

  /** {@inheritDoc} */
  @Override
  public JdbcDialectSourceDefinition uploadSource(
      final JdbcDialectSourceDefinition request,
      final MultipartFile file
  ) {
    JdbcDialectSourceDefinition source = normalizeSource(request, JdbcMetadataModels.SourceType.UPLOAD);
    source.setSourceUrl(null);
    JdbcDialectSourceDefinition loaded = artifactManager.uploadDraft(source, file);
    return sourceRepository.save(loaded);
  }

  /** {@inheritDoc} */
  @Override
  public void removeSources(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    Instant deletedAt = Instant.now();
    ids.stream()
        .map(sourceRepository::findActiveById)
        .flatMap(Optional::stream)
        .forEach(source -> {
          source.setDeletedAt(deletedAt);
          sourceRepository.updateById(source);
        });
  }

  /** {@inheritDoc} */
  @Override
  public Optional<JdbcDatabaseDialect> resolveDialect(final JdbcDatabaseDialect.SupportContext context) {
    return discoverLoadedDialects().stream()
        .filter(LoadedDialect::enabled)
        .map(LoadedDialect::dialect)
        .filter(dialect -> dialect.supports(context))
        .min(Comparator.comparingInt(JdbcDatabaseDialect::order));
  }

  private List<LoadedDialect> discoverLoadedDialects() {
    List<LoadedDialect> discovered = new java.util.ArrayList<>(artifactManager.discoverClasspathDialects());
    sourceRepository.findAllActive().forEach(source -> {
      try {
        discovered.addAll(artifactManager.discoverFromJar(source));
      } catch (Exception ex) {
        // Keep discovery best-effort; the source summary still exposes the last recorded load result.
      }
    });
    Map<String, LoadedDialect> deduplicated = new LinkedHashMap<>();
    discovered.forEach(loaded -> deduplicated.putIfAbsent(descriptorKey(loaded), loaded));
    return List.copyOf(deduplicated.values());
  }

  private Map<String, List<String>> resolveBindings(final List<LoadedDialect> loadedDialects) {
    Map<String, List<String>> bindings = new LinkedHashMap<>();
    List<JdbcDatabaseDialect> dialects = loadedDialects.stream()
        .filter(LoadedDialect::enabled)
        .map(LoadedDialect::dialect)
        .sorted(Comparator.comparingInt(JdbcDatabaseDialect::order))
        .toList();
    driverRepository.findAllActive().stream()
        .forEach(driver -> {
          JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
              driver.getDatabaseType(),
              driver.getDriverClassName(),
              null,
              null,
              null,
              null,
              null,
              Map.of()
          );
          dialects.stream()
              .filter(dialect -> dialect.supports(context))
              .findFirst()
              .ifPresent(dialect -> bindings.computeIfAbsent(
                  dialect.code() + "::" + dialect.getClass().getName(),
                  ignored -> new java.util.ArrayList<>()
              ).add(driver.getCode()));
        });
    bindings.replaceAll((key, value) -> value.stream().filter(code -> code != null && !code.isBlank()).distinct().toList());
    return bindings;
  }

  private static JdbcMetadataModels.DialectDescriptor toDescriptor(
      final LoadedDialect loaded,
      final List<String> boundDriverCodes
  ) {
    JdbcDatabaseDialect dialect = loaded.dialect();
    return new JdbcMetadataModels.DialectDescriptor(
        dialect.code(),
        dialect.name(),
        dialect.description(),
        dialect.version(),
        dialect.getClass().getName(),
        loaded.sourceType(),
        loaded.sourceId(),
        loaded.sourceName(),
        loaded.sourceUrl(),
        loaded.localJarPath(),
        loaded.enabled(),
        loaded.lastLoadMessage(),
        loaded.loadedAt(),
        boundDriverCodes,
        dialect.order()
    );
  }

  private static JdbcDialectSourceDefinition normalizeSource(
      final JdbcDialectSourceDefinition request,
      final JdbcMetadataModels.SourceType sourceType
  ) {
    if (request == null) {
      throw new IllegalArgumentException("方言源不能为空");
    }
    JdbcDialectSourceDefinition source = new JdbcDialectSourceDefinition();
    source.setId(request.getId());
    source.setName(requireValue(request.getName(), "方言源名称不能为空"));
    source.setSourceType(sourceType.name());
    source.setSourceUrl(trimToNull(request.getSourceUrl()));
    source.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
    source.setDescription(trimToNull(request.getDescription()));
    source.setLocalJarPath(null);
    source.setLoadedAt(null);
    source.setLastLoadMessage(null);
    if (JdbcMetadataModels.SourceType.URL.equals(sourceType) && source.getSourceUrl() == null) {
      throw new IllegalArgumentException("方言远程地址不能为空");
    }
    return source;
  }

  private static String descriptorKey(final LoadedDialect loaded) {
    return loaded.dialect().code() + "::" + loaded.dialect().getClass().getName();
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
}
