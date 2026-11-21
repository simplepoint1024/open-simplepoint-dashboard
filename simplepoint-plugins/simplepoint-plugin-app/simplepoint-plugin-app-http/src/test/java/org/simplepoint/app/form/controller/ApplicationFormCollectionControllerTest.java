package org.simplepoint.app.form.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCollectionService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCollectionService.Scope;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormCollectionControllerTest {
  private static final String BASE =
      "/runtime/applications/app-id/pages/page-id/records/record-id/collections";
  private static final String QUERY = "?formId=form-id&formVersion=2&inverse=true";
  private final ApplicationFormCollectionService service =
      mock(ApplicationFormCollectionService.class);
  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ApplicationFormCollectionController(service)).build();

  @Test
  void resolvesExplicitPathAndQueryParameterNames() throws Exception {
    mvc.perform(get(BASE + QUERY)).andExpect(status().isOk());
    verify(service)
        .views("app-id", "page-id", new ApplicationFormRuntimeIdentity("form-id", 2), "record-id");

    mvc.perform(get(BASE + "/members/schema" + QUERY + "&candidates=true"))
        .andExpect(status().isOk());
    verify(service).schema("app-id", "page-id", scope(), true);
  }

  @Test
  void resolvesBothPageAndMutationScopesThroughMvc() throws Exception {
    mvc.perform(
            post(BASE + "/members/query" + QUERY)
                .contentType("application/json")
                .content("{\"page\":0,\"size\":50}"))
        .andExpect(status().isOk());
    verify(service).page(eq("app-id"), eq("page-id"), eq(scope()), eq(false), any());

    mvc.perform(
            post(BASE + "/members/observe" + QUERY)
                .contentType("application/json")
                .content("{\"memberId\":\"related-record\",\"link\":true}"))
        .andExpect(status().isOk());
    verify(service).observe(eq("app-id"), eq("page-id"), eq(scope()), any());

    mvc.perform(
            post(BASE + "/members/mutations" + QUERY)
                .contentType("application/json")
                .content(
                    "{\"anchorVersion\":7,\"edges\":[],\"link\":true,\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isOk());
    verify(service).execute(eq("app-id"), eq("page-id"), eq(scope()), any());
  }

  private static Scope scope() {
    return new Scope(
        new ApplicationFormRuntimeIdentity("form-id", 2), "record-id", "members", true);
  }
}
