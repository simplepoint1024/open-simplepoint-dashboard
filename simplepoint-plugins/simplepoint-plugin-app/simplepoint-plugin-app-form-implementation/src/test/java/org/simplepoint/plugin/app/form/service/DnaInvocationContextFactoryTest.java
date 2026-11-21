package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;

class DnaInvocationContextFactoryTest {

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void capturesTheServerSelectedResourcePolicyForDna() {
    AuthorizationContext authorization = new AuthorizationContext();
    authorization.setUserId("user-a");
    authorization.setIsAdministrator(false);
    authorization.setResources(Set.of("app.forms.orders"));
    authorization.setAttributes(
        Map.of(
            "X-Tenant-Id", "tenant-a",
            "X-Org-Dept-Id", "dept-a"));
    authorization.setResourceDataScopes(
        Map.of(
            "app.forms.orders",
            new ResourceDataScope(
                "CUSTOM", Set.of("dept-a"), true, List.of("owner == current.userId"))));
    authorization.setResourceFieldPermissions(
        Map.of("app.forms.orders", Map.of("dna.runtime.record.model-1#title", "MASKED")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    ResourcePolicyContext.set("app.forms.orders");

    DnaInvocationContext result =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class)).current();

    assertThat(result.tenantId()).isEqualTo("tenant-a");
    assertThat(result.actorId()).isEqualTo("user-a");
    assertThat(result.organizationId()).isEqualTo("dept-a");
    assertThat(result.resourceCode()).isEqualTo("app.forms.orders");
    assertThat(result.dataScopeType()).isEqualTo("CUSTOM");
    assertThat(result.departmentIds()).containsExactly("dept-a");
    assertThat(result.dataScopeIncludeSelf()).isTrue();
    assertThat(result.dataScopeExpressions()).containsExactly("owner == current.userId");
    assertThat(result.fieldPermissions())
        .containsEntry("dna.runtime.record.model-1#title", "MASKED");
  }

