package org.simplepoint.plugin.ai.catalog.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** One database relation for global ordering across internal registrations and public metadata. */
@Entity
@Immutable
@Getter
@Synchronize({"simpoint_ai_mcp_servers", "simpoint_ai_catalog_entries"})
@Subselect(
    """
select concat('internal:mcp:', s.id) as id, s.id as entry_id, 'INTERNAL' as source,
       'MCP_SERVER' as kind, cast(null as varchar(512)) as registry_name, s.code,
       s.remote_server_version as version, s.name as title, s.description, cast(s.status as varchar(32)) as status,
       cast(s.scope_type as varchar(16)) as scope_type, s.tenant_id, s.endpoint_url, cast(null as varchar(2048)) as repository_url,
       cast(null as varchar(2048)) as website_url, cast(null as varchar(32)) as transport_type,
       cast(null as text) as descriptor_json, s.updated_at
  from simpoint_ai_mcp_servers s where s.deleted_at is null
union all
select concat('official:mcp:', e.id), e.id, cast(e.source as varchar(32)), cast(e.kind as varchar(32)), e.registry_name, cast(null as varchar(128)),
       e.version, coalesce(nullif(e.title, ''), e.registry_name), e.description, cast(e.status as varchar(32)),
       cast(null as varchar(16)), cast(null as varchar(64)), e.endpoint_url, e.repository_url,
       e.website_url, e.transport_type, e.descriptor_json, coalesce(e.registry_updated_at, e.updated_at)
  from simpoint_ai_catalog_entries e where e.deleted_at is null and e.source = 'OFFICIAL_MCP'
      and e.status = 'ACTIVE' and e.kind = 'MCP_SERVER'
    """)
public class AiCatalogReadModel {
  @Id private String id;
  private String entryId;
  private String source;
  private String kind;
  private String registryName;
  private String code;
  private String version;
  private String title;
  private String description;
  private String status;
  private String scopeType;
  private String tenantId;
  private String endpointUrl;
  private String repositoryUrl;
  private String websiteUrl;
  private String transportType;
  private String descriptorJson;
  private Instant updatedAt;
}
