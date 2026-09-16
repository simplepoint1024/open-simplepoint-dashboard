package org.simplepoint.common.server.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.PlatformAccountService;
import org.simplepoint.plugin.rbac.core.api.service.TenantMemberService;
import org.simplepoint.plugin.rbac.core.rest.controller.PlatformAccountController;
import org.simplepoint.plugin.rbac.core.rest.controller.TenantMemberController;
import org.springframework.data.domain.Page;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Tests request argument binding independently of compiler parameter-name metadata. */
class PlatformAccountBindingTest {
  final PlatformAccountService accounts = mock(PlatformAccountService.class);
  final TenantMemberService members = mock(TenantMemberService.class);
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new PlatformAccountController(accounts), new TenantMemberController(members))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  @Test
  void accountFiltersBindAsNamedAttributeMap() throws Exception {
    when(accounts.list(any(), any())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0, 20)));
    mvc.perform(get("/platform/accounts").param("name", "like:member").param("page", "0")).andExpect(status().isOk());
    verify(accounts).list(argThat(filters -> "like:member".equals(filters.get("name"))), any());
    mvc.perform(get("/platform/accounts")).andExpect(status().isOk());
    verify(accounts).list(argThat(java.util.Map::isEmpty), any());
  }

  @Test
  void accountAndAuditSchemasUseDedicatedRoutes() throws Exception {
    when(accounts.accountSchema()).thenReturn(java.util.Map.of("schema", java.util.Map.of(), "buttons", java.util.Set.of()));
    when(accounts.auditSchema()).thenReturn(java.util.Map.of("schema", java.util.Map.of(), "buttons", java.util.Set.of()));
    mvc.perform(get("/platform/accounts/schema")).andExpect(status().isOk());
    mvc.perform(get("/platform/accounts/audit/schema")).andExpect(status().isOk());
    verify(accounts).accountSchema();
    verify(accounts).auditSchema();
  }

  @Test
  void accountAndIdentityUpdatesBindExplicitUserId() throws Exception {
    mvc.perform(put("/platform/accounts/user-1").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    mvc.perform(put("/platform/accounts/user-1/identity").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    verify(accounts).update(eq("user-1"), any());
    verify(accounts).assignIdentity(eq("user-1"), any());
  }

  @Test
  void memberUpdateAndRemovalBindExplicitUserId() throws Exception {
    mvc.perform(put("/tenant-members/user-1").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    mvc.perform(delete("/tenant-members/user-1")).andExpect(status().isOk());
    verify(members).update(eq("user-1"), any());
    verify(members).remove("user-1");
  }
}
