package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;

@ExtendWith(MockitoExtension.class)
class FederationJdbcMetadataSupportTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private JdbcDriverDefinitionRepository driverRepository;

  @Mock
  private JdbcDialectManagementService dialectManagementService;

  @Test
  void tablesShouldExposeBaseTablesAsStandardTableMetadata() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, "create table orders (id int primary key);");

    JdbcDataSourceDefinition definition = createDefinition(dataSource);
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    FederationJdbcDriverModels.TabularResult result = support.tables(
        definition,
        "ds1",
        "PUBLIC",
        "%",
        List.of("TABLE")
    );

    assertThat(columnValues(result, "TABLE_NAME")).containsExactly("ORDERS");
    assertThat(columnValues(result, "TABLE_TYPE")).containsExactly("TABLE");
  }

  @Test
  void tablesShouldLoadTablesWithUnderscoresInName() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource,
        "create table user_orders (id int primary key);"
            + "create table user_profiles (id int primary key);"
            + "create table simple (id int primary key);");

    JdbcDataSourceDefinition definition = createDefinition(dataSource);
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    FederationJdbcDriverModels.TabularResult result = support.tables(
        definition,
        "ds1",
        "PUBLIC",
        "%",
        List.of("TABLE")
    );

    assertThat(columnValues(result, "TABLE_NAME"))
        .containsExactlyInAnyOrder("SIMPLE", "USER_ORDERS", "USER_PROFILES");
  }

  @Test
  void catalogMatchingShouldUseLiteralExactMatch() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, "create table orders (id int primary key);");

    JdbcDataSourceDefinition definition = createDefinitionWithCode(dataSource, "my_ds");
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    // Exact catalog match with underscore should return results
    FederationJdbcDriverModels.TabularResult matched = support.tables(
        definition,
        "my_ds",
        "PUBLIC",
        "%",
        List.of("TABLE")
    );
    assertThat(columnValues(matched, "TABLE_NAME")).containsExactly("ORDERS");
    assertThat(columnValues(matched, "TABLE_CAT")).containsExactly("my_ds");

    // Different catalog should return empty
    FederationJdbcDriverModels.TabularResult noMatch = support.tables(
        definition,
        "myXds",
        "PUBLIC",
        "%",
        List.of("TABLE")
    );
    assertThat(noMatch.rows()).isEmpty();
  }

  @Test
  void columnsShouldLoadForTablesWithUnderscores() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, "create table user_orders (order_id int primary key, order_name varchar(100));");

    JdbcDataSourceDefinition definition = createDefinition(dataSource);
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    FederationJdbcDriverModels.TabularResult result = support.columns(
        definition,
        "ds1",
        "PUBLIC",
        "USER_ORDERS",
        "%"
    );

    assertThat(columnValues(result, "COLUMN_NAME"))
        .containsExactlyInAnyOrder("ORDER_ID", "ORDER_NAME");
  }

  @Test
  void schemasShouldLoadWithUnderscoreCatalogCode() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, "create table orders (id int primary key);");

    JdbcDataSourceDefinition definition = createDefinitionWithCode(dataSource, "test_db");
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    FederationJdbcDriverModels.TabularResult result = support.schemas(
        definition,
        "test_db",
        "%"
    );

    assertThat(columnValues(result, "TABLE_SCHEM")).contains("PUBLIC");
    assertThat(columnValues(result, "TABLE_CATALOG"))
        .allMatch(catalog -> "test_db".equals(catalog));
  }

  @Test
  void tableTypesShouldNormalizeBaseTableToTable() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, "create table orders (id int primary key);");

    JdbcDataSourceDefinition definition = createDefinition(dataSource);
    JdbcDriverDefinition driver = createDriver();
    JdbcDatabaseDialect dialect = h2LikeDialect();
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationJdbcMetadataSupport support = new FederationJdbcMetadataSupport(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );

    FederationJdbcDriverModels.TabularResult result = support.tableTypes(definition);

    assertThat(columnValues(result, "TABLE_TYPE")).contains("TABLE", "VIEW");
    assertThat(columnValues(result, "TABLE_TYPE")).doesNotContain("BASE TABLE");
  }

  private static JdbcDataSource createDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:federation-metadata-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static void initialize(final JdbcDataSource dataSource, final String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      for (String fragment : sql.split(";")) {
        String normalized = fragment.trim();
        if (!normalized.isEmpty()) {
          statement.execute(normalized);
        }
      }
    }
  }

  private static JdbcDataSourceDefinition createDefinition(final JdbcDataSource dataSource) {
    return createDefinitionWithCode(dataSource, "ds1");
  }

  private static JdbcDataSourceDefinition createDefinitionWithCode(
      final JdbcDataSource dataSource,
      final String code
  ) {
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setCode(code);
    definition.setDriverId("driver-1");
    definition.setJdbcUrl(dataSource.getURL());
    return definition;
  }

  private static JdbcDriverDefinition createDriver() {
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    return driver;
  }

  private static JdbcDatabaseDialect h2LikeDialect() throws SQLException {
    JdbcDatabaseDialect dialect = mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    lenient().when(dialect.metadataCatalog(any(), any())).thenReturn(null);
    lenient().when(dialect.visibleCatalogs(any(), any())).thenReturn(List.of());
    lenient().when(dialect.visibleSchemas(any(), any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
        .stream()
        .filter(schema -> schema != null && !"INFORMATION_SCHEMA".equalsIgnoreCase(schema))
        .toList());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      return new JdbcDatabaseDialect.MetadataNamespaceSupport(
          metaData.supportsCatalogsInTableDefinitions(),
          metaData.supportsSchemasInTableDefinitions()
      );
    }).when(dialect).resolveMetadataNamespaceSupport(any(), any(), any());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      String catalogPattern = invocation.getArgument(3);
      try (ResultSet resultSet = metaData.getCatalogs()) {
        JdbcDatabaseDialect.MetadataResult metadata = serialize(resultSet);
        List<String> visibleCatalogs = dialect.visibleCatalogs(columnValues(metadata, "TABLE_CAT"), invocation.getArgument(2));
        return filterRows(metadata, "TABLE_CAT", visibleCatalogs, catalogPattern);
      }
    }).when(dialect).loadCatalogs(any(), any(), any(), any());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      String catalogPattern = invocation.getArgument(3);
      String schemaPattern = invocation.getArgument(4);
      try (ResultSet resultSet = metaData.getSchemas(catalogPattern, schemaPattern)) {
        JdbcDatabaseDialect.MetadataResult metadata = serialize(resultSet);
        List<String> visibleSchemas = dialect.visibleSchemas(columnValues(metadata, "TABLE_SCHEM"), catalogPattern, invocation.getArgument(2));
        return filterRows(metadata, "TABLE_SCHEM", visibleSchemas, schemaPattern);
      }
    }).when(dialect).loadSchemas(any(), any(), any(), any(), any());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      try (ResultSet resultSet = metaData.getTableTypes()) {
        return normalizeTableTypes(serialize(resultSet));
      }
    }).when(dialect).loadTableTypes(any(), any(), any());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      try (ResultSet resultSet = metaData.getTables(
          invocation.getArgument(3),
          invocation.getArgument(4),
          invocation.getArgument(5),
          expandTableTypes(invocation.getArgument(6))
      )) {
        return normalizeTableTypes(serialize(resultSet));
      }
    }).when(dialect).loadTables(any(), any(), any(), any(), any(), any(), any());
    lenient().doAnswer(invocation -> {
      DatabaseMetaData metaData = invocation.getArgument(1);
      try (ResultSet resultSet = metaData.getColumns(
          invocation.getArgument(3),
          invocation.getArgument(4),
          invocation.getArgument(5),
          invocation.getArgument(6)
      )) {
        return serialize(resultSet);
      }
    }).when(dialect).loadColumns(any(), any(), any(), any(), any(), any(), any());
    return dialect;
  }

  private static JdbcDatabaseDialect.MetadataResult serialize(final ResultSet resultSet) throws SQLException {
    try (ResultSet rows = resultSet) {
      ResultSetMetaData metaData = rows.getMetaData();
      List<JdbcDatabaseDialect.MetadataColumn> columns = new ArrayList<>(metaData.getColumnCount());
      for (int index = 1; index <= metaData.getColumnCount(); index++) {
        columns.add(new JdbcDatabaseDialect.MetadataColumn(
            metaData.getColumnLabel(index),
            metaData.getColumnTypeName(index),
            metaData.getColumnType(index)
        ));
      }
      List<List<Object>> data = new ArrayList<>();
      while (rows.next()) {
        List<Object> row = new ArrayList<>(metaData.getColumnCount());
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
          row.add(rows.getObject(index));
        }
        data.add(row);
      }
      return new JdbcDatabaseDialect.MetadataResult(columns, data);
    }
  }

  private static JdbcDatabaseDialect.MetadataResult filterRows(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final String columnName,
      final List<String> allowedValues,
      final String pattern
  ) {
    if ((allowedValues == null || allowedValues.isEmpty()) && (pattern == null || pattern.isBlank())) {
      return metadata;
    }
    int columnIndex = findColumnIndex(metadata, columnName);
    if (columnIndex < 0) {
      return metadata;
    }
    List<String> allowed = allowedValues == null ? List.of() : allowedValues;
    List<List<Object>> rows = metadata.rows().stream()
        .filter(row -> {
          String value = columnIndex < row.size() ? trimToNull(row.get(columnIndex) == null ? null : row.get(columnIndex).toString()) : null;
          return (allowed.isEmpty() || allowed.stream().anyMatch(allowedValue -> allowedValue.equalsIgnoreCase(value)))
              && matchesPattern(value, pattern);
        })
        .toList();
    return new JdbcDatabaseDialect.MetadataResult(metadata.columns(), rows);
  }

  private static JdbcDatabaseDialect.MetadataResult normalizeTableTypes(final JdbcDatabaseDialect.MetadataResult metadata) {
    int tableTypeColumnIndex = findColumnIndex(metadata, "TABLE_TYPE");
    if (tableTypeColumnIndex < 0) {
      return metadata;
    }
    List<List<Object>> rows = metadata.rows().stream()
        .map(row -> {
          List<Object> copy = new ArrayList<>(row);
          if (tableTypeColumnIndex < copy.size()) {
            copy.set(tableTypeColumnIndex, normalizeTableType(copy.get(tableTypeColumnIndex)));
          }
          return copy;
        })
        .distinct()
        .toList();
    return new JdbcDatabaseDialect.MetadataResult(metadata.columns(), rows);
  }

  private static String[] expandTableTypes(final List<String> types) {
    if (types == null || types.isEmpty()) {
      return null;
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (String type : types) {
      String normalized = trimToNull(type);
      if (normalized == null) {
        continue;
      }
      values.add(normalized);
      if ("TABLE".equalsIgnoreCase(normalized)) {
        values.add("BASE TABLE");
      }
    }
    return values.toArray(String[]::new);
  }

  private static String normalizeTableType(final Object value) {
    String normalized = trimToNull(value == null ? null : value.toString());
    return "BASE TABLE".equalsIgnoreCase(normalized) ? "TABLE" : normalized;
  }

  private static List<String> columnValues(
      final FederationJdbcDriverModels.TabularResult result,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(result, columnName);
    return result.rows().stream()
        .map(row -> String.valueOf(row.get(columnIndex)))
        .toList();
  }

  private static List<String> columnValues(
      final JdbcDatabaseDialect.MetadataResult result,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(result, columnName);
    return result.rows().stream()
        .map(row -> columnIndex < row.size() ? trimToNull(row.get(columnIndex) == null ? null : row.get(columnIndex).toString()) : null)
        .filter(value -> value != null && !value.isBlank())
        .toList();
  }

  private static int findColumnIndex(
      final FederationJdbcDriverModels.TabularResult result,
      final String columnName
  ) {
    for (int index = 0; index < result.columns().size(); index++) {
      FederationJdbcDriverModels.JdbcColumn column = result.columns().get(index);
      if (column != null && columnName.equalsIgnoreCase(column.name())) {
        return index;
      }
    }
    throw new IllegalArgumentException("Missing column: " + columnName);
  }

  private static int findColumnIndex(
      final JdbcDatabaseDialect.MetadataResult result,
      final String columnName
  ) {
    for (int index = 0; index < result.columns().size(); index++) {
      JdbcDatabaseDialect.MetadataColumn column = result.columns().get(index);
      if (column != null && columnName.equalsIgnoreCase(column.name())) {
        return index;
      }
    }
    throw new IllegalArgumentException("Missing column: " + columnName);
  }

  private static boolean matchesPattern(final String value, final String pattern) {
    String normalizedPattern = trimToNull(pattern);
    if (normalizedPattern == null) {
      return true;
    }
    String normalizedValue = trimToNull(value);
    if (normalizedValue == null) {
      return false;
    }
    StringBuilder builder = new StringBuilder("^");
    for (int index = 0; index < normalizedPattern.length(); index++) {
      char current = normalizedPattern.charAt(index);
      if (current == '%') {
        builder.append(".*");
      } else if (current == '_') {
        builder.append('.');
      } else if ("\\.[]{}()*+-?^$|".indexOf(current) >= 0) {
        builder.append('\\').append(current);
      } else {
        builder.append(current);
      }
    }
    builder.append('$');
    return normalizedValue.toLowerCase().matches(builder.toString().toLowerCase());
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
