package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRebindMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class FormCollectionRebindAuthorizationTest {
  @Test
  void deniesTheMigrationWhenAnyMappedRecordIsOutsideItsOwnPageScope() {
    var targetResolver = mock(FormRelationTargetResolver.class);
    var contexts = mock(DnaInvocationContextFactory.class);
    var dna = mock(DnaFormRuntimeService.class);
    var authorization = new FormCollectionRebindAuthorization(targetResolver, contexts, dna);
    var old = new RecordSnapshotTarget("old-model", "old-app", "old-form", "old-page");
    var next = new RecordSnapshotTarget("new-model", "new-app", "new-form", "new-page");
    var oldForm = mock(PublishedApplicationForm.class);
    var newForm = mock(PublishedApplicationForm.class);
    when(targetResolver.requireExactEntrance("tenant", old)).thenReturn(oldForm);
    when(targetResolver.requireExactEntrance("tenant", next)).thenReturn(newForm);
    var sourcePolicy = mock(DnaInvocationContext.class);
    var targetPolicy = mock(DnaInvocationContext.class);
    when(contexts.forCollectionMigrationRead(oldForm)).thenReturn(sourcePolicy);
    when(contexts.forCollectionMigrationRead(newForm)).thenReturn(targetPolicy);
    var schema = new RecordSchemaReference(1, "a".repeat(64));
    when(oldForm.physicalModelId()).thenReturn(old.modelId());
    when(newForm.physicalModelId()).thenReturn(next.modelId());
    for (var form : List.of(oldForm, newForm)) {
      when(form.physicalModelVersion()).thenReturn(schema.version());
      when(form.storageChecksum()).thenReturn(schema.checksum());
    }
    String former = "6e4e1b4b-06f6-4ed3-b644-16a201291484";
    String replacement = "fa8b1575-f543-4ef2-81ac-85a4ad2b971a";
    var access =
        new FormCollectionRebinding.Access(
            new RecordCollectionRebindMigration(
                "former", "replacement", Map.of(former, replacement)),
            old,
            next);
    when(dna.countRecords(sourcePolicy, count(old.modelId(), schema, former))).thenReturn(1L);
    when(dna.countRecords(targetPolicy, count(next.modelId(), schema, replacement)))
        .thenReturn(0L)
        .thenThrow(
            new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, "Filtered identity"))
        .thenReturn(1L);
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> authorization.requireAccess("tenant", access))
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              ex ->
                  assertThat(ex.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    }
    authorization.requireAccess("tenant", access);
  }

  private static RecordCountQuery count(String model, RecordSchemaReference schema, String id) {
    return new RecordCountQuery(
        model, schema, new RecordFilter(RecordFilterOperator.IN, "id", List.of(id), List.of()));
  }
}
