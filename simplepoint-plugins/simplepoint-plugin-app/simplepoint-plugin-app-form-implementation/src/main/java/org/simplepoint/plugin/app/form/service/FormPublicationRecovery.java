package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Continues one batch per operation with current authority and the existing durable publication lease. */
@Component
@ConditionalOnProperty(prefix = "simplepoint.app.form.publication", name = "recovery-enabled", havingValue = "true", matchIfMissing = true)
public class FormPublicationRecovery {
  private static final Logger LOG = LoggerFactory.getLogger(FormPublicationRecovery.class);
  private final FormPublicationRecoveryState state;
  private final FormPublicationRecoveryAuthority authority;
  private final ApplicationFormPageBindingStateService bindings;
  private final ApplicationFormPublicationServiceImpl publications;
  private final FormWritePolicyPublicationStateService policies;

  /** Reuses domain claims and orchestration; no request token or cached grants are persisted. */
  public FormPublicationRecovery(final FormPublicationRecoveryState state, final FormPublicationRecoveryAuthority authority,
      final ApplicationFormPageBindingStateService bindings, final ApplicationFormPublicationServiceImpl publications,
      final FormWritePolicyPublicationStateService policies) {
    this.state = state;
    this.authority = authority;
    this.bindings = bindings;
    this.publications = publications;
    this.policies = policies;
  }

  /** Dedicated scheduler prevents slow remote operations from starving submission recovery. */
  @Scheduled(scheduler = "formPublicationRecoveryScheduler", initialDelayString = "${simplepoint.app.form.publication.recovery-initial-delay:15000}",
      fixedDelayString = "${simplepoint.app.form.publication.recovery-delay:5000}")
  public void recover() {
    for (var candidate : state.due(5)) {
      try (var ignored = authority.open(candidate.tenantId(), candidate.actorId(), candidate.roleId())) {
        var claim = bindings.claimPolicyRecovery(candidate.applicationId(), candidate.formId(), candidate.bindingId(), candidate);
        if (claim != null && claim.execute()) {
          try {
            publications.recoverPolicy(candidate.applicationId(), candidate.formId(), claim, false);
          } catch (RuntimeException failure) {
            policies.deferRecoveryAttempt(claim.work(), claim.view().attempt(), forbidden(failure));
            LOG.warn("Publication attempt deferred: operation={}, cause={}", candidate.policyId(), failure.getClass().getSimpleName());
          }
        }
      } catch (RuntimeException failure) {
        boolean forbidden = forbidden(failure);
        try {
          state.defer(candidate, forbidden);
        } catch (RuntimeException persistenceFailure) {
          failure.addSuppressed(persistenceFailure);
        }
        // Identity resolution can fail before any claim. The CAS cannot change a newer owner or result.
        LOG.warn("Publication recovery deferred: operation={}, cause={}", candidate.policyId(), failure.getClass().getSimpleName());
      }
    }
  }

  private static boolean forbidden(final RuntimeException failure) {
    return failure instanceof ApplicationFormException form && form.errorCode() == ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED;
  }

}
