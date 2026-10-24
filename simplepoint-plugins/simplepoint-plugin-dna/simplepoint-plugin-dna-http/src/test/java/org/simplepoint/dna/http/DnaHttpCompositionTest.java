package org.simplepoint.dna.http;

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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.dna.model.controller.DnaDataModelController;
import org.simplepoint.dna.model.service.DnaDataModelCatalog;
import org.simplepoint.dna.runtime.service.RuntimeGovernanceCatalog;
import org.simplepoint.dna.runtime.service.RuntimeSchemaReview;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.dna.federation.api.error.DnaModelErrorCode;
import org.simplepoint.plugin.dna.federation.api.error.DnaModelException;
import org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry;
import org.simplepoint.security.oauth2.resourceserver.scope.ResourceScopeHandlerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
class DnaHttpCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainOwnsQueriesAndSchemaReviewWithoutOpeningHttp() {
    var domain = catalog.plan(List.of("dna-runtime"));
    assertThat(ModuleHttpPlan.from(domain).routes()).isEmpty();
    assertThat(domain.ownerOf(DnaDataModelController.class)).isEmpty();
    for (Class<?> type :
        List.of(
            DnaDataModelCatalog.class, RuntimeGovernanceCatalog.class, RuntimeSchemaReview.class)) {
      assertThat(domain.ownerOf(type)).isPresent();
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
                                    assertThat(path).startsWith("/dna/");
                                    mounted.add(
                                        route.getMethodsCondition() + " " + path.substring(4));
                                  }));
              assertThat(mounted).isEqualTo(original);
              assertThat(mounted.size()).isGreaterThan(30);
              assertThat(
                      context.getBean(ModulePlan.class).modules().stream()
                          .map(module -> module.id()))
                  .containsExactly("dna-core", "dna-federation", "dna-runtime", "dna-http");
            });
  }

  @Test
  void mountedModelsRetainPermissionsScopeAndDomainErrors() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var applications = context.getBean(DnaDataModelCatalog.class);
              when(applications.schema())
                  .thenReturn(new DnaDataModelCatalog.TableSchema(Map.of(), List.of()));
              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.PLATFORM);
              policy.setIsAdministrator(false);
              policy.setResources(Set.of("dna.models.data-models.view"));
              authenticate("dna.models.data-models.view");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(
                      get("/dna/platform/dna/models/data-models/schema")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk());
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post("/dna/platform/dna/models/data-models")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"name\":\"Orders\",\"modelType\":\"DNA_SQL\"}")))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(applications, never()).create(any());
              when(applications.detail("absent"))
                  .thenThrow(
                      new DnaModelException(
                          DnaModelErrorCode.MODEL_NOT_FOUND, "Application unavailable"));
              mvc.perform(
                      get("/dna/platform/dna/models/data-models/absent")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isNotFound())
                  .andExpect(jsonPath("$.messageKey").value("dna.models.error.MODEL_NOT_FOUND"));
              authenticate("dna.models.data-models.view", "dna.models.data-models.create");
              policy.setResources(
                  Set.of("dna.models.data-models.view", "dna.models.data-models.create"));
              mvc.perform(
                      post("/dna/platform/dna/models/data-models")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"name\":\"Orders\",\"modelType\":\"DNA_SQL\"}"))
                  .andExpect(status().isOk());
              verify(applications).create(any());
              policy.replaceScopeType(AuthorizationScopeType.PERSONAL);
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              get("/dna/platform/dna/models/data-models/schema")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              mvc.perform(get("/platform/dna/models/data-models/schema"))
                  .andExpect(status().isNotFound());
            });
  }

  @Test
  void storagePreparationKeepsVersionBindingAndRecoveryPermission() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final var governance = context.getBean(RuntimeGovernanceCatalog.class);
              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.TENANT);
              policy.setIsAdministrator(false);
              var granted = new java.util.HashSet<>(Set.of("dna.runtime.models.view"));
              policy.setResources(granted);
              authenticate("dna.runtime.models.view");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              String path = "/dna/platform/dna/runtime/models/model/prepare-storage";
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post(path)
                                  .param("expectedVersion", "7")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(governance, never()).prepareStorage("model", 7);
              authenticate("dna.runtime.models.retry");
              granted.clear();
              granted.add("dna.runtime.models.retry");
              mvc.perform(
                      post(path)
                          .param("expectedVersion", "7")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk());
              verify(governance).prepareStorage("model", 7);
              policy.replaceScopeType(AuthorizationScopeType.PLATFORM);
              assertThatThrownBy(() -> mvc.perform(post(path).param("expectedVersion", "7")
                  .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(governance, org.mockito.Mockito.times(1)).prepareStorage("model", 7);
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
    return runner().withBean(ModulePlan.class, () -> catalog.plan(List.of("dna-http")));
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
        List.of(
            "org.simplepoint.dna.model.controller",
            "org.simplepoint.dna.runtime.controller",
            "org.simplepoint.plugin.dna.core.rest.controller",
            "org.simplepoint.plugin.dna.federation.rest.controller")) {
      for (var bean : scanner.findCandidateComponents(root)) {
        try {
          Class<?> type = Class.forName(bean.getBeanClassName());
          for (var constructor : type.getConstructors()) {
            dependencies.addAll(List.of(constructor.getParameterTypes()));
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
      if (type == ObjectMapper.class) {
        runner = runner.withBean(ObjectMapper.class, ObjectMapper::new);
      } else {
        runner = withDomainDouble(runner, type);
      }
    }
    return runner;
  }

  private <T> WebApplicationContextRunner withDomainDouble(
      WebApplicationContextRunner runner, Class<T> type) {
    assertThat(type.getPackageName()).startsWith("org.simplepoint.");
    assertThat(catalog.plan(List.of("dna-runtime")).ownerOf(type)).isPresent();
    return runner.withBean(type, () -> mock(type));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = {
        "org.simplepoint.dna.model.controller",
        "org.simplepoint.dna.runtime.controller",
        "org.simplepoint.plugin.dna.core.rest.controller",
        "org.simplepoint.plugin.dna.federation.rest.controller"
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
