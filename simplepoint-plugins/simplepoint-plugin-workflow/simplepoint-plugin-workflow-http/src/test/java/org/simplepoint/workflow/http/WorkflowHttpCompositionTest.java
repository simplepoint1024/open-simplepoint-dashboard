package org.simplepoint.workflow.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.service.WorkflowInboxQueries;
import org.simplepoint.plugin.workflow.service.WorkflowMatterQueries;
import org.simplepoint.plugin.workflow.service.WorkflowNumericLiteralPreview;
import org.simplepoint.plugin.workflow.service.WorkflowOverviewQueries;
import org.simplepoint.plugin.workflow.service.WorkflowPriorityLiteralPreview;
import org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry;
import org.simplepoint.security.oauth2.resourceserver.scope.ResourceScopeHandlerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Real packaged HTTP adapters and method/scope interception; domain calls are explicit test
 * doubles.
 */
class WorkflowHttpCompositionTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withUserConfiguration(Adapters.class);

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void samePackagedControllerMappingsRemainEquivalentInLegacyAndModularHosts() {
    var legacyPaths = new java.util.TreeSet<String>();
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          context
              .getBean(RequestMappingHandlerMapping.class)
              .getHandlerMethods()
              .forEach(
                  (route, handler) ->
                      route
                          .getPatternValues()
                          .forEach(
                              path -> legacyPaths.add(route.getMethodsCondition() + " " + path)));
        });
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var modularPaths = new java.util.TreeSet<String>();
              context
                  .getBean(RequestMappingHandlerMapping.class)
                  .getHandlerMethods()
                  .forEach(
                      (route, handler) ->
                          route
                              .getPatternValues()
                              .forEach(
                                  path -> {
                                    assertThat(path).startsWith("/workflow/");
                                    modularPaths.add(
                                        route.getMethodsCondition()
                                            + " "
                                            + path.substring("/workflow".length()));
                                  }));
              assertThat(legacyPaths.size()).isGreaterThan(25);
              assertThat(modularPaths).isEqualTo(legacyPaths);
              assertThat(
                      context.getBean(ModulePlan.class).modules().stream()
                          .map(module -> module.id())
                          .toList())
                  .containsExactly("workflow", "workflow-http");
            });
  }

  @Test
  void relocatedEndpointsKeepScopeMethodPermissionsAndStructuredErrors() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var inbox = context.getBean(WorkflowInboxQueries.class);
              final var commands = context.getBean(WorkflowCenterService.class);
              when(inbox.query(anyString(), anyMap(), any()))
                  .thenReturn(new PageResult<>(List.of(), 0, 20, 0));
              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.TENANT);
              policy.setIsAdministrator(false);
              policy.setResources(Set.of("workflow.tasks.view"));
              SecurityContextHolder.getContext()
                  .setAuthentication(
                      new UsernamePasswordAuthenticationToken(
                          "reader",
                          null,
                          List.of(new SimpleGrantedAuthority("workflow.tasks.view"))));
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(
                      get("/workflow/inbox/pending")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.totalElements").value(0));
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post("/workflow/tasks/task/complete")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"variables\":{}}")))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(commands);
              when(inbox.query(anyString(), anyMap(), any()))
                  .thenThrow(
                      new WorkflowException(
                          WorkflowException.Code.DIRECTORY_PREPARING, "Preparing directory"));
              mvc.perform(
                      get("/workflow/inbox/pending")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isServiceUnavailable())
                  .andExpect(jsonPath("$.messageKey").value("workflow.error.DIRECTORY_PREPARING"));
              var platformPolicy = new AuthorizationContext();
              platformPolicy.setScopeType(AuthorizationScopeType.PLATFORM);
              platformPolicy.setIsAdministrator(false);
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              get("/workflow/inbox/pending")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
                                      platformPolicy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              mvc.perform(get("/inbox/pending")).andExpect(status().isNotFound());
            });
  }

  private WebApplicationContextRunner modular() {
    return runner.withBean(
        ModulePlan.class,
        () -> ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("workflow-http")));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = "org.simplepoint.workflow.rest",
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter({
            org.springframework.stereotype.Controller.class,
            org.springframework.web.bind.annotation.ControllerAdvice.class
          }))
  @EnableMethodSecurity
  @EnableSpringDataWebSupport
  static class Adapters {
    @Bean
    WorkflowCenterService center() {
      return mock(WorkflowCenterService.class);
    }

    @Bean
    WorkflowInboxQueries inbox() {
      return mock(WorkflowInboxQueries.class);
    }

    @Bean
    WorkflowMatterQueries matters() {
      return mock(WorkflowMatterQueries.class);
    }

    @Bean
    WorkflowOverviewQueries overview() {
      return mock(WorkflowOverviewQueries.class);
    }

    @Bean
    WorkflowNumericLiteralPreview literal() {
      return new WorkflowNumericLiteralPreview();
    }

    @Bean
    WorkflowPriorityLiteralPreview priority() {
      return new WorkflowPriorityLiteralPreview();
    }

    @Bean
    MappedInterceptor scopes(org.springframework.beans.factory.ObjectProvider<ModulePlan> modules)
        throws Exception {
      return new MappedInterceptor(
          null,
          new ResourceScopeHandlerInterceptor(
              new ClasspathResourceScopeRegistry(
                  new ObjectMapper(), modules.getIfAvailable(), getClass().getClassLoader())));
    }
  }
}
