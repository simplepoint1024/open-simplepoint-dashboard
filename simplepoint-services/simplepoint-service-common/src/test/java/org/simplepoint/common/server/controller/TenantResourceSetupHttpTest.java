package org.simplepoint.common.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantController;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantResourceExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantResourceSetupHttpTest {
  @ParameterizedTest
  @EnumSource(TenantResourceSetupException.Code.class)
  void directoryPreparationErrorsHaveStableLocalizedEnvelopes(TenantResourceSetupException.Code code)
      throws Exception {
    var service = mock(TenantService.class);
    var error = new TenantResourceSetupException(code);
    error.initCause(new IllegalStateException("internal SQL and private resource details"));
    when(service.create(any(Tenant.class))).thenThrow(error);
    var mvc = MockMvcBuilders.standaloneSetup(new TenantController(service))
        .setControllerAdvice(new TenantResourceExceptionHandler()).build();
    int expected = switch (code) {
      case OWNER_REQUIRED -> 403;
      case TENANT_UNAVAILABLE -> 404;
      case ADOPTION_REQUIRED, RESOURCE_CONFLICT -> 409;
      case CATALOG_NOT_READY, RESOURCE_UNAVAILABLE -> 503;
    };
    for (String path : java.util.List.of("/tenants", "/platform/tenants")) {
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New tenant\"}"))
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.code").value("TENANT_RESOURCE_SETUP:" + code))
          .andExpect(jsonPath("$.messageKey").value(error.messageKey()))
          .andExpect(jsonPath("$.message").doesNotExist())
          .andExpect(jsonPath("$.cause").doesNotExist());
    }
  }

  @ParameterizedTest
  @EnumSource(TenantResourceSetupException.Code.class)
  void accountCreationPreservesPersonalWorkspaceFailureKeys(TenantResourceSetupException.Code code) throws Exception {
    var service = mock(org.simplepoint.plugin.rbac.core.api.service.PlatformAccountService.class);
    when(service.create(any())).thenThrow(new TenantResourceSetupException(code));
    var mvc = MockMvcBuilders.standaloneSetup(
        new org.simplepoint.plugin.rbac.core.rest.controller.PlatformAccountController(service))
        .setControllerAdvice(new TenantResourceExceptionHandler()).build();
    mvc.perform(post("/platform/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(jsonPath("$.code").value("TENANT_RESOURCE_SETUP:" + code))
        .andExpect(jsonPath("$.messageKey").value("tenant.resources.error." + code))
        .andExpect(jsonPath("$.cause").doesNotExist());
  }
}
