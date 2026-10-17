package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Every accepted request key stays bound to its original release, including deduplicated requests. */
@Entity
@Table(name = "simpoint_app_form_release_keys", uniqueConstraints =
    @UniqueConstraint(name = "uk_app_release_request_key", columnNames = {"tenant_id", "request_key"}))
@Getter
@Setter
public class ApplicationFormReleaseKeyEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "request_key", length = 80, nullable = false, updatable = false)
  private String requestKey;

  @Column(name = "binding_id", length = 36, nullable = false, updatable = false)
  private String bindingId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
