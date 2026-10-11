package org.simplepoint.plugin.dna.runtime.service.schema;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.stereotype.Component;

/** Resolves one trusted model target into a managed or catalog-remapped datasource handle. */
@Component
public class ManagedRuntimeDataSourceProvider {

  private final JdbcDataSourceDefinitionService dataSourceService;
  private final JdbcDriverDefinitionService driverService;
  private final JdbcDialectManagementService coreDialectService;
  private final RuntimeSchemaDialectResolver runtimeDialectResolver;

  /** Creates the provider from platform managed datasource services. */
  public ManagedRuntimeDataSourceProvider(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionService driverService,
      final JdbcDialectManagementService coreDialectService,
      final RuntimeSchemaDialectResolver runtimeDialectResolver
  ) {
    this.dataSourceService = dataSourceService;
    this.driverService = driverService;
    this.coreDialectService = coreDialectService;
    this.runtimeDialectResolver = runtimeDialectResolver;
  }

  /** Opens a datasource handle and validates both Core and Runtime dialect identities. */
  public ManagedRuntimeDataSource open(final PhysicalSchemaTarget target) {
    String dataSourceId = target.metadataTarget().dataSourceId();
    JdbcDataSourceDefinition definition = dataSourceService.findActiveById(dataSourceId)
        .orElseThrow(() -> unavailable("Managed datasource was not found"));
    JdbcDriverDefinition driver = driverService.findActiveById(definition.getDriverId())
        .orElseThrow(() -> unavailable("Managed JDBC driver was not found"));
    SimpleDataSource managed = dataSourceService.requireSimpleDataSource(dataSourceId);
    try (Connection connection = managed.getConnection()) {
      ConnectionIdentity identity = identity(connection, driver, definition);
      String targetCatalog = normalize(target.metadataTarget().catalogName());
      if (targetCatalog == null
          || !identity.coreDialect().requiresCatalogConnection(
          targetCatalog,
          identity.supportContext()
      )) {
        return handle(managed, false, identity, target);
      }
      String jdbcUrl = identity.coreDialect().remapJdbcUrlCatalog(
          definition.getJdbcUrl(),
          targetCatalog,
          identity.supportContext()
      );
      SimpleDataSource transientDataSource = dataSourceService.createTransientSimpleDataSource(
          dataSourceId,
          jdbcUrl
      );
      try (Connection targetConnection = transientDataSource.getConnection()) {
        return handle(
            transientDataSource,
            true,
            identity(targetConnection, driver, definition),
            target
        );
      } catch (RuntimeException | SQLException exception) {
        transientDataSource.close();
        throw exception;
      }
    } catch (DnaRuntimeException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DATA_SOURCE_UNAVAILABLE,
          "Managed datasource resolution failed",
          exception
      );
    }
  }

  private ManagedRuntimeDataSource handle(
      final SimpleDataSource dataSource,
      final boolean owned,
      final ConnectionIdentity identity,
      final PhysicalSchemaTarget target
  ) {
    JdbcDatabaseDialect dialect = identity.coreDialect();
    return new ManagedRuntimeDataSource(
        dataSource,
        owned,
        identity.supportContext(),
        dialect,
        identity.runtimeDialect(),
        identity.database(),
        dialect.metadataCatalog(
            normalize(target.metadataTarget().catalogName()),
            identity.supportContext()
        ),
        dialect.metadataSchema(
            normalize(target.metadataTarget().schemaName()),
            identity.supportContext()
        )
    );
  }

  private ConnectionIdentity identity(
      final Connection connection,
      final JdbcDriverDefinition driver,
      final JdbcDataSourceDefinition dataSource
  ) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    JdbcDatabaseDialect.SupportContext supportContext = new JdbcDatabaseDialect.SupportContext(
        driver.getDatabaseType(),
        driver.getDriverClassName(),
        metadata.getDatabaseProductName(),
        metadata.getDatabaseProductVersion(),
        normalize(connection.getCatalog()),
        normalize(connection.getSchema()),
        normalize(metadata.getIdentifierQuoteString()),
        parseProperties(dataSource.getConnectionProperties())
    );
    JdbcDatabaseDialect coreDialect = coreDialectService.resolveDialect(supportContext)
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
            "The managed datasource has no core JDBC dialect"
        ));
    DatabaseIdentity database = new DatabaseIdentity(
        metadata.getDatabaseProductName(),
        metadata.getDatabaseProductVersion(),
        metadata.getDatabaseMajorVersion(),
        metadata.getDatabaseMinorVersion()
    );
    RuntimeSchemaDialect runtimeDialect = runtimeDialectResolver.resolve(database);
    if (!runtimeDialect.dialectId().equals(coreDialect.code())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
          "Core and Runtime database dialects do not agree"
      );
    }
    return new ConnectionIdentity(
        supportContext,
        coreDialect,
        runtimeDialect,
        database
    );
  }

  private static Map<String, String> parseProperties(final String rawProperties) {
    String normalized = normalize(rawProperties);
    if (normalized == null) {
      return Map.of();
    }
    java.util.Properties properties = new java.util.Properties();
    try (StringReader reader = new StringReader(normalized)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw unavailable("Managed datasource properties are invalid", exception);
    }
    Map<String, String> result = new LinkedHashMap<>();
    properties.stringPropertyNames().stream().sorted()
        .forEach(name -> result.put(name, properties.getProperty(name)));
    return Map.copyOf(result);
  }

  private static DnaRuntimeException unavailable(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.DATA_SOURCE_UNAVAILABLE, message);
  }

  private static DnaRuntimeException unavailable(
      final String message,
      final Throwable cause
  ) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.DATA_SOURCE_UNAVAILABLE, message, cause);
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record ConnectionIdentity(
      JdbcDatabaseDialect.SupportContext supportContext,
      JdbcDatabaseDialect coreDialect,
      RuntimeSchemaDialect runtimeDialect,
      DatabaseIdentity database
  ) {
  }

  /** Managed datasource plus normalized dialect and metadata namespace. */
  public record ManagedRuntimeDataSource(
      SimpleDataSource dataSource,
      boolean owned,
      JdbcDatabaseDialect.SupportContext supportContext,
      JdbcDatabaseDialect coreDialect,
      RuntimeSchemaDialect runtimeDialect,
      DatabaseIdentity database,
      String metadataCatalog,
      String metadataSchema
  ) implements AutoCloseable {

    @Override
    public void close() {
      if (owned) {
        dataSource.close();
      }
    }
  }
}
