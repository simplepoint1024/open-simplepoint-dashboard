package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.List;

/**
 * Concrete {@link DatabaseMetaData} implementation for the DNA JDBC driver.
 *
 * <p>Delegates catalog, schema, table, column, and key queries to the remote
 * DNA gateway via the owning {@link DnaJdbcConnection}'s client. All write-
 * oriented or unsupported metadata queries return safe defaults (empty strings,
 * zero limits, empty result sets, or {@code false}).
 */
final class DnaJdbcDatabaseMetaData implements DatabaseMetaData {

  private final DnaJdbcConnection connection;

  DnaJdbcDatabaseMetaData(final DnaJdbcConnection connection) {
    this.connection = connection;
  }

  // ----------------------------------------------------------------
  // Connection / driver identity
  // ----------------------------------------------------------------

  @Override
  public Connection getConnection() throws SQLException {
    return connection;
  }

  @Override
  public String getURL() throws SQLException {
    return connection.url();
  }

  @Override
  public String getUserName() throws SQLException {
    return connection.loginSubject();
  }

  @Override
  public String getDriverName() throws SQLException {
    return DnaJdbcDriver.driverName();
  }

  @Override
  public String getDriverVersion() throws SQLException {
    return DnaJdbcDriver.driverVersion();
  }

  @Override
  public int getDriverMajorVersion() {
    return 1;
  }

  @Override
  public int getDriverMinorVersion() {
    return 0;
  }

