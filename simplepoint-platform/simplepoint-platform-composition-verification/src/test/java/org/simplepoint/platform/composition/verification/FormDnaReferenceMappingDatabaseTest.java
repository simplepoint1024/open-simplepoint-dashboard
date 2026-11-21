package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.DefaultFormComponentRegistry;
import org.simplepoint.plugin.app.form.service.FormCalculationPublicationProjection;
import org.simplepoint.plugin.app.form.service.FormDetailSchemaCoordinator;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormDocumentValidator;
import org.simplepoint.plugin.app.form.service.FormMappedRuntimeDatabaseAssertions;
import org.simplepoint.plugin.app.form.service.FormRelationBinding;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler.CompiledStorageProjection;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaFormRuntimeCallerGuard;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaOwnedRecordServiceImpl;

/** Real APP projection, policy activation, record facade and native PostgreSQL/MySQL execution. */
@EnabledIfEnvironmentVariable(
    named = "DNA_RUNTIME_TEST_POSTGRES_URL",
    matches = "jdbc:postgresql:.*")
class FormDnaReferenceMappingDatabaseTest {
  @org.junit.jupiter.api.Test
  void freezesAuthorizedMappingDependenciesInRealImmutableAppVersions() throws Exception {
    try (var fixture = new FormDnaPublicationFixture(true)) {
      FormRelationVersionAssertions.verifyMapped(fixture);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void mappingPublicationObservationAndAggregateWritesRemainConsistent(boolean postgres)
      throws Exception {
    try (var fixture = new FormDnaPublicationFixture(postgres)) {
      var compiler = new FormStorageProjectionCompiler(fixture.mapper);
      var targetDocument =
          new ApplicationFormDocument(
              "1",
              List.of(
                  field("name", FormFieldType.STRING, FormStorageMode.STORED, false),
                  field("price", FormFieldType.DECIMAL, FormStorageMode.STORED, false)),
              List.of(),
              List.of(),
              List.of());
      var targetStorage = compiler.compile(targetDocument, List.of(), true);
      var actor = FormDnaPublicationFixture.actor("tenant-a");
      var customer =
          fixture.publications.prepare(actor, request("customer-app", "customer", targetStorage));
      assertTrue(customer.active());
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              customer.physicalModelId(), "customers", "app:customers", 1));
      var document = document();
      new FormDocumentValidator(new DefaultFormComponentRegistry()).validatePublication(document);
      var bindings =
          document.relations().stream()
              .map(
                  relation ->
                      new FormRelationBinding(
                          relation.relationId(),
                          relation.target(),
                          "customer-page",
                          "customers",
                          customer.physicalModelId(),
                          List.of(
                              new LogicalReferenceField("name", LogicalFieldType.STRING),
                              new LogicalReferenceField("price", LogicalFieldType.DECIMAL))))
              .toList();
      var storage = compiler.compile(document, bindings, true);
      var trade = fixture.publications.prepare(actor, request("trade-app", "trade", storage));
      assertTrue(trade.active());
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              trade.physicalModelId(), "trades", "app:trades", 1));
      String details =
          new FormDetailSchemaCoordinator(fixture.publications, compiler, fixture.mapper)
              .prepare(
                  actor, "trade-app", "trade", trade.physicalModelId(), document, bindings, false);
      var documents = new FormDocumentCodec(fixture.mapper);
      var encoded = documents.encode(document);
      var version = new ApplicationFormVersionEntity();
      version.setDocumentJson(encoded.json());
      version.setChecksum(encoded.checksum());
      version.setStorageSchemaJson(storage.json());
      version.setStorageChecksum(storage.checksum());
      version.setPhysicalModelVersion(trade.physicalModelVersion());
      version.setRelationBindingsJson(documents.encodeRelationBindings(document, bindings));
      version.setDetailBindingsJson(details);
      var policy =
          new FormCalculationPublicationProjection(
                  documents, compiler, fixture.mapper, Clock.systemUTC())
              .compile(version, trade.physicalModelId(), false);
      var command =
          new RecordWritePolicyPublicationCommand(
              trade.physicalModelId(),
              "trade-app",
              "trade",
              new RecordSchemaReference(trade.physicalModelVersion(), storage.checksum()),
              0,
              1,
              "publish-mapped-relations",
              encoded.checksum(),
              Map.of(),
              30_000,
              Map.of(),
              policy);
      var progress = fixture.dna.publishRecordWritePolicy(actor, command);
      for (int attempt = 0; !progress.published() && attempt < 12; attempt++) {
        progress = fixture.dna.publishRecordWritePolicy(actor, command);
      }
      assertTrue(progress.published());
      var properties = new DnaFormRuntimeRemoteProperties();
      properties.setAllowedCallers(Set.of("app-form-test"));
      var owned =
          new DnaOwnedRecordServiceImpl(
              new DnaFormRuntimeCallerGuard(properties), fixture.recordServices.owned);
      FormMappedRuntimeDatabaseAssertions.verify(
          fixture.dna,
          owned,
          fixture.mapper,
          document,
          targetDocument,
          trade,
          customer,
          storage,
          targetStorage,
          details,
          bindings);
    }
  }

  private static DnaSchemaPreparationRequest request(
      String app, String form, CompiledStorageProjection storage) {
    return new DnaSchemaPreparationRequest(
        app, form, form, "default", null, storage.schema(), storage.checksum(), false);
  }

  private static ApplicationFormDocument document() {
    var fields =
        List.of(
            field("reference", FormFieldType.UUID, FormStorageMode.REFERENCE, false),
            field("copied", FormFieldType.STRING, FormStorageMode.STORED, true),
            field("amount", FormFieldType.DECIMAL, FormStorageMode.STORED, false),
            field("live", FormFieldType.STRING, FormStorageMode.TRANSIENT, false));
    return new ApplicationFormDocument(
        "1",
        fields,
        List.of(),
        List.of(
            new FormSceneDefinition(
                "main",
                "main",
                "Main",
                FormSceneType.MAIN,
                null,
                new FormComponentDefinition(
                    "root",
                    "layout.form",
                    "1",
                    null,
                    24,
                    null,
                    null,
                    null,
                    null,
                    false,
                    Map.of(),
                    List.of()))),
        List.of(),
        List.of(new FormDetailDefinition("lines", "lines", "商品明细", fields, List.of())),
        List.of(),
        null,
        List.of(relation("customer", null), relation("product", "lines")));
  }

  private static FormRelationDefinition relation(String id, String detail) {
    return new FormRelationDefinition(
        "1",
        id,
        FormRelationCardinality.MANY_TO_ONE,
        new FormRelationField(detail, "reference"),
        new FormRelationTarget("customer-app", "customer", "customer-page"),
        null,
        null,
        List.of(
            new FormReferenceMapping(
                "live",
                "name",
                "live",
                FormReferenceMapping.Mode.LIVE,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.CLEAR),
            new FormReferenceMapping(
                "copy",
                "name",
                "copied",
                FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.CLEAR),
            new FormReferenceMapping(
                "price",
                "price",
                "amount",
                FormReferenceMapping.Mode.EDITABLE_DEFAULT,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.PRESERVE)));
  }

  private static FormFieldDefinition field(
      String id, FormFieldType type, FormStorageMode mode, boolean required) {
    return new FormFieldDefinition(
        id,
        mode == FormStorageMode.TRANSIENT ? null : "stored_" + id,
        id,
        mode == FormStorageMode.TRANSIENT ? null : id,
        type,
        mode,
        required,
        false,
        type == FormFieldType.STRING ? 120 : null,
        type == FormFieldType.DECIMAL ? 20 : null,
        type == FormFieldType.DECIMAL ? 2 : null,
        null,
        List.of());
  }
}
