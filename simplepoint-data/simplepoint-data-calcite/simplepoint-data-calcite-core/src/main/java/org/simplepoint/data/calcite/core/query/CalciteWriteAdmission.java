package org.simplepoint.data.calcite.core.query;

/** Trusted execution-owner policy; SQL requests cannot supply or disable this callback. */
@FunctionalInterface
public interface CalciteWriteAdmission {
  /**
   * Verifies the bound JDBC plan before execution, while the owner retains its locks/transaction.
   */
  void verify(CalciteWritePlan plan);

  /**
   * Rechecks owner state after preparation and parameter binding, immediately before executing JDBC
   * DML. Owners retaining native locks can detect lost sessions without acquiring new locks.
   */
  default void beforeExecution() {}
}
