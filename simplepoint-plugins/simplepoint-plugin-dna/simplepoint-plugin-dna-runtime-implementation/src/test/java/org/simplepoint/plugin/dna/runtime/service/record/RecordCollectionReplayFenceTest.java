package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.security.access.AccessDeniedException;

class RecordCollectionReplayFenceTest {
  private final ManagedRuntimeDataSourceProvider provider =
      mock(ManagedRuntimeDataSourceProvider.class);
  private final CalciteQueryEngine engine = mock(CalciteQueryEngine.class);
  private final RecordResourceFence fence = mock(RecordResourceFence.class);
  private final RuntimeCalciteRecordBackend backend =
      new RuntimeCalciteRecordBackend(provider, engine);
  private final RecordCollectionBinding binding =
      RecordCollectionBindingTest.binding("projects", "members", "edges", "source_id", "target_id");
  private final RuntimeActor actor = new RuntimeActor("tenant", "actor");
  private final CollectionRecordCommand command =
      new CollectionRecordCommand(
          "1",
          "projects",
          new RecordSchemaReference(1, "a".repeat(64)),
          "members",
          id(1),
          0L,
          List.of(new CollectionEdgeMutation(id(2), 0L, -1L, CollectionEdgeMutation.Mode.LINK)),
          "replay",
          30_000);

  @Test
  void revocationStopsReplayBeforeOpeningPhysicalStorageWithoutRediscoveringTargets() {
    backend.setResourceFence(fence);
    doAnswer(
            invocation -> {
              assertThat((String) invocation.getArgument(0)).isEqualTo("tenant");
              List<RecordResourceGuard> guards = invocation.getArgument(1);
              assertThat(guards)
                  .containsExactly(new RecordResourceGuard("projects", "source-page", 7, false));
              throw new AccessDeniedException("Revoked source entrance");
            })
        .when(fence)
        .execute(anyString(), anyList(), anyList(), any());
    var operation = RecordOperationIdentity.of(actor, command, "f".repeat(64));
    assertThatThrownBy(
            () ->
                backend.findCollectionResult(
                    binding.source(), access(), operation, command, 30_000))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(provider, engine);
  }

  @Test
  void receiptIdentityMustMatchTheAuthorizedRootAndActor() {
    backend.setResourceFence(fence);
    for (var other :
        List.of(new RuntimeActor("tenant", "other"), new RuntimeActor("other", "actor"))) {
      var operation = RecordOperationIdentity.of(other, command, "f".repeat(64));
      assertThatThrownBy(
              () ->
                  backend.findCollectionResult(
                      binding.source(), access(), operation, command, 30_000))
          .isInstanceOf(DnaRuntimeException.class);
    }
    verifyNoInteractions(fence, provider, engine);
  }

  private RecordAccessContext access() {
    return new RecordAccessContext(
        actor,
        "source-page",
        Set.of("id"),
        Set.of(),
        Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "actor", Set.of()),
        null,
        7);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
