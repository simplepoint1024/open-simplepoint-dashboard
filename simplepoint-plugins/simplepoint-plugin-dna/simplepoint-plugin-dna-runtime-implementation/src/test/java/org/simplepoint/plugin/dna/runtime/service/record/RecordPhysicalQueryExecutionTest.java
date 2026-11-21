package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;

class RecordPhysicalQueryExecutionTest {
  @Test
  void unsupportedInMemoryWorkFailsBeforeReadingAndDoesNotLeakPlannerRestrictions() {
    var table = new MemoryRows();
    var engine = new DefaultCalciteQueryEngine();
    Supplier<CalciteQueryResult> query =
        () ->
            engine.execute(
                new CalciteQueryRequest("SELECT COUNT(*) FROM \"records\"", null, 1, 30_000),
                root -> root.add("records", table));
    assertThatThrownBy(() -> RecordPhysicalQueryExecution.execute(query))
        .isInstanceOf(RuntimeException.class);
    assertThat(table.scans).hasValue(0);
    assertThat(RecordQueryServiceImpl.countValue(query.get())).isEqualTo(2);
    assertThat(table.scans.get()).isPositive();
  }

  @Test
  void constantFalsePlansAvoidTableReadsAndLocalTableAggregates() {
    var engine = new DefaultCalciteQueryEngine();
    var result =
        RecordPhysicalQueryExecution.execute(
            () ->
                engine.execute(
                    new CalciteQueryRequest(
                        "SELECT COUNT(*) FROM (VALUES (1)) AS t(x) WHERE 1=0", null, 1, 30_000),
                    root -> {}));
    assertThat(RecordQueryServiceImpl.countValue(result)).isZero();
  }

  private static final class MemoryRows extends AbstractTable implements ScannableTable {
    private final AtomicInteger scans = new AtomicInteger();

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
      scans.incrementAndGet();
      return Linq4j.asEnumerable(List.of(new Object[] {1}, new Object[] {2}));
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory.builder().add("id", SqlTypeName.INTEGER).build();
    }
  }
}
