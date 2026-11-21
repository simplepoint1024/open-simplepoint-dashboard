package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.FormDetailSchemaCoordinator;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.app.form.service.FormRelationBinding;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler.CompiledStorageProjection;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * APP projection/coordinator -> guarded DNA facade -> real modular control store and target DDL.
 */
@EnabledIfEnvironmentVariable(
    named = "DNA_RUNTIME_TEST_POSTGRES_URL",
    matches = "jdbc:postgresql:.*")
class FormDnaReferencePublicationDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void contextualCandidatesPreserveRealDatabasePagingAndPermissionBoundaries(boolean postgres)
      throws Exception {
    try (var fixture = new FormDnaPublicationFixture(postgres)) {
      var document =
          org.simplepoint.plugin.app.form.service.FormReferenceContextDatabaseAssertions
              .targetDocument();
      var storage =
          new FormStorageProjectionCompiler(fixture.mapper).compile(document, List.of(), true);
      var actor = FormDnaPublicationFixture.actor("tenant-a");
      var target =
          fixture.publications.prepare(actor, request("context-app", "context-customers", storage));
      assertTrue(target.active());
      fixture.dna.projectModelResource(
          actor,
          new org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand(
              target.physicalModelId(), "context-customers", "app:context-customers", 1));
      org.simplepoint.plugin.app.form.service.FormReferenceContextDatabaseAssertions.verify(
          fixture.dna,
          target.physicalModelId(),
          new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(
              target.physicalModelVersion(), storage.checksum()));
    }
  }

  @org.junit.jupiter.api.Test
  void freezesAuthorizedTargetsInRealImmutableAppVersions() throws Exception {
    try (var fixture = new FormDnaPublicationFixture(true)) {
      FormRelationVersionAssertions.verify(fixture);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publishesRootAndDetailReferencesThroughTheRealDnaContract(boolean postgres)
      throws Exception {
    try (var fixture = new FormDnaPublicationFixture(postgres)) {
      var storage = new FormStorageProjectionCompiler(fixture.mapper);
      var actor = FormDnaPublicationFixture.actor("tenant-a");
      var plain = new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of());
      var base = storage.compile(plain, List.of(), true);
      var customer = fixture.publications.prepare(actor, request("customer-app", "customer", base));
      var product = fixture.publications.prepare(actor, request("catalog-app", "product", base));
      assertTrue(customer.active());
      assertTrue(product.active());
      var document = document();
      var bindings =
          List.of(
              new FormRelationBinding(
                  "customer",
                  document.relations().getFirst().target(),
                  "customer-page",
                  "customers",
                  customer.physicalModelId()),
              new FormRelationBinding(
                  "product",
                  document.relations().getLast().target(),
                  "product-page",
                  "products",
                  product.physicalModelId()));
      var projected = storage.compile(document, bindings, true);
      var command = request("trade-app", "trade", projected);
      var trade = fixture.publications.prepare(actor, command);
      assertTrue(trade.active());
      assertEquals(
          trade.physicalModelId(), fixture.publications.prepare(actor, command).physicalModelId());
      var details = new FormDetailSchemaCoordinator(fixture.publications, storage, fixture.mapper);
      String detailJson =
          details.prepare(
              actor, "trade-app", "trade", trade.physicalModelId(), document, bindings, false);
      assertNotNull(detailJson);
      String detailModel =
          fixture.mapper.readTree(detailJson).get(0).get("physicalModelId").asText();
      assertFalse(detailModel.isBlank());
      assertForeignKey(fixture, trade.physicalModelId(), "customer_id", customer.physicalModelId());
      assertForeignKey(fixture, detailModel, "product_id", product.physicalModelId());
      var jdbc = new JdbcTemplate(fixture.control.source);
      assertEquals(
          2L,
          jdbc.queryForObject(
              "select count(*) from simpoint_dna_relation_definitions", Long.class));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from simpoint_dna_relation_guards where released=false",
              Long.class));

      FormDnaReferenceRecordAssertions.verify(
          fixture,
          storage,
          document,
          bindings,
          projected,
          trade,
          customer,
          product,
          base,
          detailJson);

      var otherTenant = FormDnaPublicationFixture.actor("tenant-b");
      var failure =
          assertThrows(
              DnaRuntimeException.class,
              () ->
                  fixture.publications.prepare(
                      otherTenant, request("trade-app", "foreign-trade", projected)));
      assertEquals(DnaRuntimeErrorCode.SCHEMA_INVALID, failure.errorCode());
      long models =
          jdbc.queryForObject("select count(*) from simpoint_dna_physical_models", Long.class);
      FormDnaPublicationFixture.authenticate("untrusted-test-client");
      assertThrows(
          AccessDeniedException.class,
          () -> fixture.publications.prepare(actor, request("trade-app", "untrusted", projected)));
      assertEquals(
          models,
          jdbc.queryForObject("select count(*) from simpoint_dna_physical_models", Long.class));
    }
  }

  private static DnaSchemaPreparationRequest request(
      String app, String form, CompiledStorageProjection storage) {
    return new DnaSchemaPreparationRequest(
        app, form, form, "default", null, storage.schema(), storage.checksum(), false);
  }

  private static ApplicationFormDocument document() {
    var lines =
        new FormDetailDefinition(
            "lines", "lines", "商品明细", List.of(reference("product", "product_id")), List.of());
    return new ApplicationFormDocument(
        "1",
        List.of(reference("customer", "customer_id")),
        List.of(),
        List.of(),
        List.of(),
        List.of(lines),
        List.of(),
        null,
        List.of(
            new FormRelationDefinition(
                "1",
                "customer",
                FormRelationCardinality.MANY_TO_ONE,
                new FormRelationField(null, "customer"),
                new FormRelationTarget("customer-app", "customer", "selected-customer")),
            new FormRelationDefinition(
                "1",
                "product",
                FormRelationCardinality.MANY_TO_ONE,
                new FormRelationField("lines", "product"),
                new FormRelationTarget("catalog-app", "product", "selected-product"))));
  }

  private static FormFieldDefinition reference(String id, String code) {
    return new FormFieldDefinition(
        id,
        code,
        id,
        id,
        FormFieldType.UUID,
        FormStorageMode.REFERENCE,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static void assertForeignKey(
      FormDnaPublicationFixture fixture, String source, String column, String target)
      throws Exception {
    var jdbc = new JdbcTemplate(fixture.control.source);
    String sourceTable =
        jdbc.queryForObject(
            "select table_name from simpoint_dna_physical_bindings where physical_model_id=?",
            String.class,
            source);
    String targetTable =
        jdbc.queryForObject(
            "select table_name from simpoint_dna_physical_bindings where physical_model_id=?",
            String.class,
            target);
    var mappings = new HashSet<String>();
    try (var connection = fixture.target.source.getConnection();
        var keys =
            connection
                .getMetaData()
                .getImportedKeys(fixture.target.catalog, fixture.target.schema(), sourceTable)) {
      while (keys.next()) {
        if (targetTable.equals(keys.getString("PKTABLE_NAME"))) {
          mappings.add(keys.getString("FKCOLUMN_NAME") + "->" + keys.getString("PKCOLUMN_NAME"));
        }
      }
    }
    assertEquals(java.util.Set.of("tenant_id->tenant_id", column + "->id"), mappings);
  }
}
