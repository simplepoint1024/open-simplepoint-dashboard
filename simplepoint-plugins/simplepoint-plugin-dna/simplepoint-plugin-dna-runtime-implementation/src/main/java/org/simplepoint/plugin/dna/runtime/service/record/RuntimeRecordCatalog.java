package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.adapter.jdbc.JdbcTable;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

/** One trusted catalog contract for business and infrastructure SQL, preserving UTC and microseconds. */
final class RuntimeRecordCatalog implements CalciteSchemaConfigurer {
  private final DataSource dataSource;
  private final RuntimeSchemaDialect dialect;
  private final String catalogName;
  private final String schemaName;
  private final Set<String> tables;

  RuntimeRecordCatalog(final DataSource dataSource, final RuntimeSchemaDialect dialect,
      final String catalogName, final String schemaName, final Set<String> tables) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.catalogName = catalogName;
    this.schemaName = schemaName;
    this.tables = tables.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void configure(final SchemaPlus root) {
    var jdbc = JdbcSchema.create(root, "runtime", dataSource, dialect.recordSqlDialectFactory(), catalogName, schemaName);
    var catalog = root.add("runtime", jdbc);
    for (String name : jdbc.getTableNames()) {
      if (tables.contains(name.toLowerCase(Locale.ROOT)) && jdbc.getTable(name) instanceof JdbcTable table) {
        catalog.add(name, new RuntimeRecordJdbcTable(table));
      }
    }
  }

  @Override
  public Class<? extends RelDataTypeSystem> typeSystem() {
    return RuntimeRecordTypeSystem.class;
  }

  @Override
  public ZoneId timeZone() {
    return ZoneId.of("UTC");
  }
}