  @Test
  void selectsDynamicPagePolicyWithoutTrustingTheStaticHostRoute() {
    AuthorizationContext authorization = new AuthorizationContext();
    authorization.setUserId("user-a");
    authorization.setIsAdministrator(false);
    authorization.setResources(Set.of("app.runtime.page.one"));
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    authorization.setResourceDataScopes(
        Map.of("app.runtime.page.one", new ResourceDataScope("SELF", Set.of(), false, List.of())));
    authorization.setResourceFieldPermissions(
        Map.of("app.runtime.page.one", Map.of("dna.runtime.record.model-a#title", "EDITABLE")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    ResourcePolicyContext.set("app.runtime.host");

    DnaInvocationContext result =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
            .forResource("app.runtime.page.one");

    assertThat(result.resourceCode()).isEqualTo("app.runtime.page.one");
    assertThat(result.dataScopeType()).isEqualTo("SELF");
    assertThat(result.fieldPermissions())
        .containsEntry("dna.runtime.record.model-a#title", "EDITABLE");

    DnaInvocationContext validation =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
            .forResourceValidation("app.runtime.page.one");
    assertThat(validation.resourceCode()).isEqualTo("app.runtime.page.one");
    assertThat(validation.dataScopeType()).isEqualTo("SELF");
    assertThat(validation.fieldPermissions()).isEmpty();
  }

  @Test
  void capturesOnlyResolvedGrantedTargetsWithoutLendingSourcePermissions() {
    var auth = new AuthorizationContext();
    auth.setUserId("actor");
    auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    auth.setResources(Set.of("source", "target"));
    auth.setDataScopeType("DENY");
    auth.setResourceDataScopes(
        Map.of(
            "source",
            new ResourceDataScope("ALL", Set.of(), false),
            "target",
            new ResourceDataScope("SELF", Set.of(), false)));
    auth.setResourceFieldPermissions(
        Map.of(
            "source",
            Map.of(),
            "target",
            Map.of("dna.runtime.record.target-model#name", "MASKED")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
    ResourcePolicyContext.set("host");
    var source = form("tenant", "source");
    var target = form("tenant", "target");
    var result =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
            .forRelations(source, List.of(target, source, form("tenant", "ungranted")));
    assertThat(result.dataScopeType()).isEqualTo("ALL");
    assertThat(result.additionalResourcePolicies()).containsOnlyKeys("target");
    assertThat(result.additionalResourcePolicies().get("target").dataScopeType()).isEqualTo("SELF");
    assertThat(result.additionalResourcePolicies().get("target").fieldPermissions())
        .containsEntry("dna.runtime.record.target-model#name", "MASKED");
    assertThat(ResourcePolicyContext.get()).isEqualTo("host");
    assertThatThrownBy(
            () ->
                new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
                    .forRelations(source, List.of(form("other-tenant", "target"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void completeIdentityContextMaterializesRoleDefaultsWithoutInventingGrants() {
    for (boolean administrator : List.of(false, true)) {
      var auth = new AuthorizationContext();
      auth.setUserId("actor");
      auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
      auth.setIsAdministrator(administrator);
      auth.setResources(Set.of("source", "target"));
      auth.setResourcePolicyDefaultsResolved(true);
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
      var context =
          new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
              .forRelations(form("tenant", "source"), List.of(form("tenant", "target")));
      assertThat(context.dataScopeType()).isEqualTo(administrator ? "ALL" : "DENY");
      assertThat(context.additionalResourcePolicies().get("target").dataScopeType())
          .isEqualTo(administrator ? "ALL" : "DENY");
      assertThat(context.additionalResourcePolicies().get("target").fieldPermissions()).isEmpty();
    }
  }

  @Test
  void delegatedSourceScopeCannotBecomeMissingTargetPolicyEvenForAdministrator() {
    for (boolean administrator : List.of(false, true)) {
      for (boolean targetScopePresent : List.of(false, true)) {
        var auth = new AuthorizationContext();
        auth.setUserId("actor");
        auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
        auth.setIsAdministrator(administrator);
        auth.setResources(Set.of("source", "target"));
        auth.setDataScopeType("ALL");
        auth.setResourceDataScopes(
            targetScopePresent
                ? Map.of("target", new ResourceDataScope("SELF", Set.of(), false))
                : Map.of());
        auth.setResourceFieldPermissions(
            targetScopePresent ? Map.of() : Map.of("target", Map.of()));
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
        var context =
            new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
                .forRelations(form("tenant", "source"), List.of(form("tenant", "target")));
        assertThat(context.dataScopeType()).isEqualTo("ALL");
        assertThat(context.additionalResourcePolicies()).isEmpty();
      }
    }
  }

  @Test
  void completeIdentityContextRetainsOwnerDefaultsAndExplicitTargetRestrictions() {
    var auth = new AuthorizationContext();
    auth.setUserId("actor");
    auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    auth.setResources(Set.of("source", "target", "restricted"));
    auth.setDataScopeType("ALL");
    auth.setResourcePolicyDefaultsResolved(true);
    auth.setResourceDataScopes(
        Map.of("restricted", new ResourceDataScope("SELF", Set.of(), false)));
    auth.setResourceFieldPermissions(
        Map.of("restricted", Map.of("restricted-model#name", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
    var context =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
            .forRelations(
                form("tenant", "source"),
                List.of(
                    form("tenant", "target"),
                    form("tenant", "restricted"),
                    form("tenant", "ungranted")));
    assertThat(context.additionalResourcePolicies()).containsOnlyKeys("target", "restricted");
    assertThat(context.additionalResourcePolicies().get("target").dataScopeType()).isEqualTo("ALL");
    assertThat(context.additionalResourcePolicies().get("restricted").dataScopeType())
        .isEqualTo("SELF");
    assertThat(context.additionalResourcePolicies().get("restricted").fieldPermissions())
        .containsEntry("restricted-model#name", "HIDDEN");
  }

  @Test
  void writeCaptureResolvesTargetsAndPreservesTheirSeparatePolicies() {
    var auth = new AuthorizationContext();
    auth.setUserId("actor");
    auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    auth.setResources(Set.of("source", "target"));
    auth.setDataScopeType("ALL");
    auth.setResourceDataScopes(Map.of("target", new ResourceDataScope("SELF", Set.of(), false)));
    auth.setResourceFieldPermissions(Map.of("target", Map.of("private", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
    var relationResolver = org.mockito.Mockito.mock(FormRelationTargetResolver.class);
    var factory = new DnaInvocationContextFactory(new FormRequestContext(), relationResolver);
    var document = FormRelationPublicationProjectionTest.document();
    var source = new PublishedApplicationFormResolver.PublishedApplicationForm("tenant", "app", "source", "Source",
        "source", "Source", 1, "source", "source-model", 1, "a".repeat(64), document, 1, 1, List.of(), null,
        FormRelationPublicationProjectionTest.bindings(document));
    org.mockito.Mockito.when(relationResolver.availableForWrite(source)).thenReturn(List.of(form("tenant", "target")));
    var captured = factory.forWrite(source);
    assertThat(captured.dataScopeType()).isEqualTo("ALL");
    assertThat(captured.additionalResourcePolicies().get("target").dataScopeType()).isEqualTo("SELF");
    assertThat(captured.additionalResourcePolicies().get("target").fieldPermissions()).containsEntry("private", "HIDDEN");
    org.mockito.Mockito.when(relationResolver.availableForWrite(source)).thenReturn(List.of());
    assertThat(factory.forWrite(source).additionalResourcePolicies()).isEmpty();
    assertThat(factory.forForm(source).additionalResourcePolicies()).isEmpty();
    org.mockito.Mockito.verify(relationResolver, org.mockito.Mockito.times(2)).availableForWrite(source);
  }

  @Test
  void acceptedSubmissionRestoresEachRelatedPolicyAndThenTheOriginalContext() {
    var auth = new AuthorizationContext();
    auth.setUserId("actor");
    auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    auth.setResources(Set.of("source", "target"));
    auth.setDataScopeType("ALL");
    auth.setResourceDataScopes(Map.of("target", new ResourceDataScope("SELF", Set.of(), false)));
    auth.setResourceFieldPermissions(Map.of("target", Map.of("target-model#private", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
    ResourcePolicyContext.set("original");
    var source = form("tenant", "source");
    var data =
        new DnaInvocationContextFactory(new FormRequestContext(), org.mockito.Mockito.mock(FormRelationTargetResolver.class))
            .forRelations(source, List.of(form("tenant", "target")));
    var workflow =
        new org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext(
            "tenant", "actor", false, Set.of("source", "target"), Set.of());
    var accepted = new AcceptedFormSubmission(source, null, data, workflow, null, null);
    try (var ignored = new FormSubmissionAuthorization(accepted)) {
      var restored = org.simplepoint.core.AuthorizationContextHolder.getContext();
      assertThat(restored.isResourcePolicyDefaultsResolved()).isFalse();
      assertThat(restored.activeDataScope().type()).isEqualTo("ALL");
      assertThat(restored.dataScopeFor("target").type()).isEqualTo("SELF");
      assertThat(restored.fieldPermissionsFor("target"))
          .containsEntry("target-model#private", "HIDDEN");
    }
    assertThat(org.simplepoint.core.AuthorizationContextHolder.getContext()).isSameAs(auth);
    assertThat(ResourcePolicyContext.get()).isEqualTo("original");
  }

  private static PublishedApplicationFormResolver.PublishedApplicationForm form(
      String tenant, String resource) {
    var document =
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument(
            "1", List.of(), List.of(), List.of(), List.of());
    return new PublishedApplicationFormResolver.PublishedApplicationForm(
        tenant,
        "app",
        resource,
        resource,
        resource,
        resource,
        1,
        resource,
        resource + "-model",
        1,
        "a".repeat(64),
        document,
        1,
        1);
  }
}
