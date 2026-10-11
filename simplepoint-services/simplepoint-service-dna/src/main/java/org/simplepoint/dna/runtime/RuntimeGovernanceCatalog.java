package org.simplepoint.dna.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.simplepoint.plugin.dna.runtime.entity.DnaRuntimeAuditEventEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-safe read model for the platform Runtime governance screens. */
@Service
@Transactional(readOnly = true)
public class RuntimeGovernanceCatalog {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TENANT_ATTRIBUTE = "X-Tenant-Id";

  private final EntityManager entityManager;
  private final SchemaPlanService planService;
  private final SchemaPublicationService publicationService;

  /** Creates the governance read adapter. */
  public RuntimeGovernanceCatalog(
      final EntityManager entityManager,
      final SchemaPlanService planService,
      final SchemaPublicationService publicationService
  ) {
    this.entityManager = entityManager;
    this.planService = planService;
    this.publicationService = publicationService;
  }

  /** Standard table schema for one governance resource. */
  public TableSchema schema(final Resource resource) {
    requireGovernanceScope();
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.put("title", "i18n:dna.runtime." + resource.code() + ".title");
    ObjectNode properties = schema.putObject("properties");
    switch (resource) {
      case MODELS -> modelColumns(properties);
      case VERSIONS -> versionColumns(properties);
      case MIGRATIONS -> migrationColumns(properties);
      case AUDITS -> auditColumns(properties);
    }
    QueryCapabilities.applySchema(resource.entityType(), schema);
    return new TableSchema(
        JSON.convertValue(schema, new TypeReference<>() { }),
        actions(resource)
    );
  }

  /** Lists physical models with one tenant predicate shared by page and count. */
  public Page<ModelSummary> models(
      final Map<String, String> filters,
      final Pageable requested
  ) {
    QueryPage<PhysicalModelEntity> result = query(
        PhysicalModelEntity.class,
        filters,
        requested,
        List.of("displayName", "appId", "formId", "status", "latestVersion",
            "activeVersion", "updatedAt")
    );
    return map(result, model -> new ModelSummary(
        model.getId(),
        model.getDisplayName(),
        model.getAppId(),
        model.getFormId(),
        model.getStatus().name(),
        model.getLatestVersion(),
        model.getActiveVersion(),
        model.getLockVersion(),
        model.getUpdatedAt()
    ));
  }

  /** Lists immutable logical schema versions. */
  public Page<VersionSummary> versions(
      final Map<String, String> filters,
      final Pageable requested
  ) {
    QueryPage<PhysicalModelVersionEntity> result = query(
        PhysicalModelVersionEntity.class,
        filters,
        requested,
        List.of("physicalModelId", "versionNumber", "contractVersion", "schemaChecksum",
            "createdAt")
    );
    return map(result, version -> new VersionSummary(
        version.getId(),
        version.getPhysicalModelId(),
        version.getVersionNumber(),
        version.getContractVersion(),
        version.getSchemaChecksum(),
        version.getCreatedAt()
    ));
  }

  /** Lists durable migrations and their recovery state. */
  public Page<MigrationSummary> migrations(
      final Map<String, String> filters,
      final Pageable requested
  ) {
    QueryPage<SchemaMigrationEntity> result = query(
        SchemaMigrationEntity.class,
        filters,
        requested,
        List.of("physicalModelId", "fromVersion", "targetVersion", "status", "risk",
            "dialectId", "errorCode", "startedAt", "finishedAt")
    );
    return map(result, migration -> new MigrationSummary(
        migration.getId(),
        migration.getPhysicalModelId(),
        migration.getFromVersion(),
        migration.getTargetVersion(),
        migration.getStatus().name(),
        migration.getRisk() == null ? null : migration.getRisk().name(),
        migration.getDialectId(),
        migration.getErrorCode(),
        migration.getPhysicalModel().getLockVersion(),
        migration.getStartedAt(),
        migration.getFinishedAt()
    ));
  }

  /** Lists sanitized runtime audit events without business values or SQL. */
  public Page<AuditSummary> audits(
      final Map<String, String> filters,
      final Pageable requested
  ) {
    QueryPage<DnaRuntimeAuditEventEntity> result = query(
        DnaRuntimeAuditEventEntity.class,
        filters,
        requested,
        List.of("eventType", "occurredAt", "actorId", "physicalModelId", "appId",
            "formId", "dataSourceId", "modelVersion", "affectedRows", "durationMillis",
            "outcome", "errorCode")
    );
    return map(result, audit -> new AuditSummary(
        audit.getId(),
        audit.getEventType().name(),
        audit.getOccurredAt(),
        audit.getActorId(),
        audit.getPhysicalModelId(),
        audit.getAppId(),
        audit.getFormId(),
        audit.getDataSourceId(),
        audit.getModelVersion(),
        audit.getAffectedRows(),
        audit.getDurationMillis(),
        audit.getOutcome().name(),
        audit.getErrorCode() == null ? null : audit.getErrorCode().name()
    ));
  }

