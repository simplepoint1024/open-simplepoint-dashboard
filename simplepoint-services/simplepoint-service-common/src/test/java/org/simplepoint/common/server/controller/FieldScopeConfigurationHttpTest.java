package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.FieldScopeService;
import org.simplepoint.plugin.rbac.core.rest.controller.FieldScopeController;
import org.simplepoint.security.entity.FieldAccessType;
import org.simplepoint.security.entity.FieldScope;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FieldScopeConfigurationHttpTest {
  @Test void configurationBindsQueryIdAndNestedEntriesWithProductionJsonConverter() throws Exception {
    var service = mock(FieldScopeService.class);
    var result = new FieldScope(); result.setId("scope-1"); result.setName("字段规则");
    when(service.createConfigured(any())).thenReturn(result);
    when(service.updateConfigured(eq("scope-1"), any())).thenAnswer(invocation -> {
      FieldScopeService.Configuration body = invocation.getArgument(1);
      assertThat(body.name()).isEqualTo("字段规则");
      assertThat(body.entries()).hasSize(1);
      assertThat(body.entries().getFirst().getResource()).isEqualTo("org.simplepoint.remote.Person");
      assertThat(body.entries().getFirst().getField()).isEqualTo("email");
      assertThat(body.entries().getFirst().getAccess()).isEqualTo(FieldAccessType.MASKED);
      return result;
    });
    var mvc = MockMvcBuilders.standaloneSetup(new FieldScopeController(service))
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
    String body = """
        {"name":"字段规则","description":"说明","entries":[
          {"resource":"org.simplepoint.remote.Person","field":"email","access":"MASKED"}]}
        """;
    mvc.perform(post("/field-scopes/configuration").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(put("/field-scopes/configuration").param("id", "scope-1")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    verify(service).updateConfigured(eq("scope-1"), any());
    mvc.perform(put("/field-scopes/configuration").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
