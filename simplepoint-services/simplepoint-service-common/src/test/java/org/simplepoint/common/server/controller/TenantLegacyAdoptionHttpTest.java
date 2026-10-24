package org.simplepoint.common.server.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance.Acquisition;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantLegacyAdoptionController;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantPackageExceptionHandler;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantLegacyAdoptionServiceImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantLegacyAdoptionHttpTest {
  private static final String PATH = "/platform/tenants/legacy-tenant/resource-adoption";

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void previewIsReadOnlyAndExecutionReturnsTheDomainReceipt() throws Exception {
    var service = mock(TenantLegacyAdoptionService.class);
    var mvc = mvc(service);
    when(service.preview("legacy-tenant"))
        .thenReturn(
            new TenantLegacyAdoption.Preview(
                "legacy-tenant",
                4,
                3,
                1,
                List.of(
                    new TenantLegacyAdoption.Source(null, null, Acquisition.LEGACY_PUBLIC, 3))));
    mvc.perform(get(PATH + "/preview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value("legacy-tenant"))
        .andExpect(jsonPath("$.sources[0].acquisition").value("LEGACY_PUBLIC"))
        .andExpect(jsonPath("$.sources[0].packageCode").isEmpty())
        .andExpect(jsonPath("$.legacyResourceCount").value(3));
    verify(service, never()).adopt("legacy-tenant");
    when(service.adopt("legacy-tenant"))
        .thenReturn(
            new TenantLegacyAdoption(
                "legacy-tenant", Instant.parse("2026-10-23T00:00:00Z"), "platform-admin", 3, 1));
    mvc.perform(post(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.adoptedBy").value("platform-admin"))
        .andExpect(jsonPath("$.resourceCount").value(3))
        .andExpect(jsonPath("$.instanceCount").value(1));
    verify(service).adopt("legacy-tenant");
  }

  @ParameterizedTest
  @EnumSource(AuthorizationScopeType.class)
  void realDomainRejectsWrongScopeOrOrdinaryPlatformUserBeforePersistence(
      AuthorizationScopeType scope) throws Exception {
    final var em = mock(EntityManager.class);
    final var versions = mock(ResourceAuthorizationVersionService.class);
    var context = new AuthorizationContext();
    context.setUserId("ordinary-user");
    context.setAttributes(java.util.Map.of("X-Tenant-Id", "other-tenant"));
    context.setScopeType(scope);
    context.setIsAdministrator(scope != AuthorizationScopeType.PLATFORM);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertForbidden(
        mvc(
            new TenantLegacyAdoptionServiceImpl(
                new org.simplepoint.plugin.rbac.tenant.service.impl.TenantLegacyAdoptionStore(
                    em, versions))));
    verifyNoInteractions(em, versions);
  }

  @Test
  void realDomainRejectsMissingAuthorizationContextBeforePersistence() throws Exception {
    final var em = mock(EntityManager.class);
    final var versions = mock(ResourceAuthorizationVersionService.class);
    assertForbidden(
        mvc(
            new TenantLegacyAdoptionServiceImpl(
                new org.simplepoint.plugin.rbac.tenant.service.impl.TenantLegacyAdoptionStore(
                    em, versions))));
    verifyNoInteractions(em, versions);
  }

  @ParameterizedTest
  @EnumSource(TenantPackageException.Code.class)
  void errorsHaveBoundedNamespacedDiagnostics(TenantPackageException.Code code) throws Exception {
    var service = mock(TenantLegacyAdoptionService.class);
    var error = new TenantPackageException(code);
    error.initCause(new IllegalStateException("private SQL and submitted values"));
    when(service.adopt("legacy-tenant")).thenThrow(error);
    int expected =
        switch (code) {
          case OWNER_REQUIRED, PLATFORM_ADMIN_REQUIRED -> 403;
          case INSTANCE_NOT_FOUND, TENANT_NOT_FOUND -> 404;
          case INVALID_DURATION, INVALID_REQUEST -> 400;
          case RESOURCE_UNAVAILABLE, PACKAGE_UNAVAILABLE -> 503;
          case LEGACY_ASSIGNMENT_RETIRED -> 410;
          default -> 409;
        };
    mvc(service)
        .perform(post(PATH))
        .andExpect(status().is(expected))
        .andExpect(jsonPath("$.code").value("TENANT_PACKAGE:" + code))
        .andExpect(jsonPath("$.messageKey").value("tenant.packages.error." + code))
        .andExpect(jsonPath("$.message").doesNotExist())
        .andExpect(jsonPath("$.cause").doesNotExist());
  }

  private static void assertForbidden(MockMvc mvc) throws Exception {
    for (var request : List.of(get(PATH + "/preview"), post(PATH))) {
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("TENANT_PACKAGE:PLATFORM_ADMIN_REQUIRED"));
    }
  }

  private static MockMvc mvc(TenantLegacyAdoptionService service) {
    return MockMvcBuilders.standaloneSetup(new TenantLegacyAdoptionController(service))
        .setControllerAdvice(new TenantPackageExceptionHandler())
        .build();
  }
}
