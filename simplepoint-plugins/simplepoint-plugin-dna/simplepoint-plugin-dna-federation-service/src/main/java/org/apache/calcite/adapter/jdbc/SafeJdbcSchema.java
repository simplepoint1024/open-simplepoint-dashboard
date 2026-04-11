package org.apache.calcite.adapter.jdbc;

import static java.util.Objects.requireNonNull;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.calcite.avatica.SqlType;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link JdbcSchema} subclass that gracefully handles databases reporting
 * {@code precision = 0} for {@code DECIMAL}/{@code NUMERIC} columns
 * (e.g. Oracle's bare {@code NUMBER} type).
 *
 * <p>The standard Calcite code calls
 * {@code typeFactory.createSqlType(DECIMAL, 0, 0)} which is rejected because
 * precision must be &ge; 1. This class intercepts that case and falls back to
 * a no-precision type (effectively {@code DECIMAL} with the system default
 * maximum precision), which matches the database semantics of
 * &ldquo;any precision&rdquo;.
 */
public final class SafeJdbcSchema extends JdbcSchema {

  private SafeJdbcSchema(
      DataSource dataSource,
      SqlDialect dialect,
      JdbcConvention convention,
      @Nullable String catalog,
      @Nullable String schema
  ) {
    super(dataSource, dialect, convention, catalog, schema);
  }

  /**
   * Creates a SafeJdbcSchema – drop-in replacement for
   * {@link JdbcSchema#create(SchemaPlus, String, DataSource, String, String)}.
   */
  public static SafeJdbcSchema create(
      SchemaPlus parentSchema,
      String name,
      DataSource dataSource,
      @Nullable String catalog,
      @Nullable String schema
  ) {
    return create(parentSchema, name, dataSource,
        SqlDialectFactoryImpl.INSTANCE, catalog, schema);
  }

  /**
   * Creates a SafeJdbcSchema with explicit dialect factory.
   */
  public static SafeJdbcSchema create(
      SchemaPlus parentSchema,
      String name,
      DataSource dataSource,
      SqlDialectFactory dialectFactory,
      @Nullable String catalog,
      @Nullable String schema
  ) {
    final Expression expression =
        Schemas.subSchemaExpression(parentSchema, name, JdbcSchema.class);
    final SqlDialect dialect = JdbcSchema.createDialect(dialectFactory, dataSource);
    final JdbcConvention convention =
        JdbcConvention.of(dialect, expression, name);
    return new SafeJdbcSchema(dataSource, dialect, convention, catalog, schema);
  }

  // ------------------------------------------------------------------ //
  // Override the package-private method that reads column metadata.
  // ------------------------------------------------------------------ //

  @Override
  RelProtoDataType getRelDataType(
      DatabaseMetaData metaData,
      String catalogName,
      String schemaName,
      String tableName
  ) throws SQLException {
    final ResultSet resultSet =
        metaData.getColumns(catalogName, schemaName, tableName, null);
    final RelDataTypeFactory typeFactory =
        new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataTypeFactory.Builder fieldInfo = typeFactory.builder();

    while (resultSet.next()) {
      final String columnName =
          requireNonNull(resultSet.getString(4), "columnName");
      final int dataType = resultSet.getInt(5);
      final String typeString = resultSet.getString(6);
      final int precision;
      final int scale;

      switch (SqlType.valueOf(dataType)) {
        case TIMESTAMP:
        case TIME:
          precision = resultSet.getInt(9); // SCALE
          scale = 0;
          break;
        default:
          precision = resultSet.getInt(7); // SIZE
          scale = resultSet.getInt(9);     // SCALE
          break;
      }

      RelDataType sqlType =
          safeSqlType(typeFactory, dataType, precision, scale, typeString);
      boolean nullable =
          resultSet.getInt(11) != DatabaseMetaData.columnNoNulls;
      fieldInfo.add(columnName, sqlType).nullable(nullable);
    }
    resultSet.close();
    return RelDataTypeImpl.proto(fieldInfo.build());
  }

  // ------------------------------------------------------------------ //
  // Safe version of JdbcSchema.sqlType that handles precision = 0.
  // ------------------------------------------------------------------ //

  private static RelDataType safeSqlType(
      RelDataTypeFactory typeFactory,
      int dataType,
      int precision,
      int scale,
      @Nullable String typeString
  ) {
    final SqlTypeName sqlTypeName =
        Util.first(SqlTypeName.getNameForJdbcType(dataType), SqlTypeName.ANY);

    // Handle ARRAY the same way as upstream.
    if (sqlTypeName == SqlTypeName.ARRAY) {
      RelDataType component = null;
      if (typeString != null && typeString.endsWith(" ARRAY")) {
        final String remaining =
            typeString.substring(0, typeString.length() - " ARRAY".length());
        component = parseTypeString(typeFactory, remaining);
      }
      if (component == null) {
        component = typeFactory.createTypeWithNullability(
            typeFactory.createSqlType(SqlTypeName.ANY), true);
      }
      return typeFactory.createArrayType(component, -1);
    }

    // ---- FIX: clamp precision to at least 1 for DECIMAL/NUMERIC ----
    // Oracle's bare NUMBER (and similar types in other databases) report
    // precision = 0 via JDBC metadata. Calcite rejects precision < 1.
    // When precision is 0 we treat it as "unspecified" and fall back to
    // DOUBLE (which most closely models Oracle's NUMBER semantics), or
    // to the system-default max-precision DECIMAL if scale is present.
    if (precision == 0 && sqlTypeName == SqlTypeName.DECIMAL) {
      if (scale != 0) {
        // Has a scale but no precision — use max system precision.
        int maxPrecision =
            typeFactory.getTypeSystem().getMaxNumericPrecision();
        return typeFactory.createSqlType(sqlTypeName, maxPrecision, scale);
      }
      // Neither precision nor scale — treat as DOUBLE.
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }

    // Standard Calcite logic.
    if (precision >= 0
        && scale >= 0
        && sqlTypeName.allowsPrecScale(true, true)) {
      return typeFactory.createSqlType(sqlTypeName, precision, scale);
    } else if (precision >= 0 && sqlTypeName.allowsPrecNoScale()) {
      return typeFactory.createSqlType(sqlTypeName, precision);
    } else {
      assert sqlTypeName.allowsNoPrecNoScale();
      return typeFactory.createSqlType(sqlTypeName);
    }
  }

  /**
   * Parses a type string such as "INTEGER" or "VARCHAR(10)" into a RelDataType.
   * Copied from {@code JdbcSchema.parseTypeString} which is private.
   */
  @SuppressWarnings("StringSplitter")
  private static @Nullable RelDataType parseTypeString(
      RelDataTypeFactory typeFactory,
      String typeString
  ) {
    int precision = -1;
    int scale = -1;
    int open = typeString.indexOf("(");
    if (open >= 0) {
      int close = typeString.indexOf(")", open);
      if (close >= 0) {
        String rest = typeString.substring(open + 1, close);
        typeString = typeString.substring(0, open);
        int comma = rest.indexOf(",");
        if (comma >= 0) {
          precision = Integer.parseInt(rest.substring(0, comma).trim());
          scale = Integer.parseInt(rest.substring(comma + 1).trim());
        } else {
          precision = Integer.parseInt(rest.trim());
        }
      }
    }
    try {
      final SqlTypeName typeName = SqlTypeName.valueOf(typeString);
      return typeName.allowsPrecScale(true, true)
          ? typeFactory.createSqlType(typeName, precision, scale)
          : typeName.allowsPrecScale(true, false)
          ? typeFactory.createSqlType(typeName, precision)
          : typeFactory.createSqlType(typeName);
    } catch (IllegalArgumentException e) {
      return typeFactory.createTypeWithNullability(
          typeFactory.createSqlType(SqlTypeName.ANY), true);
    }
  }
}
