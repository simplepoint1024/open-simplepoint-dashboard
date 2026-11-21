package org.simplepoint.data.calcite.core.query;

import java.util.Objects;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.runtime.Hook;

/** Thread-local preparation boundary. Missing/cached or repeated conversion cannot skip policy. */
final class PreparedWriteAdmission implements AutoCloseable {
  private final Hook.Closeable hook;
  private RuntimeException rejection;
  private int plans;

  PreparedWriteAdmission(CalciteWriteAdmission admission) {
    Objects.requireNonNull(admission, "admission");
    hook =
        Hook.CONVERTED.addThread(
            (RelNode root) -> {
              try {
                if (++plans != 1) {
                  throw new CalciteWritePlanException(
                      CalciteWritePlanException.Code.MULTIPLE_PLANS);
                }
                admission.verify(CalciteWritePlanCollector.collect(root));
              } catch (RuntimeException failure) {
                if (rejection == null) {
                  rejection = failure;
                }
                throw failure;
              }
            });
  }

  void requireVerified() {
    rethrowRejection();
    if (plans != 1) {
      throw new CalciteWritePlanException(CalciteWritePlanException.Code.MISSING_PLAN);
    }
  }

  void rethrowRejection() {
    if (rejection != null) {
      throw rejection;
    }
  }

  @Override
  public void close() {
    hook.close();
  }
}
