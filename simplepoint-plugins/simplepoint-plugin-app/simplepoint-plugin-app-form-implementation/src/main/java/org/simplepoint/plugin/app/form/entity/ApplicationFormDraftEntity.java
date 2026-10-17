package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Single mutable draft associated with a stable form identity. */
@Entity
@Table(name = "simpoint_app_form_drafts")
@Getter
@Setter
public class ApplicationFormDraftEntity {

  @Id
  @Column(name = "form_id", length = 36)
  private String formId;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(nullable = false)
  private long revision;

  @Column(name = "document_json", nullable = false, columnDefinition = "TEXT")
  private String documentJson;

  @Column(length = 64, nullable = false)
  private String checksum;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
