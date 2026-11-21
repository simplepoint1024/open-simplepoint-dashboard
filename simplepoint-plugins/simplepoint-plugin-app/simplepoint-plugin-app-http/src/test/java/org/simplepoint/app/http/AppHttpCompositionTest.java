package org.simplepoint.app.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.app.catalog.controller.WorkspaceApplicationController;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog;
import org.simplepoint.app.form.entity.ApplicationFormReleaseReadModel;
import org.simplepoint.app.form.service.CurrentFormPublicationRecoveryAuthority;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry;
import org.simplepoint.security.oauth2.resourceserver.scope.ResourceScopeHandlerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Actual MVC adapters and permission interceptors; domain collaborators are explicit test doubles.
 */
class AppHttpCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainOwnsQueriesRecoveryAndReadModelsWithoutOpeningHttp() {
    var domain = catalog.plan(List.of("app-form"));
    assertThat(ModuleHttpPlan.from(domain).routes()).isEmpty();
    assertThat(domain.ownerOf(WorkspaceApplicationController.class)).isEmpty();
    for (Class<?> type :
        List.of(
            WorkspaceApplicationCatalog.class,
            CurrentFormPublicationRecoveryAuthority.class,
            ApplicationFormReleaseReadModel.class)) {
      assertThat(domain.ownerOf(type).orElseThrow().id()).isEqualTo("app-form");
    }
  }

  @Test
  void everyControllerMappingIsPreservedWhenMountedInTheAggregate() {
    var original = new TreeSet<String>();
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              context
                  .getBean(RequestMappingHandlerMapping.class)
                  .getHandlerMethods()
                  .forEach(
                      (route, method) ->
                          route
                              .getPatternValues()
                              .forEach(
                                  path -> original.add(route.getMethodsCondition() + " " + path)));
            });
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mounted = new TreeSet<String>();
              context
                  .getBean(RequestMappingHandlerMapping.class)
                  .getHandlerMethods()
                  .forEach(
                      (route, method) ->
                          route
                              .getPatternValues()
                              .forEach(
                                  path -> {
                                    assertThat(path).startsWith("/app/");
                                    mounted.add(
                                        route.getMethodsCondition() + " " + path.substring(4));
                                  }));
              assertThat(mounted).isEqualTo(original);
              assertThat(mounted.size()).isGreaterThan(30);
              assertThat(
                      context.getBean(ModulePlan.class).modules().stream()
                          .map(module -> module.id()))
                  .containsExactly("app-form", "app-http");
            });
  }

  @Test
  void mountedApplicationDirectoryRetainsPermissionsScopeAndDomainErrors() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var applications = context.getBean(WorkspaceApplicationCatalog.class);
              when(applications.schema()).thenReturn(Map.of());
              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.TENANT);
              policy.setIsAdministrator(false);
              policy.setResources(Set.of("app.workbench.view"));
              authenticate("app.workbench.view");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(
                      get("/app/applications/schema")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk());
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post("/app/applications")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"navigationGroup\":false}")))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(applications, never()).create(any());
              when(applications.detail("absent"))
                  .thenThrow(
                      new ApplicationFormException(
                          ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
                          "Application unavailable"));
              mvc.perform(
                      get("/app/applications/absent")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isNotFound())
                  .andExpect(jsonPath("$.messageKey").value("app.error.APPLICATION_NOT_FOUND"));
              authenticate("app.workbench.view", "app.workbench.apps.create");
              // AuthorizationContext is immutable after initialization, just as on a new request.
              var createPolicy = new AuthorizationContext();
              createPolicy.setScopeType(AuthorizationScopeType.TENANT);
              createPolicy.setIsAdministrator(false);
              createPolicy.setResources(Set.of("app.workbench.view", "app.workbench.apps.create"));
              mvc.perform(
                      post("/app/applications")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, createPolicy)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              "{\"name\":\"Business\",\"mainPageName\":\"Records\",\"navigationGroup\":false}"))
                  .andExpect(status().isOk());
              verify(applications).create(any());
              createPolicy.replaceScopeType(AuthorizationScopeType.PERSONAL);
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              get("/app/applications/schema")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, createPolicy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              mvc.perform(get("/applications/schema")).andExpect(status().isNotFound());
            });
  }

  private void authenticate(String... permissions) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "operator",
                null,
                java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
  }

  private WebApplicationContextRunner modular() {
    return runner().withBean(ModulePlan.class, () -> catalog.plan(List.of("app-http")));
  }

  private WebApplicationContextRunner runner() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.setEnvironment(
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("simplepoint.events.enabled", "true"));
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    scanner.addExcludeFilter(
        new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*\\$.*")));
    Set<Class<?>> dependencies = new HashSet<>();
    for (String root :
        List.of("org.simplepoint.app.form.controller", "org.simplepoint.app.catalog.controller")) {
      for (var bean : scanner.findCandidateComponents(root)) {
        try {
          Class<?> type = Class.forName(bean.getBeanClassName());
          for (var constructor : type.getConstructors()) {
            for (int index = 0; index < constructor.getParameterCount(); index++) {
              var parameter = org.springframework.core.ResolvableType.forConstructorParameter(constructor, index);
              dependencies.add(parameter.resolve() == ObjectProvider.class
                  ? parameter.getGeneric(0).resolve() : parameter.resolve());
            }
          }
        } catch (ClassNotFoundException exception) {
          throw new AssertionError(exception);
        }
      }
    }
    var runner =
        new WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
            .withPropertyValues("simplepoint.events.enabled=true")
            .withUserConfiguration(Adapters.class);
    for (Class<?> type : dependencies) {
      runner = withDomainDouble(runner, type);
    }
    return runner;
  }

  private <T> WebApplicationContextRunner withDomainDouble(
      WebApplicationContextRunner runner, Class<T> type) {
    assertThat(type.getPackageName()).startsWith("org.simplepoint.");
    assertThat(catalog.plan(List.of("app-form")).ownerOf(type)).isPresent();
    return runner.withBean(type, () -> mock(type));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = {
        "org.simplepoint.app.form.controller",
        "org.simplepoint.app.catalog.controller"
      },
      useDefaultFilters = false,
      excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\$.*"),
      includeFilters =
          @ComponentScan.Filter({
            org.springframework.stereotype.Controller.class,
            org.springframework.web.bind.annotation.ControllerAdvice.class
          }))
  @EnableMethodSecurity
  @EnableSpringDataWebSupport
  static class Adapters {
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
