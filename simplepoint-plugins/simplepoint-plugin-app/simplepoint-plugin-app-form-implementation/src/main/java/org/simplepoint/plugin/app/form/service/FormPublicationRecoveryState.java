package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded local recovery index over the original APP intent; no duplicate work queue or remote effects. */
@Service
public class FormPublicationRecoveryState {
  private final EntityManager entityManager;
  private final Clock clock;

  /** Shares the publication control database and UTC clock. */
  public FormPublicationRecoveryState(final EntityManager entityManager, final Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /** Global worker scan returns only identities; all claims recheck tenant and operation ownership. */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public List<Candidate> due(final int limit) {
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("Recovery batch must contain 1 to 20 operations");
    }
    return entityManager.createQuery("select p.id,p.lockVersion,p.tenantId,p.applicationId,p.formId,p.bindingId,"
            + "p.recoveryActorId,p.recoveryRoleId,p.recoveryFailures from ApplicationFormWritePolicyPublicationEntity p, "
            + "ApplicationFormPageBindingEntity b "
            + "where b.id=p.bindingId and b.tenantId=p.tenantId and p.status in :pending and b.status in :bindings "
            + "and p.recoveryActorId is not null and p.recoveryNotBefore <= :now "
            + "and (b.leaseUntil is null or b.leaseUntil <= :now) order by p.recoveryNotBefore,p.id", Object[].class)
        .setParameter("pending", List.of(FormWritePolicyPublicationStatus.PREPARED, FormWritePolicyPublicationStatus.CANCELLING,
            FormWritePolicyPublicationStatus.TARGET_READY))
        .setParameter("bindings", List.of(ApplicationFormPageBindingStatus.ACTIVATING, ApplicationFormPageBindingStatus.FAILED))
        .setParameter("now", clock.instant()).setMaxResults(limit).getResultList().stream()
        .map(row -> new Candidate((String) row[0], (Long) row[1], (String) row[2], (String) row[3], (String) row[4],
            (String) row[5], (String) row[6], (String) row[7], (Integer) row[8])).toList();
  }

  /** A stale authorization failure cannot pause a newer manual takeover or another committed attempt. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void defer(final Candidate candidate, final boolean forbidden) {
    entityManager.createQuery("update ApplicationFormWritePolicyPublicationEntity p set p.recoveryNotBefore=:next, "
            + "p.recoveryError=:error,p.recoveryFailures=:failures,p.updatedAt=:now,p.lockVersion=p.lockVersion+1 "
            + "where p.id=:id and p.tenantId=:tenant and p.lockVersion=:version and p.status in :pending")
        .setParameter("next", forbidden || candidate.failures() >= 4 ? null : clock.instant().plusSeconds(30L * (candidate.failures() + 1)))
        .setParameter("failures", Math.min(5, candidate.failures() + 1))
        .setParameter("error", (forbidden ? ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED : ApplicationFormErrorCode.PUBLICATION_FAILED).name())
        .setParameter("now", clock.instant()).setParameter("id", candidate.policyId()).setParameter("tenant", candidate.tenantId())
        .setParameter("version", candidate.version())
        .setParameter("pending", List.of(FormWritePolicyPublicationStatus.PREPARED, FormWritePolicyPublicationStatus.CANCELLING,
            FormWritePolicyPublicationStatus.TARGET_READY)).executeUpdate();
  }

  /** Version fences the identity selection between remote authorization resolution and local claim. */
  public record Candidate(String policyId, long version, String tenantId, String applicationId, String formId,
                          String bindingId, String actorId, String roleId, int failures) {
  }
}
