package org.simplepoint.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Singleton row locked for all platform security mutations; seeded by the migration. */
@Entity
@Table(name = "simpoint_platform_security_state")
@Getter @Setter
public class PlatformSecurityState {
  @Id
  private String id;
}
