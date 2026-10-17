package org.simplepoint.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** APP service projection joining its catalog and form domains without remote infrastructure data. */
@Entity
@Immutable
@Getter
@Synchronize({"simpoint_app_form_page_bindings", "simpoint_app_form_publications", "simpoint_app_pages",
    "simpoint_app_form_policy_publications"})
@Subselect("""
    select b.id, b.tenant_id, b.application_id, b.form_id, b.page_id, p.name as page_name,
           b.form_version, b.status, coalesce(b.error_code, d.error_code) as error_code,
           b.updated_at, b.attempt, policy.status as policy_status
      from simpoint_app_form_page_bindings b
      join simpoint_app_pages p on p.id = b.page_id and p.tenant_id = b.tenant_id
          and p.application_id = b.application_id
      left join simpoint_app_form_publications d on d.form_id = b.form_id
          and d.form_version = b.form_version and d.tenant_id = b.tenant_id
      left join simpoint_app_form_policy_publications policy on policy.binding_id = b.id and policy.tenant_id = b.tenant_id
    """)
public class ApplicationFormReleaseReadModel {
  @Id private String id;
  @Column(name = "tenant_id") private String tenantId;
  @Column(name = "application_id") private String applicationId;
  @Column(name = "form_id") private String formId;
  @Column(name = "page_id") private String pageId;
  @Column(name = "page_name") private String pageName;
  @Column(name = "form_version") private int formVersion;
  private String status;
  private int attempt;
  @Column(name = "policy_status") private String policyStatus;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "updated_at") private Instant updatedAt;
}
