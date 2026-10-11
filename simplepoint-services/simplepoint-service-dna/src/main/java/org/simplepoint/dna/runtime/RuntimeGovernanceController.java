package org.simplepoint.dna.runtime;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.AuditSummary;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.MigrationSummary;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.ModelSummary;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.Resource;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.TableSchema;
import org.simplepoint.dna.runtime.RuntimeGovernanceCatalog.VersionSummary;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform endpoints for schema-driven Runtime governance tables and recovery actions. */
@RestController
@RequestMapping("/platform/dna/runtime")
@PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.view')")
@Tag(name = "DNA Runtime 治理", description = "物理模型、版本、迁移和审计")
public class RuntimeGovernanceController {

  private final RuntimeGovernanceCatalog catalog;

  /** Creates the governance HTTP adapter. */
  public RuntimeGovernanceController(final RuntimeGovernanceCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/models/schema")
  public Response<TableSchema> modelSchema() {
    return Response.okay(catalog.schema(Resource.MODELS));
  }

  @GetMapping("/models")
  public Response<Page<ModelSummary>> models(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.models(filters, pageable));
  }

  @GetMapping("/versions/schema")
  public Response<TableSchema> versionSchema() {
    return Response.okay(catalog.schema(Resource.VERSIONS));
  }

  @GetMapping("/versions")
  public Response<Page<VersionSummary>> versions(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.versions(filters, pageable));
  }

  @GetMapping("/migrations/schema")
  public Response<TableSchema> migrationSchema() {
    return Response.okay(catalog.schema(Resource.MIGRATIONS));
  }

  @GetMapping("/migrations")
  public Response<Page<MigrationSummary>> migrations(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.migrations(filters, pageable));
  }

  @GetMapping("/audits/schema")
  public Response<TableSchema> auditSchema() {
    return Response.okay(catalog.schema(Resource.AUDITS));
  }

  @GetMapping("/audits")
  public Response<Page<AuditSummary>> audits(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.audits(filters, pageable));
  }

  @GetMapping("/models/{modelId}/plan")
  public Response<SchemaPlanView> plan(
      @PathVariable final String modelId,
      @RequestParam final int targetVersion
  ) {
    return Response.okay(catalog.plan(modelId, targetVersion));
  }

  @GetMapping("/migrations/{migrationId}")
  public Response<SchemaPublicationView> migration(@PathVariable final String migrationId) {
    return Response.okay(catalog.migration(migrationId));
  }

  @PostMapping("/migrations/{migrationId}/retry")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.retry')")
  public Response<SchemaPublicationView> retry(
      @PathVariable final String migrationId,
      @RequestParam final long expectedVersion
  ) {
    return Response.okay(catalog.retry(migrationId, expectedVersion));
  }

  @PostMapping("/migrations/{migrationId}/recover")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.retry')")
  public Response<SchemaPublicationView> recover(
      @PathVariable final String migrationId,
      @RequestParam final long expectedVersion
  ) {
    return Response.okay(catalog.recover(migrationId, expectedVersion));
  }
}
