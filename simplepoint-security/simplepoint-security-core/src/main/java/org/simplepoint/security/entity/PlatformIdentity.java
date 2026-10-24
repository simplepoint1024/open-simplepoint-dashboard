package org.simplepoint.security.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** One platform-role assignment per global account, independent of tenant membership. */
@Entity
@Table(name = "simpoint_platform_identity")
@Getter
@Setter
public class PlatformIdentity {
  @Id
  @Column(name = "user_id")
  private String userId;
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "simpoint_platform_identity_role", joinColumns = @JoinColumn(name = "user_id"),
      uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_code"}))
  @Enumerated(EnumType.STRING)
  @Column(name = "role_code", nullable = false)
  private Set<PlatformRole> roles = new HashSet<>();
}
