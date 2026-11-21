package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

class FormRecordReadAuthorityTest {
  @Test
  void originalEntrancesAreResolvedIndependentlyOfTheCurrentRelationDefinition() {
    var f = new Fixture();
    when(f.relations.snapshotTargets(f.form, List.of(f.target))).thenReturn(List.of(f.original));
    when(f.contexts.forRelations(f.form, Set.of(f.original))).thenReturn(f.resolved);
    assertThat(
            f.authority.current(f.form, f.bootstrap, f.query, FormRecordReadAuthority.deadline()))
        .isSameAs(f.resolved);
    verify(f.relations, never()).availableForWrite(any());
    verify(f.relations).snapshotTargets(f.form, List.of(f.target));
    var captured = ArgumentCaptor.forClass(RecordCurrentTargetsQuery.class);
    verify(f.dna).currentTargets(eq(f.bootstrap), captured.capture());
    assertThat(captured.getValue().page()).isSameAs(f.page);
    assertThat(captured.getValue().timeoutMs()).isBetween(1, 30000);
  }

  @Test
  void revokedOriginalsStayAbsentWhileInverseQueriesRetainTheirCurrentAnchor() {
    var f = new Fixture();
    when(f.relations.snapshotTargets(f.form, List.of(f.target))).thenReturn(List.of());
    var anchor = mock(PublishedApplicationForm.class);
    when(f.relations.resolve(f.form, "relation"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(null, anchor));
    when(f.contexts.forRelations(f.form, Set.of(anchor))).thenReturn(f.resolved);
    var query =
        new RecordCurrentTargetsQuery(
            "1",
            null,
            null,
            new RelatedRecordPageQuery(
                f.page, "relation", "anchor", "11111111-1111-1111-1111-111111111111"),
            30000);
    assertThat(f.authority.current(f.form, f.bootstrap, query, FormRecordReadAuthority.deadline()))
        .isSameAs(f.resolved);
    verify(f.contexts, never()).forRelations(f.form, Set.of(f.original));
    verify(f.contexts, never()).forResource(any());
  }

  @Test
  void malformedEvidenceOrExpiredBudgetCannotFallBackToSourceAuthority() {
    var f = new Fixture();
    when(f.dna.currentTargets(any(), any())).thenReturn(List.of(f.target, f.target));
    assertThatThrownBy(
            () ->
                f.authority.current(
                    f.form, f.bootstrap, f.query, FormRecordReadAuthority.deadline()))
        .isInstanceOf(ApplicationFormException.class);
    verify(f.relations, never()).snapshotTargets(any(), any());
    verify(f.contexts, never()).forRelations(any(), any());
    var expired = new Fixture();
    assertThatThrownBy(
            () ->
                expired.authority.current(
                    expired.form, expired.bootstrap, expired.query, System.nanoTime() - 1))
        .isInstanceOf(ApplicationFormException.class);
    verify(expired.dna, never()).currentTargets(any(), any());
  }

  private static final class Fixture {
    final DnaOwnedRecordService dna = mock(DnaOwnedRecordService.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm original = mock(PublishedApplicationForm.class);
    final DnaInvocationContext bootstrap = mock(DnaInvocationContext.class);
    final DnaInvocationContext resolved = mock(DnaInvocationContext.class);
    final RecordSnapshotTarget target =
        new RecordSnapshotTarget("old-model", "app", "form", "old-resource");
    final RecordPageQuery page =
        new RecordPageQuery(
            "model",
            new RecordSchemaReference(1, "a".repeat(64)),
            0,
            50,
            List.of("copy"),
            null,
            List.of());
    final RecordCurrentTargetsQuery query =
        new RecordCurrentTargetsQuery("1", null, page, null, 30000);
    final FormRecordReadAuthority authority = new FormRecordReadAuthority(dna, contexts, relations);

    Fixture() {
      when(dna.currentTargets(eq(bootstrap), any())).thenReturn(List.of(target));
    }
  }
}
