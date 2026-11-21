package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.*;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Metadata admission only; the database coordinator tests separately exercise actual locks/commit.
 */
class RecordReferencePublicationMetadataTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "publisher");
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());

  @Test
  void acceptsStableFieldsAfterRenameWithoutMakingTheTargetAnOwnedBackfillParticipant() {
    var f = new Fixture();
    var result =
        f.resolve(
            condition(
                "status", RecordValueConditionOperator.EQUALS, LogicalValueKind.STRING, "ACTIVE"));
    assertThat(result.models()).containsOnlyKeys("source");
    assertThat(result.bindings()).containsOnlyKeys("source");
    assertThat(result.resources()).containsOnlyKeys("source");
    verify(f.targetResources).setParameter("resource", "customer-page");
    verify(f.resolver).resolveCurrent(ACTOR, "target");
  }

  @Test
  void directGovernanceCannotInstallUndeclaredOrRetypedTargetFieldDependencies() {
    var condition =
        condition("status", RecordValueConditionOperator.EQUALS, LogicalValueKind.STRING, "ACTIVE");
    var nested =
        new RecordValidationCondition(
            RecordValueConditionOperator.AND, null, List.of(), List.of(condition));
    var missing = new Fixture(List.of());
    incompatible(() -> missing.resolve(nested));
    var wrongType =
        new Fixture(List.of(new LogicalReferenceField("status", LogicalFieldType.BOOLEAN)));
    incompatible(() -> wrongType.resolve(nested));
    assertThat(new Fixture().resolve(nested).models()).containsOnlyKeys("source");
    // Recovery must remain possible even if the rejected candidate omitted dependency declarations.
    var command = missing.command(nested);
    assertThat(
            missing
                .metadata
                .resolve(ACTOR, command, Map.of("source", missing.locked.get("source")), false)
                .models())
        .containsOnlyKeys("source");
  }

  @Test
  void requiresTheCompleteSameTenantLockedParticipantSetBeforeReadingTargetMetadata() {
    var f = new Fixture();
    var command = f.command(null);
    incompatible(
        () -> f.metadata.resolve(ACTOR, command, Map.of("source", f.locked.get("source"))));
    verify(f.resolver, never()).resolveCurrent(any(), anyString());
    f.locked.get("target").setTenantId("other-tenant");
    incompatible(() -> f.resolve(null));
    f.locked.get("target").setTenantId("tenant");
    when(f.em.getLockMode(f.locked.get("target"))).thenReturn(LockModeType.NONE);
    incompatible(() -> f.resolve(null));
  }

  @Test
  void rejectsUnknownStorageNamesWrongTypesAndUnsupportedComparisonBeforeInstallation() {
    var f = new Fixture();
    for (var invalid :
        List.of(
            condition(
                "current_status",
                RecordValueConditionOperator.EQUALS,
                LogicalValueKind.STRING,
                "ACTIVE"),
            condition("status", RecordValueConditionOperator.EQUALS, LogicalValueKind.INTEGER, "1"),
            condition(
                "status",
                RecordValueConditionOperator.GREATER_THAN,
                LogicalValueKind.STRING,
                "ACTIVE"))) {
      incompatible(() -> f.resolve(invalid));
    }
  }

  @Test
  void rejectsMissingLegacyOfflineAndRetargetedEntrancesWithoutLookingForAnotherPage() {
    var f = new Fixture();
    when(f.targetResources.getResultList()).thenReturn(List.of());
    incompatible(() -> f.resolve(null));
    when(f.targetResources.getResultList())
        .thenReturn(
            List.<Object[]>of(
                new Object[] {"binding", "customer-page", "projection", null, 0L, false, null}));
    incompatible(() -> f.resolve(null));
    when(f.targetResources.getResultList())
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  "binding", "customer-page", "projection", "app:target", 3L, true, "target"
                }));
    assertThatThrownBy(() -> f.resolve(null))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED));
    when(f.targetResources.getResultList())
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  "binding", "customer-page", "projection", "app:target", 3L, false, "another-model"
                }));
    assertThatThrownBy(() -> f.resolve(null))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED));
  }

  @Test
  void targetOwnerAndStorageMustStillMatchTheDeclaredIdentity() {
    var f = new Fixture();
    when(f.resolver.resolveCurrent(ACTOR, "target"))
        .thenReturn(
            new RuntimeRecordModel(
                "target", "wrong-app", "customer", 1, f.target.activeSchema(), f.target.target()));
    incompatible(() -> f.resolve(null));
    when(f.resolver.resolveCurrent(ACTOR, "target"))
        .thenReturn(
            new RuntimeRecordModel(
                "target",
                "target-app",
                "customer",
                1,
                f.target.activeSchema(),
                new PhysicalSchemaTarget(
                    "target",
                    new RuntimeMetadataTarget("other-store", null, "public", "customers"))));
    incompatible(() -> f.resolve(null));
  }

  @Test
  void cancellationDoesNotRequireUnavailableTargetsOrValidateTheFailedCandidateAgain() {
    var f = new Fixture();
    var command =
        f.command(
            condition(
                "removed", RecordValueConditionOperator.EQUALS, LogicalValueKind.STRING, "ACTIVE"));
    var result =
        f.metadata.resolve(ACTOR, command, Map.of("source", f.locked.get("source")), false);
    assertThat(result.models()).containsOnlyKeys("source");
    verify(f.resolver, never()).resolveCurrent(any(), anyString());
    verifyNoInteractions(f.targetResources);
  }

  private static void incompatible(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
  }

  private static RecordValidationCondition condition(
      String field, RecordValueConditionOperator operator, LogicalValueKind kind, String literal) {
    return new RecordValidationCondition(
        operator,
        new RecordValidationField("parent", field),
        List.of(new LogicalValue(kind, literal)),
        List.of());
  }

  private static class Fixture {
    final EntityManager em = mock(EntityManager.class);
    final RuntimeRecordModelResolver resolver = mock(RuntimeRecordModelResolver.class);
    final RecordPublicationMetadata metadata = new RecordPublicationMetadata(em, resolver, CODEC);
    final Map<String, PhysicalModelEntity> locked = new LinkedHashMap<>();
    final TypedQuery<Object[]> targetResources = query();
    final RuntimeRecordModel source;
    final RuntimeRecordModel target;

    Fixture() {
      this(List.of(new LogicalReferenceField("status", LogicalFieldType.STRING)));
    }

    Fixture(List<LogicalReferenceField> dependencies) {
      var targetPlacement = new RuntimeMetadataTarget("store", null, "public", "customers");
      var reference =
          new LogicalFieldDefinition(
              "customer",
              "customer_id",
              "Customer",
              LogicalFieldType.UUID,
              true,
              null,
              null,
              null,
              null);
      var status =
          new LogicalFieldDefinition(
              "status",
              "current_status",
              "Status",
              LogicalFieldType.STRING,
              true,
              32,
              null,
              null,
              null);
      source =
          new RuntimeRecordModel(
              "source",
              "source-app",
              "trade",
              1,
              new PhysicalSchemaDefinition(
                  DnaRuntimeApiVersion.CURRENT,
                  List.of(reference),
                  List.of(),
                  null,
                  true,
                  List.of(
                      new LogicalReferenceDefinition(
                          "customer", "customer", "target", false, dependencies))),
              new PhysicalSchemaTarget(
                  "source",
                  new RuntimeMetadataTarget("store", null, "public", "trades"),
                  Map.of("target", targetPlacement)));
      target =
          new RuntimeRecordModel(
              "target",
              "target-app",
              "customer",
              1,
              new PhysicalSchemaDefinition(
                  DnaRuntimeApiVersion.CURRENT, List.of(status), List.of(), null, true),
              new PhysicalSchemaTarget("target", targetPlacement));
      when(em.isJoinedToTransaction()).thenReturn(true);
      for (String id : List.of("source", "target")) {
        var row = new PhysicalModelEntity();
        row.setId(id);
        row.setTenantId("tenant");
        row.setStatus(PhysicalModelStatus.ACTIVE);
        row.setActiveVersion(1);
        locked.put(id, row);
        when(em.contains(row)).thenReturn(true);
        when(em.getLockMode(row)).thenReturn(LockModeType.PESSIMISTIC_WRITE);
      }
      when(resolver.resolve(eq(ACTOR), eq("source"), any())).thenReturn(source);
      when(resolver.resolveCurrent(ACTOR, "target")).thenReturn(target);
      var sourceResources = query();
      when(sourceResources.getResultList()).thenReturn(List.of());
      when(targetResources.getResultList())
          .thenReturn(
              List.<Object[]>of(
                  new Object[] {
                    "binding", "customer-page", "projection", "app:target", 3L, false, "target"
                  }));
      when(em.createQuery(anyString(), eq(Object[].class)))
          .thenAnswer(
              invocation ->
                  invocation.getArgument(0, String.class).contains("b.resourceCode = :resource")
                      ? targetResources
                      : sourceResources);
    }

    RecordPublicationMetadata.Snapshot resolve(RecordValidationCondition condition) {
      return metadata.resolve(ACTOR, command(condition), locked);
    }

    RecordWritePolicyPublicationCommand command(RecordValidationCondition condition) {
      var rule =
          new RecordReferenceRule(
              "1",
              "customer",
              new RecordValidationField("parent", "customer"),
              "target",
              "target-app",
              "customer",
              "customer-page",
              condition,
              null);
      return new RecordWritePolicyPublicationCommand(
          "source",
          "source-app",
          "trade",
          new RecordSchemaReference(1, CODEC.encode(source.activeSchema()).checksum()),
          0,
          1,
          "publish",
          "a".repeat(64),
          Map.of(),
          30_000,
          Map.of(),
          new RecordCalculationDefinition(
              "1", Map.of(), Map.of(), null, null, null, null, List.of(rule)));
    }

    @SuppressWarnings("unchecked")
    private static TypedQuery<Object[]> query() {
      var query = (TypedQuery<Object[]>) mock(TypedQuery.class);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      return query;
    }
  }
}
