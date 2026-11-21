package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.simplepoint.app.form.service.WorkspaceApplicationAccessPort;
import org.simplepoint.app.form.service.WorkspaceApplicationPageAccessPort;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.app.form.service.ApplicationFormServiceImpl;
import org.simplepoint.plugin.app.form.service.DefaultFormComponentRegistry;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormDocumentValidator;
import org.simplepoint.plugin.app.form.service.FormRelationTargetResolver;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.springframework.transaction.support.TransactionTemplate;

/** Real APP draft/version transactions; catalog activation is an explicit test boundary. */
final class FormRelationVersionAssertions {
  private FormRelationVersionAssertions() {}

  static void verify(FormDnaPublicationFixture fixture) {
    verifyVersion(fixture, false);
  }

  static void verifyMapped(FormDnaPublicationFixture fixture) {
    verifyVersion(fixture, true);
  }

  private static void verifyVersion(FormDnaPublicationFixture fixture, boolean mapped) {
    var em = fixture.appEntities;
    var transactions = new TransactionTemplate(fixture.appTransactions);
    var context = new FormRequestContext();
    var codec = new FormDocumentCodec(fixture.mapper);
    var storage = new FormStorageProjectionCompiler(fixture.mapper);
    var pages =
        FormDnaPublicationFixture.transactional(
            new WorkspaceApplicationPageAccessPort(em, Clock.systemUTC()), fixture.appTransactions);
    var published = new PublishedApplicationFormResolver(em, context, pages, codec);
    var relations = new FormRelationTargetResolver(em, context, published);
    var forms =
        FormDnaPublicationFixture.transactional(
            new ApplicationFormServiceImpl(
                em,
                new WorkspaceApplicationAccessPort(em),
                context,
                new FormDocumentValidator(new DefaultFormComponentRegistry()),
                codec,
                storage,
                relations),
            fixture.appTransactions);
    authorize(true);
    try {
      transactions.executeWithoutResult(
          status -> {
            var app = new WorkspaceApplication();
            app.setId("version-app");
            app.setTenantId("tenant-a");
            app.setName("关联版本验证");
            app.setStatus("DRAFT");
            app.setMainPageId("customer-page");
            app.setCreatedAt(Instant.now());
            app.setUpdatedAt(Instant.now());
            em.persist(app);
            var page = new WorkspaceApplicationPage();
            page.setId("customer-page");
            page.setTenantId("tenant-a");
            page.setApplicationId("version-app");
            page.setKind("MAIN");
            page.setName("客户");
            page.setStatus("DRAFT");
            page.setCreatedAt(Instant.now());
            page.setUpdatedAt(Instant.now());
            em.persist(page);
          });
      var target =
          forms.create(new CreateApplicationFormCommand("version-app", "customer", "客户", null));
      if (mapped) {
        var initial = forms.draft("version-app", target.id()).document();
        forms.saveDraft(
            "version-app",
            target.id(),
            new SaveApplicationFormDraftCommand(
                0,
                new ApplicationFormDocument(
                    "1", List.of(name(false)), List.of(), initial.scenes(), List.of())));
      }
      var targetVersion = forms.createVersion("version-app", target.id(), mapped ? 1 : 0);
      var targetStorage = storage.compile(targetVersion.document(), List.of(), true);
      var physical =
          fixture.publications.prepare(
              FormDnaPublicationFixture.actor("tenant-a"),
              new DnaSchemaPreparationRequest(
                  "version-app",
                  target.id(),
                  "客户",
                  "default",
                  null,
                  targetStorage.schema(),
                  targetStorage.checksum(),
                  false));
      transactions.executeWithoutResult(
          status -> {
            var form = em.find(ApplicationFormEntity.class, target.id());
            form.setPhysicalModelId(physical.physicalModelId());
            form.setPublishedVersion(1);
            em.find(ApplicationFormVersionEntity.class, targetVersion.id())
                .setPhysicalModelVersion(physical.physicalModelVersion());
            em.persist(binding(target.id()));
          });
      pages.activateBinding(
          new ActivateApplicationPageBinding(
              "tenant-a",
              "version-app",
              "customer-page",
              target.id(),
              1,
              "customer-resource",
              "customers",
              "/app/runtime/version-app/customer-page"));
      var source =
          forms.create(new CreateApplicationFormCommand("version-app", "trade", "交易", null));
      var document = document(target.id(), mapped);
      forms.saveDraft("version-app", source.id(), new SaveApplicationFormDraftCommand(0, document));

      authorize(false);
      var denied =
          assertThrows(
              ApplicationFormException.class,
              () -> forms.createVersion("version-app", source.id(), 1));
      assertEquals(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, denied.errorCode());
      assertEquals(0, forms.versions("version-app", source.id()).size());
      assertEquals(0, forms.get("version-app", source.id()).latestVersion());

      authorize(true);
      var version = forms.createVersion("version-app", source.id(), 1);
      assertEquals(version.id(), forms.createVersion("version-app", source.id(), 1).id());
      var frozen =
          transactions.execute(status -> em.find(ApplicationFormVersionEntity.class, version.id()));
      var bindings = codec.decodeRelationBindings(document, frozen.getRelationBindingsJson());
      assertEquals(1, bindings.size());
      assertEquals(physical.physicalModelId(), bindings.getFirst().targetPhysicalModelId());
      assertEquals("customer-page", bindings.getFirst().targetPageId());
      assertEquals("customers", bindings.getFirst().targetResourceCode());
      if (mapped) {
        assertEquals(
            List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)),
            bindings.getFirst().targetFields());
        assertEquals(
            document.relations().getFirst().mappings(),
            codec.decode(frozen.getDocumentJson()).relations().getFirst().mappings());
      }
      assertEquals(
          physical.physicalModelId(),
          storage.decode(frozen.getStorageSchemaJson()).references().getFirst().targetModelId());
      var prepared =
          fixture.publications.prepare(
              FormDnaPublicationFixture.actor("tenant-a"),
              new DnaSchemaPreparationRequest(
                  "version-app",
                  source.id(),
                  "交易",
                  "default",
                  null,
                  storage.decode(frozen.getStorageSchemaJson()),
                  frozen.getStorageChecksum(),
                  false));
      assertEquals(true, prepared.active());