  /** Delegates plan generation to the Runtime engine so the UI never derives DDL. */
  public SchemaPlanView plan(final String modelId, final int targetVersion) {
    requireGovernanceScope();
    return planService.plan(modelId, targetVersion);
  }

  /** Returns durable migration and step state for a composite detail view. */
  public SchemaPublicationView migration(final String migrationId) {
    requireGovernanceScope();
    return publicationService.get(migrationId);
  }

  /** Retries only a failed migration through its existing guarded state machine. */
  public SchemaPublicationView retry(final String migrationId, final long expectedVersion) {
    requireGovernanceScope();
    return publicationService.retry(migrationId, expectedVersion);
  }

  /** Fences a stale nonterminal migration before the operator explicitly retries it. */
  public SchemaPublicationView recover(final String migrationId, final long expectedVersion) {
    requireGovernanceScope();
    return publicationService.recover(migrationId, expectedVersion);
  }

  private <E> QueryPage<E> query(
      final Class<E> entityType,
      final Map<String, String> filters,
      final Pageable requested,
      final List<String> queryFieldNames
  ) {
    String tenantId = requireTenant();
    Sort sort = requested.getSort().isSorted() ? requested.getSort() : Sort.by(
        Sort.Direction.DESC,
        defaultSort(entityType)
    );
    Pageable page = PageRequest.of(
        requested.getPageNumber(),
        Math.max(1, Math.min(requested.getPageSize(), 100)),
        sort
    );
    ProjectionQuery query = ProjectionQuery.prepare(entityType, filters, page);
    var criteriaBuilder = entityManager.getCriteriaBuilder();
    var selection = criteriaBuilder.createQuery(entityType);
    Root<E> root = selection.from(entityType);
    Map<String, Expression<?>> fields = fields(root, queryFieldNames);
    Predicate mandatory = criteriaBuilder.and(
        criteriaBuilder.equal(root.get("tenantId"), tenantId),
        criteriaBuilder.isNull(root.get("deletedAt"))
    );
    selection.select(root).where(
        mandatory,
        query.predicate(criteriaBuilder, fields)
    );
    selection.orderBy(query.orders(criteriaBuilder, fields, root.get("id")));
    List<E> content = entityManager.createQuery(selection)
        .setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize())
        .getResultList();

