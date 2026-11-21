package org.simplepoint.plugin.dna.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.dna.runtime.api.event.FormStorageRemovalEvents;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.entity.FormStorageRemovalEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.simplepoint.plugin.dna.runtime.service.schema.FormPhysicalStorageRemoval;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Admits removal in the DNA store, then applies physical effects outside that transaction. */
@Component
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class FormStorageRemoval implements EventHandler {
  private static final Set<PhysicalModelStatus> AVAILABLE =
      Set.of(PhysicalModelStatus.ACTIVE, PhysicalModelStatus.DRAFT, PhysicalModelStatus.FAILED);
  private final EntityManager entities;
  private final PhysicalRelationCatalog relations;
  private final FormPhysicalStorageRemoval physical;
  private final EventPublisher events;
  private final ObjectMapper mapper;
  private final PhysicalSchemaCodec schemas;
  private final TransactionTemplate transaction;

  public FormStorageRemoval(EntityManager entities, PhysicalRelationCatalog relations,
      FormPhysicalStorageRemoval physical, EventPublisher events, ObjectMapper mapper, PhysicalSchemaCodec schemas,
      PlatformTransactionManager manager) {
    this.entities = entities;
    this.relations = relations;
    this.physical = physical;
    this.events = events;
    this.mapper = mapper;
    this.schemas = schemas;
    this.transaction = new TransactionTemplate(manager);
  }

  @Override
  public EventSubscription subscription() {
    return FormStorageRemovalEvents.STORAGE;
  }

  /** Called by the durable inbox in the DNA control database transaction. */
  @Override
  public void handle(EventMessage event) {
    var request = FormStorageRemovalEvents.selection(event);
    String tenant = event.tenantId();
    relations.write(tenant);
    var prior = entities.find(FormStorageRemovalEntity.class, event.operationId());
    if (prior != null) {
      if (!tenant.equals(prior.getTenantId()) || !event.resourceId().equals(prior.getFormId())
          || event.generation() != prior.getFormVersion()) throw blocked();
      return;
    }
    var formIdentities = new HashSet<String>();
    request.members().forEach(member -> formIdentities.addAll(member.formIdentities()));
    var models = entities.createQuery("from PhysicalModelEntity m where m.tenantId = :tenant"
            + " and m.appId = :app and m.formId in :forms and m.deletedAt is null order by m.id",
            PhysicalModelEntity.class)
        .setParameter("tenant", tenant).setParameter("app", request.applicationId())
        .setParameter("forms", formIdentities)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    if (models.stream().anyMatch(model -> !AVAILABLE.contains(model.getStatus()))) throw blocked();
    var modelsByForm = new HashMap<String, String>();
    for (var model : models) {
      if (modelsByForm.put(model.getFormId(), model.getId()) != null) throw blocked();
    }
    var parents = new HashSet<String>();
    for (var member : request.members()) {
      if (!member.parentModelId().equals(modelsByForm.get(member.formId()))) throw blocked();
      parents.add(member.parentModelId());
    }
    var ids = Set.copyOf(modelsByForm.values());
    requireOwnedChildren(tenant, request, modelsByForm, parents);
    requireDetached(tenant, ids);
    var plan = new Plan(plan(tenant, parents, ids), request.members().stream()
        .map(member -> new Completion(member.formId(), member.version(), member.operation())).toList());
    var row = new FormStorageRemovalEntity();
    row.setId(event.operationId());
    row.setTenantId(tenant);
    row.setFormId(event.resourceId());
    row.setFormVersion((int) event.generation());
    row.setNextAttemptAt(Instant.now());
    try {
      row.setPlanJson(mapper.writeValueAsString(plan));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot persist the form storage removal plan", failure);
    }
    models.forEach(model -> model.setStatus(PhysicalModelStatus.RETIRED));
    entities.persist(row);
  }

  private void requireOwnedChildren(String tenant, FormStorageRemovalEvents.Selection request,
      java.util.Map<String, String> modelsByForm, Set<String> parents) {
    var childIds = new HashSet<>(modelsByForm.values());
    childIds.removeAll(parents);
    if (childIds.isEmpty()) return;
    var definitions = entities.createQuery("select m.id as id, v.schemaJson as definition"
            + " from PhysicalModelVersionEntity v join v.physicalModel m"
            + " where v.tenantId = :tenant and m.id in :children"
            + " and v.versionNumber = m.latestVersion and v.deletedAt is null", Tuple.class)
        .setParameter("tenant", tenant).setParameter("children", childIds).getResultList();
    if (definitions.size() != childIds.size()) throw blocked();
    var schemaByModel = new HashMap<String, org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition>();
    for (var row : definitions) {
      schemaByModel.put(row.get("id", String.class), schemas.decode(row.get("definition", String.class)));
    }
    for (var member : request.members()) {
      for (String form : member.formIdentities()) {
        String model = modelsByForm.get(form);
        if (model == null || member.parentModelId().equals(model)) continue;
        var schema = schemaByModel.get(model);
        var collection = schema.collection();
        boolean detailOwned = schema.owner() != null && member.parentModelId().equals(schema.owner().parentModelId());
        boolean collectionOwned = collection != null && schema.references().stream().anyMatch(reference ->
            reference.relationId().equals(collection.sourceReferenceId())
                && member.parentModelId().equals(reference.targetModelId()));
        if (!detailOwned && !collectionOwned) throw blocked();
      }
    }
  }

  private void requireDetached(String tenant, Set<String> ids) {
    int dependencies = entities.createQuery("select r.id from PhysicalRelationDefinitionEntity r"
            + " where r.tenantId = :tenant and r.targetModelId in :ids and r.sourceModelId not in :ids", String.class)
        .setParameter("tenant", tenant).setParameter("ids", ids).setMaxResults(1).getResultList().size();
    int activeResources = entities.createQuery("select r.id from PhysicalModelResourceBindingEntity r"
            + " where r.tenantId = :tenant and r.physicalModelId in :ids and r.deletedAt is null", String.class)
        .setParameter("tenant", tenant).setParameter("ids", ids).setMaxResults(1).getResultList().size();
    int activeProjections = entities.createQuery("select p.id from PhysicalResourceProjectionEntity p"
            + " where p.tenantId = :tenant and p.physicalModelId in :ids and not p.formallyOffline", String.class)
        .setParameter("tenant", tenant).setParameter("ids", ids).setMaxResults(1).getResultList().size();
    int guards = entities.createQuery("select g.id from PhysicalRelationGuardEntity g"
            + " where g.tenantId = :tenant and g.modelId in :ids and not g.released", String.class)
        .setParameter("tenant", tenant).setParameter("ids", ids).setMaxResults(1).getResultList().size();
    if (dependencies + activeResources + activeProjections + guards != 0) throw blocked();
  }

  private List<FormPhysicalStorageRemoval.Plan> plan(String tenant, Set<String> roots, Set<String> ids) {
    var bindings = entities.createQuery("from PhysicalModelBindingEntity b"
            + " where b.tenantId = :tenant and b.physicalModelId in :ids and b.deletedAt is null order by b.physicalModelId",
            PhysicalModelBindingEntity.class)
        .setParameter("tenant", tenant).setParameter("ids", ids).getResultList();
    if (bindings.size() != ids.size()) throw blocked();
    var groups = new TreeMap<String, List<PhysicalModelBindingEntity>>();
    for (var binding : bindings) {
      if (binding.getPhysicalNamespaceId() == null) throw blocked();
      groups.computeIfAbsent(binding.getPhysicalNamespaceId(), ignored -> new java.util.ArrayList<>()).add(binding);
    }
    return groups.entrySet().stream().map(group -> {
      var first = group.getValue().getFirst();
      if (group.getValue().stream().anyMatch(binding ->
          !first.getCatalogName().equals(binding.getCatalogName())
              || !first.getSchemaName().equals(binding.getSchemaName()))) throw blocked();
      var tables = group.getValue().stream()
          .sorted(Comparator.comparing((PhysicalModelBindingEntity b) -> roots.contains(b.getPhysicalModelId()))
              .thenComparing(PhysicalModelBindingEntity::getPhysicalModelId))
          .map(binding -> new FormPhysicalStorageRemoval.Table(binding.getPhysicalModelId(), binding.getTableName()))
          .toList();
      return new FormPhysicalStorageRemoval.Plan(first.getDataSourceId(), first.getCatalogName(),
          first.getSchemaName(), UUID.fromString(group.getKey()), tables);
    }).toList();
  }

  /** A lease covers a single target operation, with exact replay on timeout or restart. */
  @Scheduled(fixedDelayString = "${simplepoint.dna.form-storage-removal-delay:5s}")
  public void run() {
    var work = transaction.execute(status -> claim());
    if (work == null) return;
    try {
      for (var namespace : work.plan().namespaces()) physical.execute(work.tenant(), namespace);
      transaction.executeWithoutResult(status -> complete(work));
    } catch (java.sql.SQLException | RuntimeException failure) {
      transaction.executeWithoutResult(status -> retry(work));
    }
  }

  private Work claim() {
    Instant now = Instant.now();
    var rows = entities.createQuery("from FormStorageRemovalEntity r where r.completedAt is null"
            + " and r.nextAttemptAt <= :now and (r.leaseUntil is null or r.leaseUntil <= :now)"
            + " order by r.nextAttemptAt, r.id", FormStorageRemovalEntity.class)
        .setParameter("now", now).setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (row.getCompletedAt() != null || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) return null;
    String token = UUID.randomUUID().toString();
    row.setExecutionToken(token);
    row.setLeaseUntil(now.plusSeconds(120));
    row.setAttempts(row.getAttempts() + 1);
    try {
      return new Work(row.getId(), row.getTenantId(), token,
          mapper.readValue(row.getPlanJson(), Plan.class));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot read the immutable form storage removal plan", failure);
    }
  }

  private void complete(Work work) {
    relations.write(work.tenant());
    var row = current(work);
    if (row == null) return;
    var ids = work.plan().namespaces().stream().flatMap(namespace -> namespace.tables().stream())
        .map(FormPhysicalStorageRemoval.Table::model).toList();
    var tenant = work.tenant();
    requireDetached(tenant, Set.copyOf(ids));
    execute("delete from PhysicalRelationDefinitionEntity r where r.tenantId = :tenant"
        + " and r.sourceModelId in :ids", tenant, ids);
    execute("delete from PhysicalRelationGuardEntity r where r.tenantId = :tenant"
        + " and (r.modelId in :ids or r.migrationId in (select m.id from SchemaMigrationEntity m"
        + " where m.tenantId = :tenant and m.physicalModelId in :ids))", tenant, ids);
    execute("delete from SchemaMigrationStepEntity r where r.tenantId = :tenant and r.migrationId in"
        + " (select m.id from SchemaMigrationEntity m where m.tenantId = :tenant and m.physicalModelId in :ids)", tenant, ids);
    for (String entity : List.of("DnaRuntimeAuditEventEntity", "SchemaMigrationEntity", "PhysicalModelResourceBindingEntity",
        "RecordCreateReceiptEntity", "PhysicalResourceProjectionEntity", "PhysicalModelVersionEntity", "PhysicalModelBindingEntity")) {
      execute("delete from " + entity + " r where r.tenantId = :tenant and r.physicalModelId in :ids", tenant, ids);
    }
    execute("delete from PhysicalModelEntity r where r.tenantId = :tenant and r.id in :ids", tenant, ids);
    row.setCompletedAt(Instant.now());
    row.setPlanJson(null);
    row.setExecutionToken(null);
    row.setLeaseUntil(null);
    for (var member : work.plan().completions()) {
      events.publish(FormStorageRemovalEvents.completed(tenant, member.formId(), member.version(),
          member.operation().toString()));
    }
  }

  private void execute(String hql, String tenant, List<String> models) {
    entities.createQuery(hql).setParameter("tenant", tenant).setParameter("ids", models).executeUpdate();
  }

  private void retry(Work work) {
    var row = current(work);
    if (row == null) return;
    row.setExecutionToken(null);
    row.setLeaseUntil(null);
    row.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 5L << Math.min(6, row.getAttempts()))));
  }

  private FormStorageRemovalEntity current(Work work) {
    var row = entities.find(FormStorageRemovalEntity.class, work.id(), LockModeType.PESSIMISTIC_WRITE);
    return row != null && tenantMatches(work, row) && work.token().equals(row.getExecutionToken()) ? row : null;
  }

  private static boolean tenantMatches(Work work, FormStorageRemovalEntity row) {
    return work.tenant().equals(row.getTenantId());
  }

  private static IllegalStateException blocked() {
    return new IllegalStateException("Form storage has a live binding, a retained dependency or incompatible ownership");
  }

  private record Work(String id, String tenant, String token, Plan plan) {}
  private record Plan(List<FormPhysicalStorageRemoval.Plan> namespaces, List<Completion> completions) {}
  private record Completion(String formId, int version, UUID operation) {}
}
