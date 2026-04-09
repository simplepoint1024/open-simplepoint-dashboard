package org.simplepoint.plugin.dna.core.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service contract for JDBC dialect discovery, source management, and automatic driver binding.
 */
public interface JdbcDialectManagementService {

  /**
   * Returns all loaded dialect descriptors, including classpath and external sources.
   *
   * @return loaded dialect descriptors
   */
  List<JdbcMetadataModels.DialectDescriptor> listLoadedDialects();

  /**
   * Returns all externally managed dialect sources.
   *
   * @return source summaries
   */
  List<JdbcMetadataModels.DialectSourceSummary> listSources();

  /**
   * Creates a URL-based dialect source and loads it immediately.
   *
   * @param request source request
   * @return persisted source
   */
  JdbcDialectSourceDefinition createUrlSource(JdbcDialectSourceDefinition request);

  /**
   * Creates an upload-based dialect source and loads it immediately.
   *
   * @param request source request
   * @param file uploaded dialect jar
   * @return persisted source
   */
  JdbcDialectSourceDefinition uploadSource(JdbcDialectSourceDefinition request, MultipartFile file);

  /**
   * Removes external dialect sources.
   *
   * @param ids source ids
   */
  void removeSources(Collection<String> ids);

  /**
   * Resolves the best-matching dialect for the supplied runtime context.
   *
   * @param context support context
   * @return resolved dialect
   */
  Optional<JdbcDatabaseDialect> resolveDialect(JdbcDatabaseDialect.SupportContext context);
}
