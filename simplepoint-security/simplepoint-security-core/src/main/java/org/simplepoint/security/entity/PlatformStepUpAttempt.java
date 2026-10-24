package org.simplepoint.security.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Persistent account-level step-up attempt and replay protection state. */
@Entity
@Table(name = "simpoint_platform_step_up")
@Getter
@Setter
public class PlatformStepUpAttempt {
  @Id
  private String userId;
  private int failures;
  private Instant blockedUntil;
  private Long lastTotpStep;
}
