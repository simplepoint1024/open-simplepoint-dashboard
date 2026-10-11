package org.simplepoint.plugin.dna.runtime.api.audit;

/** Persists or forwards a sanitized Runtime audit event. */
public interface DnaRuntimeAuditPublisher {

  /** Persists or forwards one sanitized audit event. */
  void publish(DnaRuntimeAuditEvent event);
}
