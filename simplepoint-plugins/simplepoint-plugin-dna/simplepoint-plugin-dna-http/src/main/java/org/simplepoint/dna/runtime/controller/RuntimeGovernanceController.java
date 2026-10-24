package org.simplepoint.dna.runtime.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.AuditSummary;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.MigrationSummary;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.ModelSummary;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.Resource;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.TableSchema;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog.VersionSummary;
import org.simplepoint.dna.runtime.service.RuntimeSchemaReview;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private final RuntimeSchemaReview reviews;

  /** Creates the governance HTTP adapter. */
  public RuntimeGovernanceController(
      final RuntimeGovernanceCatalog catalog, final RuntimeSchemaReview reviews) {
    this.catalog = catalog;
    this.reviews = reviews;
  }

  /** Returns the model list schema and allowed actions. */
  @GetMapping("/models/schema")
  public Response<TableSchema> modelSchema() {
    return Response.okay(catalog.schema(Resource.MODELS));
  }

  /** Lists models with validated filters and tenant scope. */
  @GetMapping("/models")
  public Response<Page<ModelSummary>> models(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable) {
    return Response.okay(catalog.models(filters, pageable));
  }

  /** Returns the immutable version list schema. */
  @GetMapping("/versions/schema")
  public Response<TableSchema> versionSchema() {
    return Response.okay(catalog.schema(Resource.VERSIONS));
  }

  /** Lists accessible schema versions. */
  @GetMapping("/versions")
  public Response<Page<VersionSummary>> versions(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable) {
    return Response.okay(catalog.versions(filters, pageable));
  }

  /** Returns the migration list schema and recovery actions. */
  @GetMapping("/migrations/schema")
  public Response<TableSchema> migrationSchema() {
    return Response.okay(catalog.schema(Resource.MIGRATIONS));
  }

  /** Lists scoped migration operations. */
  @GetMapping("/migrations")
  public Response<Page<MigrationSummary>> migrations(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable) {
    return Response.okay(catalog.migrations(filters, pageable));
  }

  /** Returns the audit list schema. */
  @GetMapping("/audits/schema")
  public Response<TableSchema> auditSchema() {
    return Response.okay(catalog.schema(Resource.AUDITS));
  }

  /** Lists scoped runtime audit summaries. */
  @GetMapping("/audits")
  public Response<Page<AuditSummary>> audits(
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable) {
    return Response.okay(catalog.audits(filters, pageable));
  }

  /** Returns tenant-scoped model metadata independently of migration planning. */
  @GetMapping("/models/{modelId}")
  public Response<RuntimeGovernanceCatalog.ModelDetail> model(
      @PathVariable(name = "modelId") final String modelId) {
    return Response.okay(catalog.model(modelId));
  }

  /** Prepares the active namespace through the same authorized publication state machine. */
  @PostMapping("/models/{modelId}/prepare-storage")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.retry')")
  public Response<SchemaPublicationView> prepareStorage(
      @PathVariable("modelId") final String modelId,
      @RequestParam("expectedVersion") final long expectedVersion) {
    return Response.okay(catalog.prepareStorage(modelId, expectedVersion));
  }

  /** Plans changes for an accessible model version. */
  @GetMapping("/models/{modelId}/plan")
  public Response<SchemaPlanView> plan(
      @PathVariable(name = "modelId") final String modelId,
      @RequestParam(name = "targetVersion") final int targetVersion) {
    return Response.okay(catalog.plan(modelId, targetVersion));
  }

  /** Reviews the current immutable target through the scoped governance catalog. */
  @GetMapping("/models/{modelId}/review")
  public Response<RuntimeSchemaReview.Review> review(
      @PathVariable("modelId") final String modelId) {
    return Response.okay(reviews.inspect(modelId));
  }

  /** Executes only the inspected revision and checksums under a distinct publication authority. */
  @PostMapping("/models/{modelId}/publish")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.publish')")
  public Response<SchemaPublicationView> publish(
      @PathVariable("modelId") final String modelId,
      @RequestBody final RuntimeSchemaReview.Command command) {
    return Response.okay(reviews.publish(modelId, command));
  }

  /** Returns the current state of an accessible migration. */
  @GetMapping("/migrations/{migrationId}")
  public Response<SchemaPublicationView> migration(
      @PathVariable(name = "migrationId") final String migrationId) {
    return Response.okay(catalog.migration(migrationId));
  }

  /** Retries a migration with an expected model version. */
  @PostMapping("/migrations/{migrationId}/retry")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.retry')")
  public Response<SchemaPublicationView> retry(
      @PathVariable(name = "migrationId") final String migrationId,
      @RequestParam(name = "expectedVersion") final long expectedVersion) {
    return Response.okay(catalog.retry(migrationId, expectedVersion));
  }

  /** Recovers a migration through the authorized publication service. */
  @PostMapping("/migrations/{migrationId}/recover")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.models.retry')")
  public Response<SchemaPublicationView> recover(
      @PathVariable(name = "migrationId") final String migrationId,
      @RequestParam(name = "expectedVersion") final long expectedVersion) {
    return Response.okay(catalog.recover(migrationId, expectedVersion));
  }
}
