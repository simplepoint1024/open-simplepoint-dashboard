package org.simplepoint.plugin.rbac.module;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import javax.sql.DataSource;
import org.simplepoint.core.scheduling.PeriodicWorker;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservation;
import org.simplepoint.plugin.rbac.tenant.directory.MemberLabelObservationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Starts only when identity is selected; the shared lifecycle owns thread shutdown. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MemberLabelObservationProperties.class)
public class IdentityLabelObservationConfiguration {
  @Bean
  MemberLabelObservation memberLabelObservation(
      DataSource source,
      PlatformTransactionManager transactions,
      EventPublisher publisher,
      MemberLabelObservationProperties properties) {
    return new MemberLabelObservation(source, transactions, publisher, properties);
  }

  @Bean
  MeterBinder memberLabelObservationMetrics(MemberLabelObservation observation) {
    return registry ->
        Gauge.builder(
                "simplepoint.identity.member.labels.pending",
                observation,
                MemberLabelObservation::pending)
            .register(registry);
  }

  @Bean
  PeriodicWorker memberLabelObservationWorker(
      MemberLabelObservation observation, MemberLabelObservationProperties properties) {
    return new PeriodicWorker(
        "identity-member-label-observations", properties.delay(), observation::observe);
  }
}
