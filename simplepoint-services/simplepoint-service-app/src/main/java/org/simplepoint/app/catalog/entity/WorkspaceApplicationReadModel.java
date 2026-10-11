package org.simplepoint.app.catalog.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** Queryable application directory; aggregates are computed before filtering and pagination. */
@Entity
@Immutable
@Getter
@Synchronize({"simpoint_app_definitions", "simpoint_app_pages"})
@Subselect(
    """
    select a.id, a.tenant_id, a.name, a.description, a.icon, a.status, a.main_page_id,
           p.name as main_page_name,
           (select count(*) from simpoint_app_pages c where c.tenant_id = a.tenant_id
                and c.application_id = a.id and c.kind = 'ATTACHED') as attached_page_count,
           a.created_by, a.created_at, a.updated_at
      from simpoint_app_definitions a
      left join simpoint_app_pages p on p.id = a.main_page_id and p.tenant_id = a.tenant_id
          and p.application_id = a.id and p.kind = 'MAIN'
    """)
public class WorkspaceApplicationReadModel {
  @Id private String id;
  private String tenantId;
  private String name;
  private String description;
  private String icon;
  private String status;
  private String mainPageId;
  private String mainPageName;
  private long attachedPageCount;
  private String createdBy;
  private Instant createdAt;
  private Instant updatedAt;
}
