package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.annotation.UuidStringGenerator;
import org.springframework.core.annotation.Order;

/** Transactional audit of platform account changes, deliberately excluding credentials. */
@Entity
@Table(name = "simpoint_platform_security_audit")
@Getter @Setter
@Schema(title = "i18n:platform-accounts.audit", description = "i18n:platform-accounts.auditDescription")
public class PlatformSecurityAudit {
  @Id @UuidStringGenerator
  @Order(0)
  @Schema(title = "i18n:platform-accounts.id", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "false")))
  private String id;
  @Order(2)
  @Schema(title = "i18n:platform-accounts.actor", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String actorId;
  @Order(3)
  @Schema(title = "i18n:platform-accounts.target", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String targetId;
  @Order(4)
  @Schema(title = "i18n:platform-accounts.action", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String action;
  @Column(length = 2000)
  @Order(5)
  @Schema(title = "i18n:platform-accounts.before", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String beforeState;
  @Column(length = 2000)
  @Order(6)
  @Schema(title = "i18n:platform-accounts.after", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String afterState;
  @Column(length = 500)
  @Order(7)
  @Schema(title = "i18n:platform-accounts.reason", accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String reason;
  @Order(1)
  @Schema(title = "i18n:platform-accounts.time", accessMode = Schema.AccessMode.READ_ONLY,
      format = "date-time", extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Instant occurredAt;
}
