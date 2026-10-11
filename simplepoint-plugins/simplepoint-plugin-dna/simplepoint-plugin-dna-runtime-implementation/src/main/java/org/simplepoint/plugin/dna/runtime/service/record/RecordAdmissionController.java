package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.springframework.stereotype.Component;

/** Enforces a hard per-tenant concurrent Record execution budget. */
@Component
class RecordAdmissionController {

  private final int maxConcurrentPerTenant;
  private final ConcurrentHashMap<String, Semaphore> tenants = new ConcurrentHashMap<>();

  RecordAdmissionController(final DnaRuntimeExecutionProperties properties) {
    this.maxConcurrentPerTenant = properties.getMaxConcurrentPerTenant();
  }

  Permit enter(final String tenantId) {
    Semaphore semaphore = tenants.computeIfAbsent(
        tenantId,
        ignored -> new Semaphore(maxConcurrentPerTenant, true)
    );
    if (!semaphore.tryAcquire()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_RATE_LIMITED,
          "The tenant Record execution concurrency limit was reached"
      );
    }
    return semaphore::release;
  }

  /** One acquired execution slot released by structured scope. */
  interface Permit extends AutoCloseable {
    @Override
    void close();
  }
}
