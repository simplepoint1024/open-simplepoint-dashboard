package org.simplepoint.plugin.dna.runtime.service.record;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Records bounded metrics and sanitized diagnostics for Record operations. */
@Component
class RecordExecutionObserver {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecordExecutionObserver.class);

  private final MeterRegistry meterRegistry;
  private final long slowThresholdMs;

  RecordExecutionObserver(
      final ObjectProvider<MeterRegistry> meterRegistry,
      final DnaRuntimeExecutionProperties properties
  ) {
    this.meterRegistry = meterRegistry.getIfAvailable();
    this.slowThresholdMs = properties.getSlowThresholdMs();
  }

  void record(
      final String operation,
      final String outcome,
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final long durationMs
  ) {
    if (meterRegistry != null) {
      Timer.builder("simplepoint.dna.runtime.operation")
          .description("DNA Runtime Record operation latency")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meterRegistry)
          .record(Duration.ofMillis(durationMs));
    }
    if (!"succeeded".equals(outcome) || durationMs >= slowThresholdMs) {
      LOGGER.warn(
          "DNA Runtime operation tenant={} model={} datasource={} operation={} outcome={} durationMs={}",
          access.actor().tenantId(),
          model.physicalModelId(),
          model.target().metadataTarget().dataSourceId(),
          operation,
          outcome,
          durationMs
      );
    }
  }
}
