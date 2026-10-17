package org.simplepoint.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** History pages never load the accepted authority, request payload or complete business snapshot. */
@Entity
@Immutable
@Subselect("select id, title, tenant_id as tenant_id, application_id as application_id, page_id as page_id, actor_id as actor_id, "
    + "form_version as form_version, definition_version as definition_version, process_instance_id as process_instance_id, "
    + "process_status as process_status, status, step, error_code as error_code, created_at as created_at, updated_at as updated_at, "
    + "case when snapshot_json is not null then true else false end as snapshot_available from simpoint_app_form_submissions")
@Synchronize("simpoint_app_form_submissions")
@Getter
public class ApplicationFormSubmissionReadModel {
  @Id private String id;
  private String title;
  @Column(name = "tenant_id") private String tenantId;
  @Column(name = "application_id") private String applicationId;
  @Column(name = "page_id") private String pageId;
  @Column(name = "actor_id") private String actorId;
  @Column(name = "form_version") private int formVersion;
  @Column(name = "definition_version") private int definitionVersion;
  @Column(name = "process_instance_id") private String processInstanceId;
  @Column(name = "process_status") private String processStatus;
  private String status;
  private String step;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "created_at") private Instant createdAt;
  @Column(name = "updated_at") private Instant updatedAt;
  @Column(name = "snapshot_available") private boolean snapshotAvailable;
}
