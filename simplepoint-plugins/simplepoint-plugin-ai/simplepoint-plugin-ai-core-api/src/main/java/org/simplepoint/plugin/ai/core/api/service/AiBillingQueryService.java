package org.simplepoint.plugin.ai.core.api.service;

import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.BillingSummary;

/** Scope-isolated model billing report service. */
public interface AiBillingQueryService {

  /**
   * Summarizes priced and unpriced invocations without combining currencies.
   *
   * @param from inclusive period start, or {@code null} for the current UTC month
   * @param to   exclusive period end, or {@code null} for now
   * @return billing overview
   */
  BillingSummary summarize(Instant from, Instant to);
}
