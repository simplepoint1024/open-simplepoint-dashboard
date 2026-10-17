package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.app.form.service.ApplicationFormReleaseCatalog;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormCatalogPage;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormReleaseView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCatalogService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormPublicationService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordSchemaService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRuntimeService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifies the public form HTTP contract independently from persistence. */
class ApplicationFormControllerHttpTest {

  private ApplicationFormCatalogService catalog;
  private ApplicationFormService formService;
  private ApplicationFormPublicationService publicationService;
  private ApplicationFormRuntimeService runtimeService;
  private ApplicationFormRecordService recordService;
  private ApplicationFormRecordSchemaService recordSchemas;
  private ApplicationFormReleaseCatalog releases;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog = mock(ApplicationFormCatalogService.class);
    formService = mock(ApplicationFormService.class);
    publicationService = mock(ApplicationFormPublicationService.class);
    runtimeService = mock(ApplicationFormRuntimeService.class);
    recordService = mock(ApplicationFormRecordService.class);
    recordSchemas = mock(ApplicationFormRecordSchemaService.class);
    releases = mock(ApplicationFormReleaseCatalog.class);
    mvc = MockMvcBuilders.standaloneSetup(
            new ApplicationFormCatalogController(catalog),
            new ApplicationFormController(formService, publicationService),
            new ApplicationFormRuntimeController(runtimeService),
            new ApplicationFormRecordController(recordService, recordSchemas),
            new ApplicationFormReleaseController(releases)
        )
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
        .setControllerAdvice(new ApplicationFormExceptionHandler(),
            new org.simplepoint.core.handler.GlobalExceptionHandler())
        .build();
  }

  @Test
  void cancellationRequiresPublishAuthorityAndUsesOnlyThePersistedReleaseIdentity() throws Exception {
    var proxy = new ProxyFactory(new ApplicationFormController(formService, publicationService));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    var secured = MockMvcBuilders.standaloneSetup(proxy.getProxy())
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler()).build();
    try {
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "unused",
          List.of(new SimpleGrantedAuthority("app.workbench.forms.view"))));
      assertThatThrownBy(() -> secured.perform(post("/applications/app-1/forms/form-1/releases/release-1/cancel")))
          .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      verifyNoInteractions(publicationService);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("publisher", "unused",
          List.of(new SimpleGrantedAuthority("app.workbench.forms.publish"))));
      secured.perform(post("/applications/app-1/forms/form-1/releases/release-1/cancel")
              .contentType(MediaType.APPLICATION_JSON).content("{\"formVersion\":999,\"pageId\":\"other\"}"))
          .andExpect(status().isOk());
      verify(publicationService).cancel("app-1", "form-1", "release-1");
      verifyNoInteractions(formService);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void obsoleteResourceProjectionReturnsConflictWithoutPublishingAnotherVersion() throws Exception {
    when(publicationService.resume("app-1", "form-1", "release-1")).thenThrow(
        new ApplicationFormException(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED, "Publication was superseded"));
    mvc.perform(post("/applications/app-1/forms/form-1/releases/release-1/resume"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAGE_BINDING_SUPERSEDED"));
    verifyNoInteractions(formService);
  }

  @Test
  void releaseHistoryUsesPlatformPagingAndResumeUsesOnlyTheSavedIdentity() throws Exception {
    var row = new ApplicationFormReleaseCatalog.ReleaseRow("release-1", "page-1", "Entry", 2,
        "DATA_PREPARING", null, Instant.EPOCH, true);
    when(releases.page(any(), any(), any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(
        List.of(row), org.springframework.data.domain.PageRequest.of(1, 5), 6));
    mvc.perform(get("/applications/app-1/forms/form-1/releases")
            .param("page", "1").param("size", "5").param("status", "equals:DATA_PREPARING")
            .param("sort", "formVersion,desc", "updatedAt,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("release-1"))
        .andExpect(jsonPath("$.content[0].formVersion").value(2));
    var pageable = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    verify(releases).page(org.mockito.ArgumentMatchers.eq("app-1"), org.mockito.ArgumentMatchers.eq("form-1"),
        any(), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    assertThat(pageable.getValue().getSort().toList()).hasSize(2);
    mvc.perform(post("/applications/app-1/forms/form-1/releases/release-1/resume")
            .contentType(MediaType.APPLICATION_JSON).content("{\"formVersion\":999,\"pageId\":\"other\"}"))
        .andExpect(status().isOk());
    verify(publicationService).resume("app-1", "form-1", "release-1");
    verifyNoInteractions(formService);
  }

  @Test
  void catalogUsesPlatformPageEnvelopeAndPreservesMultiColumnSorting() throws Exception {
    Map<String, String> filters = Map.of("page", "1", "size", "10", "name", "like:customer",
        "sort", "code,asc");
    when(catalog.page("app-1", filters, 1, 10, List.of("code,asc", "updatedAt,desc")))
        .thenReturn(new ApplicationFormCatalogPage(
            List.of(formView()),
            new ApplicationFormCatalogPage.Metadata(1, 10, 11, 2)
        ));
    mvc.perform(get("/applications/app-1/forms/catalog")
            .param("page", "1").param("size", "10").param("name", "like:customer")
            .param("sort", "code,asc", "updatedAt,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("form-1"))
        .andExpect(jsonPath("$.page.totalElements").value(11))
        .andExpect(jsonPath("$.page.number").value(1));
    verify(catalog).page("app-1", filters, 1, 10, List.of("code,asc", "updatedAt,desc"));
  }

  @Test
  void publicationReadinessUsesPlatformFieldValidationWithoutExposingValues() throws Exception {
    when(formService.createVersion("app-1", "form-1", 4)).thenThrow(
        new org.simplepoint.core.validation.InvalidFormException(List.of(
            new org.simplepoint.core.validation.FormViolation("fields.customer-name", "input",
                Map.of(), "app.publication.requiredInput"))));
    mvc.perform(post("/applications/app-1/forms/form-1/versions")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("fields.customer-name"))
        .andExpect(jsonPath("$.fieldErrors[0].messageKey").value("app.publication.requiredInput"));
    verifyNoInteractions(publicationService);
  }

  @Test
  void catalogAccessFailuresUseTheExistingFormErrorContract() throws Exception {
    when(catalog.designer("missing")).thenThrow(new ApplicationFormException(
        ApplicationFormErrorCode.APPLICATION_NOT_FOUND, "Application was not found"
    ));
    mvc.perform(get("/applications/missing/forms/designer-contract"))
        .andExpect(status().isNotFound());
  }

  @Test
  void createUsesTheRouteApplicationIdentity() throws Exception {
    when(formService.create(any())).thenReturn(formView());

    mvc.perform(post("/applications/app-1/forms")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "customer",
                  "name": "Customer",
                  "description": "Customer profile"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("form-1"))
        .andExpect(jsonPath("$.applicationId").value("app-1"));

    ArgumentCaptor<CreateApplicationFormCommand> command =
        ArgumentCaptor.forClass(CreateApplicationFormCommand.class);
    verify(formService).create(command.capture());
    assertThat(command.getValue())
        .extracting(
            CreateApplicationFormCommand::applicationId,
            CreateApplicationFormCommand::code,
            CreateApplicationFormCommand::name
        )
        .containsExactly("app-1", "customer", "Customer");
  }

  @Test
  void saveDraftBindsRevisionAndCompleteSceneTree() throws Exception {
    when(formService.saveDraft(any(), any(), any())).thenReturn(draftView());

    mvc.perform(put("/applications/app-1/forms/form-1/draft")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "expectedRevision": 3,
                  "document": {
                    "contractVersion": "1",
                    "fields": [],
                    "rules": [],
                    "scenes": [{
                      "sceneId": "main",
                      "code": "main",
                      "name": "Main",
                      "type": "MAIN",
                      "root": {
                        "componentId": "root",
                        "componentType": "layout.form",
                        "columnSpan": 24,
                        "readOnly": false,
                        "children": []
                      }
                    }]
                  }
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(4))
        .andExpect(jsonPath("$.document.scenes[0].root.componentType")
            .value("layout.form"));

    ArgumentCaptor<SaveApplicationFormDraftCommand> command =
        ArgumentCaptor.forClass(SaveApplicationFormDraftCommand.class);
    verify(formService).saveDraft(
        org.mockito.ArgumentMatchers.eq("app-1"),
        org.mockito.ArgumentMatchers.eq("form-1"),
        command.capture()
    );
    assertThat(command.getValue().expectedRevision()).isEqualTo(3);
    assertThat(command.getValue().document().scenes()).singleElement()
        .extracting(FormSceneDefinition::type)
        .isEqualTo(FormSceneType.MAIN);
  }

  @Test
  void domainFailuresExposeStableCodeAndStatus() throws Exception {
    when(formService.get("app-1", "missing")).thenThrow(new ApplicationFormException(
        ApplicationFormErrorCode.FORM_NOT_FOUND,
        "Form was not found in the active application"
    ));

    mvc.perform(get("/applications/app-1/forms/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FORM_NOT_FOUND"))
        .andExpect(jsonPath("$.message")
            .value("Form was not found in the active application"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void dataPublicationUsesRouteVersionAndClientRetryIdentity() throws Exception {
    when(publicationService.prepareData(any(), any(), any())).thenReturn(publicationView());

    mvc.perform(post("/applications/app-1/forms/form-1/versions/3/data-publication")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"idempotencyKey":"publish-form-1-v3"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formVersion").value(3))
        .andExpect(jsonPath("$.status").value("DATA_READY"));

    ArgumentCaptor<PrepareApplicationFormDataCommand> command =
        ArgumentCaptor.forClass(PrepareApplicationFormDataCommand.class);
    verify(publicationService).prepareData(
        org.mockito.ArgumentMatchers.eq("app-1"),
        org.mockito.ArgumentMatchers.eq("form-1"),
        command.capture()
    );
    assertThat(command.getValue().formVersion()).isEqualTo(3);
    assertThat(command.getValue().idempotencyKey()).isEqualTo("publish-form-1-v3");
  }

  @Test
  void oneClickPublicationUsesTheRouteVersionAndExplicitPage() throws Exception {
    when(publicationService.publish(any(), any(), any())).thenReturn(
        new ApplicationFormReleaseView(
            publicationView(ApplicationFormPublicationStatus.ACTIVE),
            bindingView()
        )
    );

    mvc.perform(post("/applications/app-1/forms/form-1/versions/3/publication")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"pageId":"page-1","idempotencyKey":"publish-form-1-page-1-v3"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataPublication.status").value("ACTIVE"))
        .andExpect(jsonPath("$.pageBinding.pageId").value("page-1"))
        .andExpect(jsonPath("$.pageBinding.status").value("ACTIVE"));

    ArgumentCaptor<PublishApplicationFormCommand> command =
        ArgumentCaptor.forClass(PublishApplicationFormCommand.class);
    verify(publicationService).publish(
        org.mockito.ArgumentMatchers.eq("app-1"),
        org.mockito.ArgumentMatchers.eq("form-1"),
        command.capture()
    );
    assertThat(command.getValue())
        .extracting(
            PublishApplicationFormCommand::formVersion,
            PublishApplicationFormCommand::pageId,
            PublishApplicationFormCommand::idempotencyKey
        )
        .containsExactly(3, "page-1", "publish-form-1-page-1-v3");
  }

  @Test
  void runtimePageReturnsTheImmutableVersionSelectedByTheActiveBinding() throws Exception {
    when(runtimeService.page("app-1", "page-1")).thenReturn(new ApplicationFormRuntimeView(
        "app-1",
        "page-1",
        "Customer page",
        "form-1",
        "Customer",
        3,
        "main",
        "app.runtime.page.one",
        new ApplicationFormRuntimeCapabilities(true, true, false, false),
        new ApplicationFormRuntimeDocument(
            draftView().document().contractVersion(),
            List.of(),
            draftView().document().scenes(),
            draftView().document().rules()
        )
    ));

    mvc.perform(get("/runtime/applications/app-1/pages/page-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formVersion").value(3))
        .andExpect(jsonPath("$.sceneId").value("main"))
        .andExpect(jsonPath("$.document.contractVersion")
            .value(ApplicationFormApiVersion.DOCUMENT_V1));

    verify(runtimeService).page("app-1", "page-1");
  }

  @Test
  void runtimeRecordSchemaDelegatesToThePlatformSchemaProjection() throws Exception {
    when(recordSchemas.schema("app-1", "page-1")).thenReturn(java.util.Map.of(
        "schema", java.util.Map.of("type", "object", "properties", java.util.Map.of()),
        "buttons", List.of()
    ));

    mvc.perform(get("/runtime/applications/app-1/pages/page-1/records/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.type").value("object"))
        .andExpect(jsonPath("$.buttons").isArray());

    verify(recordSchemas).schema("app-1", "page-1");
  }

  @Test
  void runtimeRecordCreateAcceptsFieldIdentitiesWithoutPhysicalMetadata() throws Exception {
    when(recordService.create(any(), any(), any())).thenReturn(new ApplicationFormRecordMutationView(
        "record-1", 0, FormRecordState.SUBMITTED, new ApplicationFormRecordView(
        "record-1",
        0,
        FormRecordState.SUBMITTED,
        java.util.Map.of("name-field", "Ada")
    )));

    mvc.perform(post("/runtime/applications/app-1/pages/page-1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "runtime": {"formId": "form-1", "formVersion": 3},
                  "idempotencyKey": "create-record-1",
                  "state": "SUBMITTED",
                  "values": {"name-field": "Ada"}
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recordId").value("record-1"))
        .andExpect(jsonPath("$.state").value("SUBMITTED"))
        .andExpect(jsonPath("$.currentRecord.values.name-field").value("Ada"));

    var command = ArgumentCaptor.forClass(
        org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand.class
    );
    verify(recordService).create(
        org.mockito.ArgumentMatchers.eq("app-1"),
        org.mockito.ArgumentMatchers.eq("page-1"),
        command.capture()
    );
    assertThat(command.getValue().values()).containsOnlyKeys("name-field");
    assertThat(command.getValue().runtime().formId()).isEqualTo("form-1");
    assertThat(command.getValue().runtime().formVersion()).isEqualTo(3);
  }

  @Test
  void runtimeUpdatePreservesItsOperationKeyAndCanAcknowledgeAnUnavailableCurrentRecord() throws Exception {
    when(recordService.update(any(), any(), any(), any())).thenReturn(new ApplicationFormRecordMutationView(
        "record-1", 4, FormRecordState.SUBMITTED, null));
    mvc.perform(put("/runtime/applications/app-1/pages/page-1/records/record-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"runtime":{"formId":"form-1","formVersion":3},"expectedVersion":3,
                 "idempotencyKey":"update-1","state":"SUBMITTED","values":{"name-field":"Ada"}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recordId").value("record-1"))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.currentRecord").doesNotExist());
    var command = ArgumentCaptor.forClass(
        org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand.class);
    verify(recordService).update(org.mockito.ArgumentMatchers.eq("app-1"), org.mockito.ArgumentMatchers.eq("page-1"),
        org.mockito.ArgumentMatchers.eq("record-1"), command.capture());
    assertThat(command.getValue().idempotencyKey()).isEqualTo("update-1");
    assertThat(command.getValue().expectedVersion()).isEqualTo(3);
  }

  @Test
  void runtimeDeleteRequiresAndForwardsTheExactOperationKey() throws Exception {
    String path = "/runtime/applications/app-1/pages/page-1/records/record-1";
    mvc.perform(delete(path).param("formId", "form-1").param("formVersion", "3").param("expectedVersion", "4"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(recordService);
    mvc.perform(delete(path).param("formId", "form-1").param("formVersion", "3").param("expectedVersion", "4")
            .param("idempotencyKey", "delete-1"))
        .andExpect(status().isOk());
    verify(recordService).delete("app-1", "page-1", "record-1", 4,
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity("form-1", 3), "delete-1");
  }

  @Test
  void stalePublicationReturnsConflictInsteadOfWritingAgainstTheNewForm() throws Exception {
    when(recordService.create(any(), any(), any())).thenThrow(new ApplicationFormException(
        ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT,
        "The published form has changed"
    ));

    mvc.perform(post("/runtime/applications/app-1/pages/page-1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "runtime": {"formId": "form-1", "formVersion": 1},
                  "idempotencyKey": "create-record-1",
                  "state": "DRAFT",
                  "values": {}
                }
                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RUNTIME_VERSION_CONFLICT"));
  }

  private static ApplicationFormView formView() {
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    return new ApplicationFormView(
        "form-1",
        "app-1",
        "customer",
        "Customer",
        "Customer profile",
        ApplicationFormStatus.DRAFT,
        0,
        0,
        null,
        0,
        now,
        now
    );
  }

  private static ApplicationFormDraftView draftView() {
    FormComponentDefinition root = new FormComponentDefinition(
        "root",
        "layout.form",
        ApplicationFormApiVersion.RENDERER_V1,
        null,
        24,
        null,
        null,
        null,
        null,
        false,
        java.util.Map.of(),
        List.of()
    );
    ApplicationFormDocument document = new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(),
        List.of(),
        List.of(new FormSceneDefinition(
            "main",
            "main",
            "Main",
            FormSceneType.MAIN,
            null,
            root
        )),
        List.of()
    );
    return new ApplicationFormDraftView(
        "form-1",
        4,
        document,
        "checksum",
        "user-1",
        Instant.parse("2026-10-13T00:00:00Z")
    );
  }

  private static ApplicationFormPublicationView publicationView() {
    return publicationView(ApplicationFormPublicationStatus.DATA_READY);
  }

  private static ApplicationFormPublicationView publicationView(
      final ApplicationFormPublicationStatus status
  ) {
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    return new ApplicationFormPublicationView(
        "publication-1",
        "form-1",
        3,
        status,
        ApplicationFormPublicationStep.PAGE_RESOURCE,
        "migration-1",
        null,
        1,
        now,
        now
    );
  }

  private static ApplicationFormPageBindingView bindingView() {
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    return new ApplicationFormPageBindingView(
        "binding-1",
        "app-1",
        "page-1",
        "form-1",
        3,
        "main",
        ApplicationFormPageBindingStatus.ACTIVE,
        "resource-1",
        "app.runtime.page.one",
        "/app/runtime/app-1/page-1",
        null,
        1,
        now,
        now,
        now
    );
  }
}