      // A newly frozen version cannot silently fall back to a different resource after offline.
      transactions.executeWithoutResult(
          status ->
              em.find(ApplicationFormPageBindingEntity.class, "customer-binding")
                  .setStatus(ApplicationFormPageBindingStatus.RETIRED));
      var changed =
          new ApplicationFormDocument(
              document.contractVersion(),
              document.fields(),
              document.indexes(),
              document.scenes(),
              document.rules(),
              document.details(),
              document.constraints(),
              document.presentation(),
              List.of(
                  new FormRelationDefinition(
                      "1",
                      "customer",
                      FormRelationCardinality.ONE_TO_ONE,
                      new FormRelationField(null, "customer"),
                      document.relations().getFirst().target(),
                      null,
                      null,
                      document.relations().getFirst().mappings())));
      forms.saveDraft("version-app", source.id(), new SaveApplicationFormDraftCommand(1, changed));
      assertThrows(
          ApplicationFormException.class, () -> forms.createVersion("version-app", source.id(), 2));
      assertEquals(1, forms.versions("version-app", source.id()).size());
      var retained =
          transactions.execute(status -> em.find(ApplicationFormVersionEntity.class, version.id()));
      assertEquals(frozen.getRelationBindingsJson(), retained.getRelationBindingsJson());
      assertEquals(frozen.getStorageSchemaJson(), retained.getStorageSchemaJson());
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private static void authorize(boolean allowed) {
    var context = new AuthorizationContext();
    context.setUserId("author");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(allowed ? List.of("customers") : List.of());
    context.setIsAdministrator(false);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static ApplicationFormPageBindingEntity binding(String formId) {
    var binding = new ApplicationFormPageBindingEntity();
    binding.setId("customer-binding");
    binding.setTenantId("tenant-a");
    binding.setApplicationId("version-app");
    binding.setPageId("customer-page");
    binding.setFormId(formId);
    binding.setFormVersion(1);
    binding.setSceneId("main");
    binding.setIdempotencyKey("customer-binding");
    binding.setGeneration(1);
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVE);
    binding.setResourceManagedKey("customer-resource");
    binding.setResourceId("customer-resource");
    binding.setResourceCode("customers");
    binding.setCreatedAt(Instant.now());
    binding.setUpdatedAt(Instant.now());
    return binding;
  }

  private static ApplicationFormDocument document(String target, boolean mapped) {
    var field =
        new FormFieldDefinition(
            "customer",
            "customer_id",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    var input =
        new FormComponentDefinition(
            "customer-input",
            "field.reference",
            "1",
            "customer",
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of());
    var root =
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
            List.of(input));
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(null, "customer"),
            new FormRelationTarget("version-app", target, "customer-resource"),
            null,
            null,
            mapped
                ? List.of(
                    new FormReferenceMapping(
                        "copy",
                        "name",
                        "name",
                        FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                        FormReferenceMapping.Conversion.IDENTITY,
                        FormReferenceMapping.NullPolicy.CLEAR))
                : List.of());
    return new ApplicationFormDocument(
        "1",
        mapped ? List.of(field, name(true)) : List.of(field),
        List.of(),
        List.of(new FormSceneDefinition("main", "main", "主表单", FormSceneType.MAIN, null, root)),
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(relation));
  }

  private static FormFieldDefinition name(boolean required) {
    return new FormFieldDefinition(
        "name",
        "name",
        "名称",
        "名称",
        FormFieldType.STRING,
        FormStorageMode.STORED,
        required,
        false,
        120,
        null,
        null,
        null,
        List.of());
  }
}
