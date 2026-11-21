package org.simplepoint.data.calcite.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual prepare/execute boundary, including denial, missing binding and thread isolation. */
class CalciteWriteAdmissionTest {
  @Test
  void changedOwnerStateAfterPreparationRejectsBeforeExecutingBoundParameters() throws Exception {
    var source = source();
    var order = new ArrayList<String>();
    var refusal = new IllegalStateException("owner-state-changed");
    var admission =
        new CalciteWriteAdmission() {
          @Override
          public void verify(CalciteWritePlan plan) {
            order.add("plan");
          }

          @Override
          public void beforeExecution() {
            order.add("execution");
            throw refusal;
          }
        };
    var request =
        new CalciteUpdateRequest(
            "INSERT INTO logical.target VALUES (?,?)", "logical", 5000, List.of(1, 9));
    var engine = new DefaultCalciteQueryEngine();
    assertSame(
        refusal,
        assertThrows(
            IllegalStateException.class,
            () -> engine.executeUpdate(request, configured(source, admission))));
    assertEquals(List.of("plan", "execution"), order);
    assertEquals(0, value(source, "SELECT COUNT(*) FROM target"));
    assertEquals(1, engine.executeUpdate(request, configured(source, null)).affectedRows());
  }

  @Test
  void subqueriesAndDirectoryAliasesAreVerifiedBeforeDenial() throws Exception {
    var source = source();
    execute(source, "INSERT INTO target VALUES (1,1)");
    execute(source, "INSERT INTO source VALUES (2,7)");
    execute(source, "INSERT INTO auxiliary VALUES (1,1)");
    var plans = new ArrayList<CalciteWritePlan>();
    var refusal = new IllegalArgumentException("fixed-domain-rejection");
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                new DefaultCalciteQueryEngine()
                    .executeUpdate(
                        new CalciteUpdateRequest(
                            "UPDATE logical.target SET amount=(SELECT MAX(amount) FROM"
                                + " alias.source) WHERE EXISTS (SELECT 1 FROM logical.auxiliary"
                                + " WHERE id=target.id)",
                            "logical",
                            5000,
                            null),
                        configured(
                            source,
                            plan -> {
                              plans.add(plan);
                              throw refusal;
                            })));
    assertSame(refusal, failure);
    assertEquals(1, plans.size());
    var plan = plans.getFirst();
    assertEquals(CalciteWritePlan.Operation.UPDATE, plan.operation());
    var target =
        plan.tables().stream()
            .filter(table -> table.role() == CalciteWritePlan.Role.TARGET)
            .toList();
    assertEquals(1, target.size());
    assertEquals("TARGET", target.getFirst().table());
    assertEquals("PUBLIC", target.getFirst().schema());
    assertTrue(
        plan.tables().stream()
            .map(CalciteWritePlan.TableUse::table)
            .toList()
            .containsAll(List.of("TARGET", "SOURCE", "AUXILIARY")));
    assertTrue(
        plan.tables().stream().anyMatch(table -> table.logicalPath().getFirst().equals("alias")));
    plan.tables().forEach(table -> assertSame(source, table.dataSource()));
    assertThrows(UnsupportedOperationException.class, () -> plan.tables().clear());
    assertEquals(1, value(source, "SELECT amount FROM target"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void refusalBeforeExecutionPreservesDomainDiagnosisAndDoesNotLeakToNextWrite(boolean parameters)
      throws Exception {
    var source = source();
    var refusal = new IllegalArgumentException("fixed-domain-rejection");
    var request =
        new CalciteUpdateRequest(
            parameters
                ? "INSERT INTO logical.target VALUES (?,?)"
                : "INSERT INTO logical.target VALUES (1,9)",
            "logical",
            5000,
            parameters ? List.of(1, 9) : null);
    var engine = new DefaultCalciteQueryEngine();
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                engine.executeUpdate(
                    request,
                    configured(
                        source,
                        plan -> {
                          throw refusal;
                        })));
    assertSame(refusal, failure);
    assertEquals(0, value(source, "SELECT COUNT(*) FROM target"));
    assertEquals(1, engine.executeUpdate(request, configured(source, null)).affectedRows());
    assertEquals(9, value(source, "SELECT amount FROM target"));
  }

  @Test
  void successfulAdmissionIsPerOperationAndReadsDoNotInvokeIt() throws Exception {
    var source = source();
    var operations = new ArrayList<CalciteWritePlan.Operation>();
    var schemas = configured(source, plan -> operations.add(plan.operation()));
    var engine = new DefaultCalciteQueryEngine();
    engine.executeUpdate(
        new CalciteUpdateRequest(
            "INSERT INTO logical.target VALUES (?,?)", "logical", 5000, List.of(1, 3)),
        schemas);
    engine.executeUpdate(
        new CalciteUpdateRequest(
            "UPDATE logical.target SET amount=? WHERE id=?", "logical", 5000, List.of(4, 1)),
        schemas);
    assertEquals(4, value(source, "SELECT amount FROM target"));
    engine.execute(
        new CalciteQueryRequest("SELECT * FROM logical.target", "logical", 10, 5000), schemas);
    engine.executeUpdate(
        new CalciteUpdateRequest(
            "DELETE FROM logical.target WHERE id=?", "logical", 5000, List.of(1)),
        schemas);
    assertEquals(
        List.of(
            CalciteWritePlan.Operation.INSERT,
            CalciteWritePlan.Operation.UPDATE,
            CalciteWritePlan.Operation.DELETE),
        operations);
  }

  @Test
  void missingConversionCannotCountAsApproval() {
    var calls = new AtomicInteger();
    try (var admission = new PreparedWriteAdmission(plan -> calls.incrementAndGet())) {
      var failure = assertThrows(CalciteWritePlanException.class, admission::requireVerified);
      assertEquals(CalciteWritePlanException.Code.MISSING_PLAN, failure.code());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void expressionDepthIsBoundedBeforeOwnerApprovalOrExecution() throws Exception {
    var source = source();
    var calls = new AtomicInteger();
    String expression = "ABS(".repeat(70) + "amount" + ")".repeat(70);
    var failure =
        assertThrows(
            CalciteWritePlanException.class,
            () ->
                new DefaultCalciteQueryEngine()
                    .executeUpdate(
                        new CalciteUpdateRequest(
                            "INSERT INTO logical.target SELECT id,"
                                + expression
                                + " FROM logical.source",
                            "logical",
                            5000,
                            null),
                        configured(source, plan -> calls.incrementAndGet())));
    assertEquals(CalciteWritePlanException.Code.PLAN_LIMIT_EXCEEDED, failure.code());
    assertEquals(0, calls.get());
    assertEquals(0, value(source, "SELECT COUNT(*) FROM target"));
  }

  @Test
  void repeatedConversionDuringPreparationIsRejectedBeforeAnyWrite() throws Exception {
    var source = source();
    var once = new AtomicBoolean();
    try (var ignored =
        Hook.CONVERTED.addThread(
            (RelNode root) -> {
              if (once.compareAndSet(false, true)) {
                Hook.CONVERTED.run(root);
              }
            })) {
      var failure =
          assertThrows(
              CalciteWritePlanException.class,
              () ->
                  new DefaultCalciteQueryEngine()
                      .executeUpdate(
                          new CalciteUpdateRequest(
                              "INSERT INTO logical.target VALUES (1,2)", "logical", 5000, null),
                          configured(source, plan -> {})));
      assertEquals(CalciteWritePlanException.Code.MULTIPLE_PLANS, failure.code());
    }
    assertEquals(0, value(source, "SELECT COUNT(*) FROM target"));
  }

  @Test
  void unknownReadAdapterCannotBeOmittedFromThePlan() throws Exception {
    var source = source();
    var calls = new AtomicInteger();
    var base = configured(source, plan -> calls.incrementAndGet());
    var schemas =
        new CalciteSchemaConfigurer() {
          @Override
          public void configure(SchemaPlus root) {
            base.configure(root);
            root.add("memory", new MemoryTable());
          }

          @Override
          public CalciteWriteAdmission writeAdmission() {
            return base.writeAdmission();
          }
        };
    var failure =
        assertThrows(
            CalciteWritePlanException.class,
            () ->
                new DefaultCalciteQueryEngine()
                    .executeUpdate(
                        new CalciteUpdateRequest(
                            "INSERT INTO logical.target SELECT id,amount FROM memory",
                            null,
                            5000,
                            null),
                        schemas));
    assertEquals(CalciteWritePlanException.Code.UNSUPPORTED_PLAN, failure.code());
    assertEquals(0, calls.get());
    assertEquals(0, value(source, "SELECT COUNT(*) FROM target"));
  }

  @Test
  void simultaneousOperationsHaveIndependentPolicies() throws Exception {
    var rejectedSource = source();
    var allowedSource = source();
    var entered = new CountDownLatch(2);
    var refusal = new IllegalArgumentException("fixed-domain-rejection");
    var request =
        new CalciteUpdateRequest("INSERT INTO logical.target VALUES (1,8)", "logical", 5000, null);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var rejected =
          executor.submit(
              () ->
                  new DefaultCalciteQueryEngine()
                      .executeUpdate(
                          request,
                          configured(
                              rejectedSource,
                              plan -> {
                                synchronize(entered);
                                throw refusal;
                              })));
      var allowed =
          executor.submit(
              () ->
                  new DefaultCalciteQueryEngine()
                      .executeUpdate(
                          request, configured(allowedSource, plan -> synchronize(entered))));
      var failure =
          assertThrows(ExecutionException.class, () -> rejected.get(10, TimeUnit.SECONDS));
      assertSame(refusal, failure.getCause());
      assertEquals(1, allowed.get(10, TimeUnit.SECONDS).affectedRows());
    }
    assertEquals(0, value(rejectedSource, "SELECT COUNT(*) FROM target"));
    assertEquals(1, value(allowedSource, "SELECT COUNT(*) FROM target"));
  }

  private static void synchronize(CountDownLatch entered) {
    entered.countDown();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  private static CalciteSchemaConfigurer configured(
      JdbcDataSource source, CalciteWriteAdmission admission) {
    return new CalciteSchemaConfigurer() {
      @Override
      public void configure(SchemaPlus root) {
        var schema = JdbcSchema.create(root, "logical", source, null, "PUBLIC");
        root.add("logical", schema);
        root.add("alias", schema);
      }

      @Override
      public CalciteWriteAdmission writeAdmission() {
        return admission;
      }
    };
  }

  private static JdbcDataSource source() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:admission_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    source.setUser("sa");
    source.setPassword("");
    for (String name : List.of("target", "source", "auxiliary")) {
      execute(source, "CREATE TABLE " + name + " (id INTEGER, amount INTEGER)");
    }
    return source;
  }

  private static void execute(JdbcDataSource source, String sql) throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int value(JdbcDataSource source, String sql) throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      int value = rows.getInt(1);
      assertFalse(rows.next());
      return value;
    }
  }

  private static final class MemoryTable extends AbstractTable implements ScannableTable {
    @Override
    public RelDataType getRowType(RelDataTypeFactory factory) {
      return factory
          .builder()
          .add("ID", SqlTypeName.INTEGER)
          .add("AMOUNT", SqlTypeName.INTEGER)
          .build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext context) {
      return Linq4j.asEnumerable(Collections.singletonList(new Object[] {2, 7}));
    }
  }
}