    var countSelection = criteriaBuilder.createQuery(Long.class);
    Root<E> countRoot = countSelection.from(entityType);
    Map<String, Expression<?>> countFields = fields(countRoot, queryFieldNames);
    countSelection.select(criteriaBuilder.count(countRoot)).where(
        criteriaBuilder.equal(countRoot.get("tenantId"), tenantId),
        criteriaBuilder.isNull(countRoot.get("deletedAt")),
        query.predicate(criteriaBuilder, countFields)
    );
    return new QueryPage<>(content, page, entityManager.createQuery(countSelection).getSingleResult());
  }

  private static <E> Map<String, Expression<?>> fields(
      final Root<E> root,
      final List<String> names
  ) {
    Map<String, Expression<?>> fields = new LinkedHashMap<>();
    names.forEach(name -> fields.put(name, root.get(name)));
    return fields;
  }

  private static <E, T> Page<T> map(
      final QueryPage<E> page,
      final Function<E, T> mapper
  ) {
    return new PageImpl<>(page.content().stream().map(mapper).toList(), page.page(), page.total());
  }

  private static String defaultSort(final Class<?> entityType) {
    return entityType == DnaRuntimeAuditEventEntity.class ? "occurredAt"
        : entityType == SchemaMigrationEntity.class ? "startedAt"
        : entityType == PhysicalModelVersionEntity.class ? "createdAt" : "updatedAt";
  }

  private static List<Action> actions(final Resource resource) {
    if (resource == Resource.AUDITS || resource == Resource.VERSIONS) {
      return List.of();
    }
    List<Action> actions = new ArrayList<>();
    actions.add(new Action(
        "view",
        "i18n:dna.runtime.action.view",
        "i18n:dna.runtime.action.view",
        "EyeOutlined",
        "blue",
        0,
        1,
        1,
        false
    ));
    if (resource == Resource.MIGRATIONS && canRetry()) {
      actions.add(new Action(
          "recover",
          "i18n:dna.runtime.action.recover",
          "i18n:dna.runtime.action.recover",
          "SafetyOutlined",
          "red",
          1,
          1,
          1,
          true
      ));
      actions.add(new Action(
          "retry",
          "i18n:dna.runtime.action.retry",
          "i18n:dna.runtime.action.retry",
          "ReloadOutlined",
          "orange",
          2,
          1,
          1,
          false
      ));
    }
    return List.copyOf(actions);
  }

  private static boolean canRetry() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return Boolean.TRUE.equals(context.getIsAdministrator())
        || context.getResources() != null
        && context.getResources().contains("dna.runtime.models.retry");
  }

  private static String requireTenant() {
    requireGovernanceScope();
    String tenant = AuthorizationContextHolder.getContext().getAttribute(TENANT_ATTRIBUTE);
    if (tenant == null || tenant.isBlank()) {
      throw new AccessDeniedException("缺少租户上下文");
    }
    return tenant.trim();
  }

  private static void requireGovernanceScope() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || context.getScopeType() != AuthorizationScopeType.PLATFORM
        && context.getScopeType() != AuthorizationScopeType.TENANT) {
      throw new AccessDeniedException("请切换到平台或组织工作空间");
    }
  }

  private static ObjectNode column(
      final ObjectNode properties,
      final String name,
      final String type,
      final int order,
      final boolean visible
  ) {
    ObjectNode property = properties.putObject(name);
    property.put("type", type);
    property.put("title", "i18n:dna.runtime.column." + name);
    property.put("x-order", order);
    property.putObject("x-ui").put("x-list-visible", visible);
    return property;
  }

  private static void modelColumns(final ObjectNode properties) {
    column(properties, "id", "string", 0, false);
    column(properties, "displayName", "string", 10, true);
    column(properties, "appId", "string", 20, true);
    column(properties, "formId", "string", 30, true);
    column(properties, "status", "string", 40, true);
    column(properties, "latestVersion", "integer", 50, true);
    column(properties, "activeVersion", "integer", 60, true);
    column(properties, "lockVersion", "integer", 70, false);
    column(properties, "updatedAt", "string", 80, true).put("format", "date-time");
  }

  private static void versionColumns(final ObjectNode properties) {
    column(properties, "id", "string", 0, false);
    column(properties, "physicalModelId", "string", 10, true);
    column(properties, "versionNumber", "integer", 20, true);
    column(properties, "contractVersion", "string", 30, true);
    column(properties, "schemaChecksum", "string", 40, true);
    column(properties, "createdAt", "string", 50, true).put("format", "date-time");
  }

  private static void migrationColumns(final ObjectNode properties) {
    column(properties, "id", "string", 0, false);
    column(properties, "physicalModelId", "string", 10, true);
    column(properties, "fromVersion", "integer", 20, true);
    column(properties, "targetVersion", "integer", 30, true);
    column(properties, "status", "string", 40, true);
    column(properties, "risk", "string", 50, true);
    column(properties, "dialectId", "string", 60, true);
    column(properties, "errorCode", "string", 70, true);
    column(properties, "expectedModelVersion", "integer", 80, false);
    column(properties, "startedAt", "string", 90, true).put("format", "date-time");
    column(properties, "finishedAt", "string", 100, true).put("format", "date-time");
  }

  private static void auditColumns(final ObjectNode properties) {
    column(properties, "id", "string", 0, false);
    column(properties, "eventType", "string", 10, true);
    column(properties, "occurredAt", "string", 20, true).put("format", "date-time");
    column(properties, "actorId", "string", 30, true);
    column(properties, "physicalModelId", "string", 40, true);
    column(properties, "appId", "string", 50, false);
    column(properties, "formId", "string", 60, false);
    column(properties, "dataSourceId", "string", 70, true);
    column(properties, "modelVersion", "integer", 80, false);
    column(properties, "affectedRows", "integer", 90, true);
    column(properties, "durationMillis", "integer", 100, true);
    column(properties, "outcome", "string", 110, true);
    column(properties, "errorCode", "string", 120, true);
  }

  /** Governance resource kinds with their schema contract source. */
  public enum Resource {
    MODELS("models", PhysicalModelEntity.class),
    VERSIONS("versions", PhysicalModelVersionEntity.class),
    MIGRATIONS("migrations", SchemaMigrationEntity.class),
    AUDITS("audits", DnaRuntimeAuditEventEntity.class);

    private final String code;
    private final Class<?> entityType;

    Resource(final String code, final Class<?> entityType) {
      this.code = code;
      this.entityType = entityType;
    }

    String code() {
      return code;
    }

    Class<?> entityType() {
      return entityType;
    }
  }

  public record Action(
      String key,
      String title,
      String text,
      String icon,
      String color,
      int sort,
      int argumentMinSize,
      int argumentMaxSize,
      boolean danger
  ) {
  }

  public record TableSchema(Map<String, Object> schema, List<Action> buttons) {
  }

  public record ModelSummary(
      String id,
      String displayName,
      String appId,
      String formId,
      String status,
      int latestVersion,
      Integer activeVersion,
      long lockVersion,
      Instant updatedAt
  ) {
  }

  public record VersionSummary(
      String id,
      String physicalModelId,
      int versionNumber,
      String contractVersion,
      String schemaChecksum,
      Instant createdAt
  ) {
  }

  public record MigrationSummary(
      String id,
      String physicalModelId,
      Integer fromVersion,
      int targetVersion,
      String status,
      String risk,
      String dialectId,
      String errorCode,
      long expectedModelVersion,
      Instant startedAt,
      Instant finishedAt
  ) {
  }

  public record AuditSummary(
      String id,
      String eventType,
      Instant occurredAt,
      String actorId,
      String physicalModelId,
      String appId,
      String formId,
      String dataSourceId,
      Integer modelVersion,
      Long affectedRows,
      long durationMillis,
      String outcome,
      String errorCode
  ) {
  }

  private record QueryPage<E>(List<E> content, Pageable page, long total) {
  }
}
