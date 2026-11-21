package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Mock control metadata admission; native coordinator/transaction tests remain separate. */
class RecordCollectionPublicationMetadataTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "publisher");
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());

  @Test
  void freezesOnlyEdgeStorageWithoutAddingTargetsOrEdgesToFormulaBackfill() {
    var f = new Fixture();
    var snapshot = f.resolve(f.rule(null, null, null));
    assertThat(snapshot.models()).containsOnlyKeys("projects");
    assertThat(snapshot.bindings()).containsOnlyKeys("projects");
    assertThat(snapshot.resources()).containsOnlyKeys("projects");
    assertThat(snapshot.collectionBindings()).containsOnlyKeys("members");
    assertThat(snapshot.collectionBindings().get("members").target())
        .isEqualTo(f.binding.edge().target().metadataTarget());
    verify(f.entrance).setParameter("resource", "member-page");
  }

  @Test
  void requiresCompleteSameTenantLocksBeforeResolvingAnyStorage() {
    var f = new Fixture();
    var command = f.command(f.rule(null, null, null));
    assertThatThrownBy(
            () -> f.metadata.resolve(ACTOR, command, Map.of("projects", f.locked.get("projects"))))
        .isInstanceOf(DnaRuntimeException.class);
    verify(f.resolver, never()).resolve(any(), anyString(), any());
    f.locked.get("membership").setTenantId("other");
    assertThatThrownBy(() -> f.resolve(f.rule(null, null, null)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsUndeclaredEligibilityMutabilityAndLabelDependencies() {
    var f = new Fixture();
    var condition =
        new RecordValidationCondition(
            RecordValueConditionOperator.IS_EMPTY,
            new RecordValidationField("parent", "status"),
            List.of(),
            List.of());
    for (var rule :
        List.of(
            f.rule(condition, null, null),
            f.rule(null, condition, null),
            f.rule(null, null, "label"))) {
      assertThatThrownBy(() -> f.resolve(rule)).isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void requiresTheExactLiveManagedTargetEntrance() {
    var f = new Fixture();
    for (var rows :
        List.of(
            List.<Object[]>of(),
            List.<Object[]>of(
                new Object[] {"binding", "member-page", "projection", null, 0L, false, null}),
            List.<Object[]>of(
                new Object[] {
                  "binding", "member-page", "projection", "app:target", 2L, true, "members"
                }),
            List.<Object[]>of(
                new Object[] {
                  "binding", "member-page", "projection", "app:target", 2L, false, "foreign"
                }))) {
      when(f.entrance.getResultList()).thenReturn(rows);
      assertThatThrownBy(() -> f.resolve(f.rule(null, null, null)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void cancellationDoesNotLoadUnavailableEdgesTargetsOrFailedDependencies() {
    var f = new Fixture();
    var command = f.command(f.rule(null, null, "missing-label"));
    var snapshot =
        f.metadata.resolve(ACTOR, command, Map.of("projects", f.locked.get("projects")), false);
    assertThat(snapshot.collectionBindings()).isEmpty();
    assertThat(snapshot.models()).containsOnlyKeys("projects");
    verify(f.resolver, never()).resolve(eq(ACTOR), eq("membership"), any());
    verify(f.resolver, never()).resolveCurrent(any(), anyString());
    verifyNoInteractions(f.entrance);
  }

  private static final class Fixture {
    final EntityManager em = mock(EntityManager.class);
    final RuntimeRecordModelResolver resolver = mock(RuntimeRecordModelResolver.class);
    final RecordPublicationMetadata metadata = new RecordPublicationMetadata(em, resolver, CODEC);
    final RecordCollectionBinding binding =
        RecordCollectionBindingTest.binding(
            "projects", "members", "edges", "source_id", "target_id");
    final Map<String, PhysicalModelEntity> locked = new LinkedHashMap<>();
    final TypedQuery<Object[]> entrance = query();

    Fixture() {
      when(em.isJoinedToTransaction()).thenReturn(true);
      for (var model : List.of(binding.source(), binding.target(), binding.edge())) {
        var row = new PhysicalModelEntity();
        row.setId(model.physicalModelId());
        row.setTenantId(ACTOR.tenantId());
        row.setStatus(PhysicalModelStatus.ACTIVE);
        row.setActiveVersion(1);
        locked.put(row.getId(), row);
        when(em.contains(row)).thenReturn(true);
        when(em.getLockMode(row)).thenReturn(LockModeType.PESSIMISTIC_WRITE);
        when(resolver.resolve(eq(ACTOR), eq(row.getId()), any())).thenReturn(model);
        when(resolver.resolveCurrent(ACTOR, row.getId())).thenReturn(model);
      }
      var ownResources = query();
      when(ownResources.getResultList()).thenReturn(List.of());
      when(entrance.getResultList())
          .thenReturn(
              List.<Object[]>of(
                  new Object[] {
                    "binding", "member-page", "projection", "app:target", 2L, false, "members"
                  }));
      when(em.createQuery(anyString(), eq(Object[].class)))
          .thenAnswer(
              call ->
                  call.getArgument(0, String.class).contains("b.resourceCode = :resource")
                      ? entrance
                      : ownResources);
    }

    RecordCollectionRule rule(
        RecordValidationCondition eligibility, RecordValidationCondition mutable, String label) {
      return new RecordCollectionRule(
          "1",
          "members",
          "membership",
          reference(binding.edge()),
          "members",
          "app",
          "form",
          "member-page",
          eligibility,
          mutable,
          label);
    }

    RecordWritePolicyPublicationCommand command(RecordCollectionRule rule) {
      return new RecordWritePolicyPublicationCommand(
          "projects",
          "app",
          "form",
          reference(binding.source()),
          0,
          1,
          "publish",
          "a".repeat(64),
          Map.of(),
          30_000,
          Map.of(),
          new RecordCalculationDefinition(
              "1", Map.of(), Map.of(), null, null, null, null, null, List.of(rule)));
    }

    RecordPublicationMetadata.Snapshot resolve(RecordCollectionRule rule) {
      return metadata.resolve(ACTOR, command(rule), locked);
    }
  }

  private static RecordSchemaReference reference(RuntimeRecordModel model) {
    return new RecordSchemaReference(
        model.activeVersion(), CODEC.encode(model.activeSchema()).checksum());
  }

  @SuppressWarnings("unchecked")
  private static TypedQuery<Object[]> query() {
    var query = (TypedQuery<Object[]>) mock(TypedQuery.class);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.setMaxResults(anyInt())).thenReturn(query);
    return query;
  }
}
