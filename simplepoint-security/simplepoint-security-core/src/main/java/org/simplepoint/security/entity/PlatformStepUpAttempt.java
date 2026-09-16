package org.simplepoint.security.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "simpoint_platform_step_up")
@Getter @Setter
public class PlatformStepUpAttempt {
  @Id
  private String userId;
  private int failures;
  private Instant blockedUntil;
  private Long lastTotpStep;
}
