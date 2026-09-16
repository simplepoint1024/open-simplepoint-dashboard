package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.simplepoint.plugin.rbac.core.api.pojo.command.PlatformAccountCommand;
import org.simplepoint.security.authentication.TotpVerifier;
import org.simplepoint.security.entity.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Attempts commit independently, so failed outer changes cannot reset rate limits or replay protection. */
@Service
public class PlatformStepUpService {
  private final EntityManager em;
  private final PasswordEncoder passwords;
  private final TotpVerifier totp = new TotpVerifier();
  private final java.time.Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public PlatformStepUpService(EntityManager em, PasswordEncoder passwords) {
    this(em, passwords, java.time.Clock.systemUTC());
  }

  PlatformStepUpService(EntityManager em, PasswordEncoder passwords, java.time.Clock clock) {
    this.clock = clock;
    this.em = em;
    this.passwords = passwords;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AccessDeniedException.class)
  public void verify(String actorId, PlatformAccountCommand command) {
    if (em.find(PlatformSecurityState.class, "platform", LockModeType.PESSIMISTIC_WRITE) == null) {
      throw new AccessDeniedException("平台安全表未初始化，请先执行迁移");
    }
    User actor = em.find(User.class, actorId);
    if (actor == null || actor.getDeletedAt() != null || !Boolean.TRUE.equals(actor.getEnabled()) || !Boolean.TRUE.equals(actor.getAccountNonLocked())
        || !Boolean.TRUE.equals(actor.getAccountNonExpired()) || !Boolean.TRUE.equals(actor.getCredentialsNonExpired())) {
      throw new AccessDeniedException("当前账号不可用");
    }
    PlatformStepUpAttempt attempt = em.find(PlatformStepUpAttempt.class, actorId);
    if (attempt == null) {
      attempt = new PlatformStepUpAttempt();
      attempt.setUserId(actorId);
      em.persist(attempt);
    }
    Instant now = clock.instant();
    if (attempt.getBlockedUntil() != null && attempt.getBlockedUntil().isAfter(now)) {
      throw new AccessDeniedException("安全验证失败次数过多，请稍后重试");
    }
    if (attempt.getBlockedUntil() != null) {
      attempt.setFailures(0);
      attempt.setBlockedUntil(null);
    }
    boolean valid = command.getConfirmationPassword() != null && actor.getPassword() != null
        && passwords.matches(command.getConfirmationPassword(), actor.getPassword());
    Long acceptedStep = null;
    if (Boolean.TRUE.equals(actor.getTwoFactorEnabled())) {
      acceptedStep = totp.matchingStep(actor.getTwoFactorSecret(), command.getConfirmationCode(), now);
      valid = valid && acceptedStep != null
          && (attempt.getLastTotpStep() == null || acceptedStep > attempt.getLastTotpStep());
    }
    if (!valid) {
      attempt.setFailures(attempt.getFailures() + 1);
      if (attempt.getFailures() >= 5) attempt.setBlockedUntil(now.plusSeconds(900));
      PlatformSecurityAudit audit = new PlatformSecurityAudit();
      audit.setActorId(actorId);
      audit.setTargetId(actorId);
      audit.setAction("STEP_UP_REJECTED");
      audit.setOccurredAt(now);
      audit.setReason("Credential verification failed");
      em.persist(audit);
      throw new AccessDeniedException("安全验证失败；请确认当前账号密码及已启用的动态验证码");
    }
    attempt.setFailures(0);
    if (Boolean.TRUE.equals(actor.getTwoFactorEnabled())) attempt.setLastTotpStep(acceptedStep);
  }
}
