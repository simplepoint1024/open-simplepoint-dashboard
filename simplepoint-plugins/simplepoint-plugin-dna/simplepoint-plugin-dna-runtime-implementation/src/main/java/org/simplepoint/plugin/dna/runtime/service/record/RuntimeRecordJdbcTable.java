package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collection;
import java.util.List;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.adapter.jdbc.JdbcTable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.sql.type.SqlTypeName;

/** Retains the managed TIME(6)/TIMESTAMP(6) contract that JdbcSchema's temporary default type factory truncates. */
final class RuntimeRecordJdbcTable extends AbstractQueryableTable implements TranslatableTable, ModifiableTable {

  private final JdbcTable delegate;

  RuntimeRecordJdbcTable(final JdbcTable delegate) {
    super(delegate.getElementType());
    this.delegate = delegate;
  }

  @Override
  public RelDataType getRowType(final RelDataTypeFactory factory) {
    var builder = factory.builder();
    for (var field : delegate.getRowType(factory).getFieldList()) {
      RelDataType type = field.getType();
      SqlTypeName name = type.getSqlTypeName();
      if (name == SqlTypeName.TIME || name == SqlTypeName.TIMESTAMP || name == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE) {
        type = factory.createTypeWithNullability(factory.createSqlType(name, 6), type.isNullable());
      }
      if (name == SqlTypeName.ANY) {
        type = factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.ANY), type.isNullable());
      }
      builder.add(field.getName(), type);
    }
    return builder.build();
  }

  @Override
  public RelNode toRel(final RelOptTable.ToRelContext context, final RelOptTable table) {
    return delegate.toRel(context, table);
  }

  @Override
  public TableModify toModificationRel(final RelOptCluster cluster, final RelOptTable table,
      final Prepare.CatalogReader reader, final RelNode input, final TableModify.Operation operation,
      final List<String> columns, final List<RexNode> expressions, final boolean flattened) {
    return delegate.toModificationRel(cluster, table, reader, input, operation, columns, expressions, flattened);
  }

  @Override
  public Collection<?> getModifiableCollection() {
    return delegate.getModifiableCollection();
  }

  @Override
  public <T> Queryable<T> asQueryable(final QueryProvider provider, final SchemaPlus schema, final String table) {
    return delegate.asQueryable(provider, schema, table);
  }

  @Override
  public Expression getExpression(final SchemaPlus schema, final String table, final Class clazz) {
    return delegate.getExpression(schema, table, clazz);
  }

  @Override
  public <T> T unwrap(final Class<T> type) {
    return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
  }
}
