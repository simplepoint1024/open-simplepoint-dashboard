package org.simplepoint.plugin.oidc.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/** Durable binding between an external issuer subject and a local SimplePoint account. */
@Data
@Entity
@Table(name = "simpoint_ac_external_identity_link",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_external_identity_subject",
            columnNames = {"provider_id", "external_subject"}),
        @UniqueConstraint(name = "uk_external_identity_provider_user",
            columnNames = {"provider_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_external_identity_user", columnList = "user_id"),
        @Index(name = "idx_external_identity_provider_user",
            columnList = "provider_id, user_id")
    })
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(hidden = true)
public class ExternalIdentityLink extends BaseEntityImpl<String> {

  @Column(name = "provider_id", length = 64, nullable = false)
  private String providerId;

  @Column(name = "external_subject", length = 512, nullable = false)
  private String externalSubject;

  @Column(name = "user_id", length = 64, nullable = false)
  private String userId;

  @Column(name = "email_at_link", length = 320)
  private String emailAtLink;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;
}
