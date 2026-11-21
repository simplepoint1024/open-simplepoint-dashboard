package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormCalculationPublicationProjection;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormRelationBinding;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler.CompiledStorageProjection;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaFormRuntimeCallerGuard;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaOwnedRecordServiceImpl;

/** Proves projected APP relation rules with real DNA policy publication and aggregate commands. */
final class FormDnaReferenceRecordAssertions {
  private FormDnaReferenceRecordAssertions() {}

  static void verify(
      FormDnaPublicationFixture fixture,
      FormStorageProjectionCompiler storage,
      ApplicationFormDocument document,
      List<FormRelationBinding> bindings,
      CompiledStorageProjection projected,
      PreparedDnaSchema trade,
      PreparedDnaSchema customer,
      PreparedDnaSchema product,
      CompiledStorageProjection base,
      String detailJson)
      throws Exception {
    var author = FormDnaPublicationFixture.actor("tenant-a");
    for (var pair :
        Map.of(
                trade.physicalModelId(),
                "trades",
                customer.physicalModelId(),
                "customers",
                product.physicalModelId(),
                "products")
            .entrySet()) {
      fixture.dna.projectModelResource(
          author,
          new PhysicalModelResourceProjectionCommand(
              pair.getKey(), pair.getValue(), "app:" + pair.getValue(), 1));
    }
    var documents = new FormDocumentCodec(fixture.mapper);
    var encoded = documents.encode(document);
    var version = new ApplicationFormVersionEntity();
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson(projected.json());
    version.setStorageChecksum(projected.checksum());
    version.setPhysicalModelVersion(trade.physicalModelVersion());
    version.setRelationBindingsJson(documents.encodeRelationBindings(document, bindings));
    version.setDetailBindingsJson(detailJson);
    var policy =
        new FormCalculationPublicationProjection(
                documents, storage, fixture.mapper, Clock.systemUTC())
            .compile(version, trade.physicalModelId(), false);
    var tradeSchema = new RecordSchemaReference(trade.physicalModelVersion(), projected.checksum());
    var publish =
        new RecordWritePolicyPublicationCommand(
            trade.physicalModelId(),
            "trade-app",
            "trade",
            tradeSchema,
            0,
            1,
            "publish-relations",
            encoded.checksum(),
            Map.of(),
            30_000,
            Map.of(),
            policy);
    var progress = fixture.dna.publishRecordWritePolicy(author, publish);
    for (int attempt = 0; !progress.published() && attempt < 12; attempt++) {
      progress = fixture.dna.publishRecordWritePolicy(author, publish);
    }
    assertTrue(progress.published(), "Actual APP reference governance must activate before writes");
    assertTrue(fixture.dna.publishRecordWritePolicy(author, publish).published());

    var customerSchema =
        new RecordSchemaReference(customer.physicalModelVersion(), base.checksum());
    var productSchema = new RecordSchemaReference(product.physicalModelVersion(), base.checksum());
    String customerId =
        fixture
            .dna
            .createRecord(
                user("customers", true),
                new RecordCreateCommand(
                    customer.physicalModelId(),
                    customerSchema,
                    "customer-record",
                    Map.of("record_state", "DRAFT")))
            .recordId();
    String productId =
        fixture
            .dna
            .createRecord(
                user("products", true),
                new RecordCreateCommand(
                    product.physicalModelId(),
                    productSchema,
                    "product-record",
                    Map.of("record_state", "DRAFT")))
            .recordId();
    var detail = fixture.mapper.readTree(detailJson).get(0);
    var detailModel =
        new OwnedRecordModel(
            detail.get("physicalModelId").asText(),
            new RecordSchemaReference(
                detail.get("schemaVersion").asInt(), detail.get("checksum").asText()));
    var properties = new DnaFormRuntimeRemoteProperties();
    properties.setAllowedCallers(Set.of("app-form-test"));
    var aggregates =
        new DnaOwnedRecordServiceImpl(
            new DnaFormRuntimeCallerGuard(properties), fixture.recordServices.owned);
    var allowed = create(trade, tradeSchema, detailModel, customerId, productId, "trade-record");
    var denied = create(trade, tradeSchema, detailModel, customerId, productId, "denied-record");
    conflict(() -> aggregates.execute(user("trades", false), denied));
    var saved = aggregates.execute(user("trades", true), allowed);
    assertEquals(saved, aggregates.execute(user("trades", true), allowed));
    var read =
        aggregates.get(
            user("trades", true),
            new OwnedRecordQuery(
                new RecordLookupQuery(
                    trade.physicalModelId(), tradeSchema, saved.recordId(), List.of()),
                List.of(detailModel)));
    assertEquals(customerId, read.parent().values().get("customer_id"));
    assertEquals(1, read.details().get(detailModel.physicalModelId()).size());
    assertEquals(
        productId,
        read.details().get(detailModel.physicalModelId()).getFirst().values().get("product_id"));
    FormInverseRecordDatabaseAssertions.verify(
        aggregates,
        trade,
        tradeSchema,
        detailModel,
        customerId,
        productId,
        saved.recordId(),
        customer.physicalModelId(),
        product.physicalModelId());
    // Target-only policy must not hide an incoming reference from deletion protection.
    conflict(
        () ->
            fixture.dna.deleteRecord(
                user("customers", false),
                new RecordDeleteCommand(
                    customer.physicalModelId(), customerSchema, customerId, 0)));
    conflict(
        () ->
            fixture.dna.deleteRecord(
                user("products", false),
                new RecordDeleteCommand(product.physicalModelId(), productSchema, productId, 0)));
  }

  private static OwnedRecordCommand create(
      PreparedDnaSchema trade,
      RecordSchemaReference schema,
      OwnedRecordModel detail,
      String customer,
      String product,
      String key) {
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            trade.physicalModelId(),
            schema,
            List.of(
                new RecordCreateMutation(
                    "create", Map.of("customer_id", customer, "record_state", "DRAFT"))),
            30_000,
            key,
            1),
        List.of(
            new OwnedRecordSet(
                detail, List.of(new OwnedRecordRow(null, null, Map.of("product_id", product))))));
  }

  static DnaInvocationContext user(String resource, boolean targets) {
    var policy = new DnaResourcePolicy("1", "ALL", Set.of(), false, List.of(), Map.of());
    Map<String, DnaResourcePolicy> related =
        targets && resource.equals("trades")
            ? Map.of("customers", policy, "products", policy)
            : Map.of();
    Set<String> resources = targets ? Set.of("trades", "customers", "products") : Set.of(resource);
    return new DnaInvocationContext(
        "tenant-a",
        "business-user",
        null,
        resource,
        false,
        resources,
        "ALL",
        Set.of(),
        false,
        List.of(),
        Map.of(),
        related);
  }

  private static void conflict(Runnable operation) {
    var failure = assertThrows(DnaRuntimeException.class, operation::run);
    assertEquals(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT, failure.errorCode());
  }
}