  @Override
  public String getDatabaseProductName() throws SQLException {
    return connection.databaseProductName();
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    return connection.databaseProductVersion();
  }

  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    return 0;
  }

  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    return 0;
  }

  @Override
  public int getJDBCMajorVersion() throws SQLException {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() throws SQLException {
    return 3;
  }

  // ----------------------------------------------------------------
  // Catalog / schema / identifier terminology
  // ----------------------------------------------------------------

  @Override
  public String getCatalogSeparator() throws SQLException {
    return ".";
  }

  @Override
  public String getCatalogTerm() throws SQLException {
    return "catalog";
  }

  @Override
  public String getSchemaTerm() throws SQLException {
    return "schema";
  }

  @Override
  public String getProcedureTerm() throws SQLException {
    return "";
  }

  @Override
  public String getIdentifierQuoteString() throws SQLException {
    return "\"";
  }

  @Override
  public String getSearchStringEscape() throws SQLException {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() throws SQLException {
    return "";
  }

  // ----------------------------------------------------------------
  // SQL keywords and built-in functions
  // ----------------------------------------------------------------

  @Override
  public String getSQLKeywords() throws SQLException {
    return "CATALOG,COLUMN,CROSS,CURRENT_CATALOG,CURRENT_SCHEMA,"
        + "FETCH,FIRST,FULL,GROUPING,INNER,INTERSECT,JOIN,LAST,LEFT,LIMIT,MINUS,NATURAL,"
        + "OFFSET,ON,ORDER,OUTER,PARTITION,RIGHT,ROW,ROWS,SCHEMA,TABLE,UNION,USING,VALUE,VALUES,WINDOW";
  }

  @Override
  public String getNumericFunctions() throws SQLException {
    return "ABS,ACOS,ASIN,ATAN,ATAN2,CEILING,COS,COT,DEGREES,EXP,"
        + "FLOOR,LOG,LOG10,MOD,PI,POWER,RADIANS,RAND,ROUND,SIGN,SIN,SQRT,TAN,TRUNCATE";
  }

  @Override
  public String getStringFunctions() throws SQLException {
    return "ASCII,CHAR,CHAR_LENGTH,CHARACTER_LENGTH,CONCAT,INSERT,"
        + "LCASE,LEFT,LENGTH,LOCATE,LTRIM,REPEAT,REPLACE,RIGHT,RTRIM,SPACE,SUBSTRING,UCASE,UPPER,LOWER,TRIM";
  }

  @Override
  public String getSystemFunctions() throws SQLException {
    return "CURRENT_USER,SESSION_USER,USER";
  }

  @Override
  public String getTimeDateFunctions() throws SQLException {
    return "CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,"
        + "DAYNAME,DAYOFMONTH,DAYOFWEEK,DAYOFYEAR,EXTRACT,HOUR,MINUTE,MONTH,MONTHNAME,"
        + "NOW,QUARTER,SECOND,WEEK,YEAR";
  }

  // ----------------------------------------------------------------
  // Boolean capabilities — TRUE
  // ----------------------------------------------------------------

  @Override
  public boolean allTablesAreSelectable() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsResultSetHoldability(final int holdability) throws SQLException {
    return holdability == java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsResultSetType(final int type) throws SQLException {
    return type == java.sql.ResultSet.TYPE_FORWARD_ONLY
        || type == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE;
  }

  @Override
  public boolean supportsResultSetConcurrency(final int type, final int concurrency) throws SQLException {
    return concurrency == java.sql.ResultSet.CONCUR_READ_ONLY && supportsResultSetType(type);
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    return true;
  }

  // ----------------------------------------------------------------
  // Boolean capabilities — FALSE
  // ----------------------------------------------------------------

  @Override
  public boolean isReadOnly() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSavepoints() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    return false;
  }

  @Override
  public boolean nullPlusNonNullIsNull() throws SQLException {
    return true;
  }

  @Override
  public boolean usesLocalFiles() throws SQLException {
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsColumnAliasing() throws SQLException {
    return true;
  }

  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtStart() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    return false;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    return false;
  }

  // ----------------------------------------------------------------
  // Boolean capabilities — remaining defaults (false)
  // ----------------------------------------------------------------

  @Override
  public boolean allProceduresAreCallable() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleResultSets() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsNonNullableColumns() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCoreSQLGrammar() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean isCatalogAtStart() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsPositionedDelete() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsUnion() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsUnionAll() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(final int level) throws SQLException {
    return false;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOrderByUnrelated() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsGroupBy() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsGroupByUnrelated() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsLikeEscapeClause() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsConvert() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsConvert(final int fromType, final int toType) throws SQLException {
    return false;
  }

  @Override
  public boolean ownUpdatesAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean updatesAreDetected(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean deletesAreDetected(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean insertsAreDetected(final int type) throws SQLException {
    return false;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
    return false;
  }

  @Override
  public boolean locatorsUpdateCopy() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() throws SQLException {
    return false;
  }

  // ----------------------------------------------------------------
  // Transaction / holdability defaults
  // ----------------------------------------------------------------

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    return Connection.TRANSACTION_NONE;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  // ----------------------------------------------------------------
  // SQL state type
  // ----------------------------------------------------------------

  @Override
  public int getSQLStateType() throws SQLException {
    return 0;
  }

  // ----------------------------------------------------------------
  // RowId lifetime
  // ----------------------------------------------------------------

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  // ----------------------------------------------------------------
  // Max limits
  // Non-zero values signal to tools like DataGrip that the driver
  // supports catalog/schema/table identifiers.
  // ----------------------------------------------------------------

  @Override
  public int getMaxBinaryLiteralLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() throws SQLException {
    return 128;
  }

  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxConnections() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxIndexLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    return 128;
  }

  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    return 128;
  }

  @Override
  public int getMaxRowSize() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxStatementLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxStatements() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() throws SQLException {
    return 128;
  }

  @Override
  public int getMaxTablesInSelect() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() throws SQLException {
    return 128;
  }

  @Override
  public long getMaxLogicalLobSize() throws SQLException {
    return 0L;
  }

  // ----------------------------------------------------------------
  // Catalog / schema / table metadata queries
  // ----------------------------------------------------------------

  @Override
  public ResultSet getCatalogs() throws SQLException {
    return ResultSetBuilder.fromTabularResult(connection.client().catalogs());
  }

  @Override
  public ResultSet getSchemas() throws SQLException {
    return handleGetSchemas(null, null);
  }

  @Override
  public ResultSet getSchemas(final String catalog, final String schemaPattern) throws SQLException {
    return handleGetSchemas(catalog, schemaPattern);
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    return ResultSetBuilder.fromTabularResult(connection.client().tableTypes());
  }

  @Override
  public ResultSet getTables(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern,
      final String[] types
  ) throws SQLException {
    List<String> typeList = types != null ? List.of(types) : null;
    return ResultSetBuilder.fromTabularResult(
        connection.client().tables(trimToNull(catalog), trimToNull(schemaPattern), tableNamePattern, typeList)
    );
  }

  @Override
  public ResultSet getColumns(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern,
      final String columnNamePattern
  ) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().columns(trimToNull(catalog), trimToNull(schemaPattern), tableNamePattern, columnNamePattern)
    );
  }

  @Override
  public ResultSet getPrimaryKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().primaryKeys(trimToNull(catalog), trimToNull(schema), table)
    );
  }

  @Override
  public ResultSet getIndexInfo(
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().indexInfo(trimToNull(catalog), trimToNull(schema), table, unique, approximate)
    );
  }

  @Override
  public ResultSet getImportedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().importedKeys(trimToNull(catalog), trimToNull(schema), table)
    );
  }

  @Override
  public ResultSet getExportedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().exportedKeys(trimToNull(catalog), trimToNull(schema), table)
    );
  }

  @Override
  public ResultSet getTypeInfo() throws SQLException {
    return ResultSetBuilder.fromTabularResult(connection.client().typeInfo());
  }

  // ----------------------------------------------------------------
  // Cross-reference / key queries — empty result sets
  // ----------------------------------------------------------------

  @Override
  public ResultSet getCrossReference(
      final String parentCatalog,
      final String parentSchema,
      final String parentTable,
      final String foreignCatalog,
      final String foreignSchema,
      final String foreignTable
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  // ----------------------------------------------------------------
  // Procedure / function / UDT queries — empty result sets
  // ----------------------------------------------------------------

  @Override
  public ResultSet getProcedures(
      final String catalog,
      final String schemaPattern,
      final String procedureNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getProcedureColumns(
      final String catalog,
      final String schemaPattern,
      final String procedureNamePattern,
      final String columnNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getFunctions(
      final String catalog,
      final String schemaPattern,
      final String functionNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getFunctionColumns(
      final String catalog,
      final String schemaPattern,
      final String functionNamePattern,
      final String columnNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getUDTs(
      final String catalog,
      final String schemaPattern,
      final String typeNamePattern,
      final int[] types
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getSuperTypes(
      final String catalog,
      final String schemaPattern,
      final String typeNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getSuperTables(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getAttributes(
      final String catalog,
      final String schemaPattern,
      final String typeNamePattern,
      final String attributeNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  // ----------------------------------------------------------------
  // Column privilege / table privilege / best row / version queries
  // ----------------------------------------------------------------

  @Override
  public ResultSet getColumnPrivileges(
      final String catalog,
      final String schema,
      final String table,
      final String columnNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getTablePrivileges(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getBestRowIdentifier(
      final String catalog,
      final String schema,
      final String table,
      final int scope,
      final boolean nullable
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getVersionColumns(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  // ----------------------------------------------------------------
  // Pseudo-column / client info property queries
  // ----------------------------------------------------------------

  @Override
  public ResultSet getPseudoColumns(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern,
      final String columnNamePattern
  ) throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
    return ResultSetBuilder.emptyResultSet();
  }

  // ----------------------------------------------------------------
  // Wrapper support
  // ----------------------------------------------------------------

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unwrap(final Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return (T) this;
    }
    throw new SQLException("不支持 unwrap 到 " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(final Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  // ----------------------------------------------------------------
  // Object overrides
  // ----------------------------------------------------------------

  @Override
  public String toString() {
    return "DnaJdbcDatabaseMetaData";
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(final Object obj) {
    return this == obj;
  }

  // ----------------------------------------------------------------
  // Private helpers
  // ----------------------------------------------------------------

  private ResultSet handleGetSchemas(final String catalog, final String schemaPattern) throws SQLException {
    return ResultSetBuilder.fromTabularResult(
        connection.client().schemas(trimToNull(catalog), trimToNull(schemaPattern))
    );
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
