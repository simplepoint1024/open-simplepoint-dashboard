package org.simplepoint.plugin.personal.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.personal.api.PersonalModels.Overview;
import org.simplepoint.plugin.personal.api.PersonalService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalControllerTest {
  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean
    PersonalService service() {
      return mock(PersonalService.class);
    }

    @Bean
    PersonalSchemaController schemaController() {
      return new PersonalSchemaController(
          mock(org.simplepoint.api.security.service.DetailsProviderService.class));
    }

    @Bean
    PersonalController controller(PersonalService service) {
      return new PersonalController(service);
    }
  }

  private AnnotationConfigApplicationContext context;
  private PersonalController controller;
  private PersonalService service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    controller = context.getBean(PersonalController.class);
    service = context.getBean(PersonalService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new PersonalExceptionHandler())
            .setCustomArgumentResolvers(
                new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user", null, List.of()));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  @Test
  void anonymousCannotAccessPersonalApis() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> controller.overview())
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    assertThatThrownBy(() -> context.getBean(PersonalSchemaController.class).items())
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
  }

  @Test
  void overviewIsRawJsonAndDoesNotExposeOwnerIdentifiers() throws Exception {
    when(service.overview()).thenReturn(new Overview(1, 0, 2, 3, List.of(), List.of(), List.of()));
    mvc.perform(get("/personal/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeItems").value(1))
        .andExpect(jsonPath("$.unreadMessages").value(3))
        .andExpect(jsonPath("$.ownerId").doesNotExist());
  }

  @Test
  void foreignOrMissingRecordsReturnNonSensitive404() throws Exception {
    when(service.item("foreign")).thenThrow(new NoSuchElementException("internal-private-data"));
    mvc.perform(get("/personal/items/foreign"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("记录不存在或无权访问"));
  }

  @Test
  void invalidWriteAndVersionConflictHaveStableStatuses() throws Exception {
    when(service.createItem(any())).thenThrow(new IllegalArgumentException("标题不能为空"));
    mvc.perform(
            post("/personal/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"priority\":1,\"pinned\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("标题不能为空"));
    when(service.itemAction(any(), any()))
        .thenThrow(new OptimisticLockingFailureException("database-details"));
    mvc.perform(
            post("/personal/items/x/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"action\":\"START\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void headersKeepFiltersSeparateFromViewParametersAndForwardSort() throws Exception {
    when(service.items(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            org.springframework.data.domain.Page.empty(
                org.springframework.data.domain.PageRequest.of(0, 20)));
    mvc.perform(
            get("/personal/items")
                .param("status", "TODO")
                .param(
                    "filters",
                    "{\"status\":\"in:TODO,IN_PROGRESS\",\"dueAt\":\"than:greater:2026-09-18T10:30:00Z\"}")
                .param("sort", "title,desc")
                .param("page", "1"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(service)
        .items(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("TODO"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACTIVE"),
            org.mockito.ArgumentMatchers.eq(
                org.springframework.data.domain.PageRequest.of(
                    1, 20, org.springframework.data.domain.Sort.by("title").descending())),
            org.mockito.ArgumentMatchers.eq(
                java.util.Map.of(
                    "status",
                    "in:TODO,IN_PROGRESS",
                    "dueAt",
                    "than:greater:2026-09-18T10:30:00Z")));
  }

  @Test
  void malformedHeaderPayloadReturns400BeforeCallingService() throws Exception {
    for (String filters : List.of("[]", "null", "{", "{\"title\":{}}", "{} {}", "x".repeat(8193))) {
      mvc.perform(get("/personal/items").param("filters", filters))
          .andExpect(status().isBadRequest());
    }
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void businessDeliveryHasNoPublicHttpEndpoint() throws Exception {
    mvc.perform(post("/personal/messages").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed());
  }
}
