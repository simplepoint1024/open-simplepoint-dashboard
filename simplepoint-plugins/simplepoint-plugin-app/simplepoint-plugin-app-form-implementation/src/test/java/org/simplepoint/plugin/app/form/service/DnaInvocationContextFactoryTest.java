package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    authorization.setAttributes(Map.of(
        "X-Tenant-Id", "tenant-a",
        "X-Org-Dept-Id", "dept-a"
    ));
    authorization.setResourceDataScopes(Map.of(
        "app.forms.orders",
        new ResourceDataScope(
            "CUSTOM",
            Set.of("dept-a"),
            true,
            List.of("owner == current.userId")
        )
    ));
    authorization.setResourceFieldPermissions(Map.of(
        "app.forms.orders",
        Map.of("dna.runtime.record.model-1#title", "MASKED")
    ));
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        authorization
    );
    ResourcePolicyContext.set("app.forms.orders");

    DnaInvocationContext result = new DnaInvocationContextFactory(
        new FormRequestContext()
    ).current();

    assertThat(result.tenantId()).isEqualTo("tenant-a");
    assertThat(result.actorId()).isEqualTo("user-a");
    assertThat(result.organizationId()).isEqualTo("dept-a");
    assertThat(result.resourceCode()).isEqualTo("app.forms.orders");
    assertThat(result.dataScopeType()).isEqualTo("CUSTOM");
    assertThat(result.departmentIds()).containsExactly("dept-a");
    assertThat(result.dataScopeIncludeSelf()).isTrue();
    assertThat(result.dataScopeExpressions()).containsExactly("owner == current.userId");
    assertThat(result.fieldPermissions()).containsEntry(
        "dna.runtime.record.model-1#title",
        "MASKED"
    );
  }

  @Test
  void selectsDynamicPagePolicyWithoutTrustingTheStaticHostRoute() {
    AuthorizationContext authorization = new AuthorizationContext();
    authorization.setUserId("user-a");
    authorization.setIsAdministrator(false);
    authorization.setResources(Set.of("app.runtime.page.one"));
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    authorization.setResourceDataScopes(Map.of(
        "app.runtime.page.one",
        new ResourceDataScope("SELF", Set.of(), false, List.of())
    ));
    authorization.setResourceFieldPermissions(Map.of(
        "app.runtime.page.one",
        Map.of("dna.runtime.record.model-a#title", "EDITABLE")
    ));
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        authorization
    );
    ResourcePolicyContext.set("app.runtime.host");

    DnaInvocationContext result = new DnaInvocationContextFactory(
        new FormRequestContext()
    ).forResource("app.runtime.page.one");

    assertThat(result.resourceCode()).isEqualTo("app.runtime.page.one");
    assertThat(result.dataScopeType()).isEqualTo("SELF");
    assertThat(result.fieldPermissions()).containsEntry(
        "dna.runtime.record.model-a#title",
        "EDITABLE"
    );

    DnaInvocationContext validation = new DnaInvocationContextFactory(
        new FormRequestContext()
    ).forResourceValidation("app.runtime.page.one");
    assertThat(validation.resourceCode()).isEqualTo("app.runtime.page.one");
    assertThat(validation.dataScopeType()).isEqualTo("SELF");
    assertThat(validation.fieldPermissions()).isEmpty();
  }
}
