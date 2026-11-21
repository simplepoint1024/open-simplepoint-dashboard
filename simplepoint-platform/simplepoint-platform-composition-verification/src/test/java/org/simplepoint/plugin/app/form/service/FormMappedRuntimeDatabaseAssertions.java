package org.simplepoint.plugin.app.form.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingConfirmation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler.CompiledStorageProjection;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

/**
 * APP service wiring in its owning test package. Only identity/publication discovery is stubbed.
 */
public final class FormMappedRuntimeDatabaseAssertions {
  private FormMappedRuntimeDatabaseAssertions() {}

  /** Uses real APP validation/observation/commands over the guarded native DNA services. */
  public static void verify(
      DnaFormRuntimeService dna,
      DnaOwnedRecordService owned,
      ObjectMapper mapper,
      ApplicationFormDocument document,
      ApplicationFormDocument targetDocument,
      PreparedDnaSchema source,
      PreparedDnaSchema target,
      CompiledStorageProjection storage,
      CompiledStorageProjection targetStorage,
      String details,
      List<FormRelationBinding> bindings)
      throws Exception {
    var targetSchema =
        new RecordSchemaReference(target.physicalModelVersion(), targetStorage.checksum());
    final var customer =
        dna.createRecord(
                context("customers", false),
                new RecordCreateCommand(
                    target.physicalModelId(),
                    targetSchema,
                    "mapped-customer",
                    Map.of(
                        "record_state",
                        "DRAFT",
                        "stored_name",
                        "客户・日本語😀",
                        "stored_price",
                        "9007199254740993.12")))
            .recordId();
    final var product =
        dna.createRecord(
                context("customers", false),
                new RecordCreateCommand(
                    target.physicalModelId(),
                    targetSchema,
                    "mapped-product",
                    Map.of(
                        "record_state", "DRAFT", "stored_name", "商品・東京", "stored_price", "7.25")))
            .recordId();
    var form =
        new PublishedApplicationForm(
            "tenant-a",
            "trade-app",
            "trade-page",
            "Trades",
            "trade",
            "Trade",
            1,
            "trades",
            source.physicalModelId(),
            source.physicalModelVersion(),
            storage.checksum(),
            document,
            1,
            1,
            mapper.readValue(details, new TypeReference<List<FormDetailBinding>>() {}),
            null,
            bindings);
    var targetForm =
        new PublishedApplicationForm(
            "tenant-a",
            "customer-app",
            "customer-page",
            "Customers",
            "customer",
            "Customer",
            1,
            "customers",
            target.physicalModelId(),
            target.physicalModelVersion(),
            targetStorage.checksum(),
            targetDocument,
            1,
            0);
    var resolver = mock(PublishedApplicationFormResolver.class);
    when(resolver.resolve("trade-app", "trade-page")).thenReturn(form);
    var contexts = mock(DnaInvocationContextFactory.class);
    var targetsAllowed = new AtomicBoolean(true);
    when(contexts.forForm(form)).thenAnswer(call -> context("trades", targetsAllowed.get()));
    when(contexts.forWrite(form)).thenAnswer(call -> context("trades", targetsAllowed.get()));
    when(contexts.forResourceValidation("trades"))
        .thenAnswer(call -> context("trades", targetsAllowed.get()));
    when(contexts.forRelatedRead(form, targetForm))
        .thenAnswer(
            call -> {
              if (!targetsAllowed.get()) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "Target denied");
              }
              return context("customers", false);
            });
    when(contexts.forRelations(eq(form), any()))
        .thenAnswer(call -> context("trades", targetsAllowed.get()));
    when(contexts.forRelations(eq(targetForm), any())).thenReturn(context("customers", false));
    var relations = mock(FormRelationTargetResolver.class);
    for (var relation : document.relations()) {
      when(relations.resolve(form, relation.relationId()))
          .thenReturn(new FormRelationTargetResolver.ResolvedRelation(relation, targetForm));
    }
    when(relations.availableForWrite(form)).thenReturn(List.of(targetForm));
    when(relations.snapshotTargets(eq(form), any())).thenReturn(List.of(targetForm));
    when(relations.snapshotTargets(eq(targetForm), any())).thenReturn(List.of());
    var reads = new FormRecordReadAuthority(owned, contexts, relations);
    var authority = new FormReferenceMappingAuthority(contexts, relations, reads);
    var payloads = new FormRecordPayloadCompiler();
    var invariants = mock(FormRecordInvariantService.class);
    when(invariants.resolve(form))
        .thenReturn(
            new FormRecordInvariantService.Plan(
                document, source.physicalModelVersion(), storage.checksum(), List.of(document)));
    var labels = mock(FormRecordIdentityLabels.class);
    when(labels.decorate(anyString(), any(ApplicationFormRecordView.class)))
        .thenAnswer(call -> call.getArgument(1));
    var records =
        new ApplicationFormRecordServiceImpl(
            resolver,
            payloads,
            dna,
            contexts,
            new FormRecordInvariantValidator(invariants, payloads, dna),
            new FormDetailRecordCoordinator(
                owned, payloads, mock(FormAttachmentReferenceValidator.class)),
            labels,
            reads,
            authority);
    var observer =
        new ApplicationFormReferenceMappingServiceImpl(resolver, records, owned, authority);
    var live =
        new ApplicationFormReferenceLiveServiceImpl(resolver, relations, contexts, reads, dna);
    var runtime = new ApplicationFormRuntimeIdentity("trade", 1);
    assertEquals(
        "客户・日本語😀",
        live.read(
                "trade-app",
                "trade-page",
                "customer",
                new FormReferenceLiveQuery(runtime, customer))
            .values()
            .get("live")
            .value());
    assertEquals(
        "商品・東京",
        live.read(
                "trade-app", "trade-page", "product", new FormReferenceLiveQuery(runtime, product))
            .values()
            .get("live")
            .value());
    var root =
        observer.observe(
            "trade-app",
            "trade-page",
            "customer",
            new FormReferenceMappingQuery(runtime, null, null, customer, List.of()));
    var line =
        observer.observe(
            "trade-app",
            "trade-page",
            "product",
            new FormReferenceMappingQuery(
                runtime, null, null, product, List.of("price"), null, true));
    assertEquals("客户・日本語😀", root.snapshots().get("copied").value());
    assertEquals("7.25", line.defaults().get("amount").value());
    var mappings =
        new FormReferenceMappingIntent(
            "1",
            List.of(
                new FormReferenceMappingConfirmation(
                    "customer", null, null, null, root.observation(), List.of()),
                new FormReferenceMappingConfirmation(
                    "product", "lines", 0, null, line.observation(), List.of("price"))));
    Map<String, Object> values =
        Map.of(
            "reference",
            customer,
            "amount",
            "1.25",
            "lines",
            List.of(Map.of("values", Map.of("reference", product, "amount", "7.25"))));
    var preview =
        records.preview(
            "trade-app",
            "trade-page",
            new PreviewApplicationFormRecordCommand(
                runtime, null, null, FormRecordState.SUBMITTED, values, mappings));
    assertEquals("客户・日本語😀", preview.values().get("copied"));
    assertEquals("商品・東京", preview.details().get("lines").getFirst().get("copied"));
    var command =
        new CreateApplicationFormRecordCommand(
            runtime, "mapped-trade", FormRecordState.SUBMITTED, values, mappings);
    var saved = records.create("trade-app", "trade-page", command);
    assertNotNull(saved.currentRecord());
    assertEquals("客户・日本語😀", saved.currentRecord().values().get("copied"));
    assertEquals("1.25", saved.currentRecord().values().get("amount"));
    assertFalse(saved.currentRecord().values().containsKey("live"));
    var rows = (List<?>) saved.currentRecord().values().get("lines");
    assertEquals(1, rows.size());
    assertEquals("商品・東京", ((Map<?, ?>) ((Map<?, ?>) rows.getFirst()).get("values")).get("copied"));
    assertThrows(
        ApplicationFormException.class,
        () -> records.captureCreate(form, command),
        "A snapshot capture is a distinct intent and cannot reuse an ordinary CRUD key");
    var submission =
        new CreateApplicationFormRecordCommand(
            runtime, "mapped-submission", FormRecordState.SUBMITTED, values, mappings);
    var captured = records.captureCreate(form, submission);
    assertFalse(captured.values().containsKey("live"));
    dna.updateRecord(
        context("customers", false),
        new RecordUpdateCommand(
            target.physicalModelId(),
            targetSchema,
            customer,
            0,
            Map.of("stored_name", "changed", "stored_price", "2.00"),
            "update-customer"));
    assertEquals(
        "changed",
        live.read(
                "trade-app",
                "trade-page",
                "customer",
                new FormReferenceLiveQuery(runtime, customer))
            .values()
            .get("live")
            .value());
    assertEquals(saved.recordId(), records.create("trade-app", "trade-page", command).recordId());
    assertEquals(
        captured,
        records.captureCreate(form, submission),
        "Replay must preserve the frozen accepted snapshot");
    assertThrows(
        ApplicationFormException.class,
        () ->
            records.create(
                "trade-app",
                "trade-page",
                new CreateApplicationFormRecordCommand(
                    runtime, "stale-copy", FormRecordState.SUBMITTED, values, mappings)));
    assertThrows(
        ApplicationFormException.class,
        () ->
            records.create(
                "trade-app",
                "trade-page",
                new CreateApplicationFormRecordCommand(
                    runtime,
                    "forged-copy",
                    FormRecordState.SUBMITTED,
                    Map.of("reference", customer, "copied", "forged"),
                    mappings)));
    targetsAllowed.set(false);
    var hidden = records.get("trade-app", "trade-page", saved.recordId());
    assertFalse(hidden.values().containsKey("copied"));
    targetsAllowed.set(true);
    assertEquals(
        "客户・日本語😀",
        records.get("trade-app", "trade-page", saved.recordId()).values().get("copied"));
  }

  private static DnaInvocationContext context(String resource, boolean related) {
    var policy = new DnaResourcePolicy("1", "ALL", Set.of(), false, List.of(), Map.of());
    return new DnaInvocationContext(
        "tenant-a",
        "business-user",
        null,
        resource,
        false,
        related ? Set.of("trades", "customers") : Set.of(resource),
        "ALL",
        Set.of(),
        false,
        List.of(),
        Map.of(),
        related ? Map.of("customers", policy) : Map.of());
  }
}
