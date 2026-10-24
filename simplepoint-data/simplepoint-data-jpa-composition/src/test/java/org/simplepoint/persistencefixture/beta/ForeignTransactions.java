package org.simplepoint.persistencefixture.beta;

import org.springframework.transaction.annotation.Transactional;

/** Deliberately selects a foreign manager to verify startup rejection. */
public class ForeignTransactions {
  /** Must never execute because its manager belongs outside the owning module. */
  @Transactional("transactionManager")
  public void write() {}
}
