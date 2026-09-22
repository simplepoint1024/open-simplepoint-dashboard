package org.simplepoint.plugin.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/**
 * Global administrator quota read model. Aggregate once per tenant before filtering/paging; never
 * interpret an ambient tenant filter as the global storage usage of another tenant. This is an
 * inline SQL projection, not a new database table or a writable entity.
 */
@Entity
@Immutable
@Subselect(
    "select q.id, q.tenant_id, q.quota_bytes, q.enabled, q.description, q.created_at, q.updated_at,"
        + " t.name as tenant_name, coalesce(u.used_bytes, 0) as used_bytes, case when q.quota_bytes"
        + " is null then null when q.quota_bytes > coalesce(u.used_bytes, 0) then q.quota_bytes -"
        + " coalesce(u.used_bytes, 0) else 0 end as remaining_bytes from"
        + " simpoint_storage_tenant_quotas q left join simpoint_saas_tenants t on t.id ="
        + " q.tenant_id and t.deleted_at is null left join (select tenant_id, sum(content_length)"
        + " as used_bytes from simpoint_storage_objects where deleted_at is null group by"
        + " tenant_id) u on u.tenant_id = q.tenant_id where q.deleted_at is null")
@Synchronize({
  "simpoint_storage_tenant_quotas",
  "simpoint_saas_tenants",
  "simpoint_storage_objects"
})
public class StorageQuotaReadModel {
  @Id private String id;

  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "tenant_name")
  private String tenantName;

  @Column(name = "quota_bytes")
  private Long quotaBytes;

  @Column(name = "used_bytes")
  private Long usedBytes;

  @Column(name = "remaining_bytes")
  private Long remainingBytes;

  private Boolean enabled;
  private String description;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;
}
