package org.simplepoint.ai.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiProviderDefinitionService;
import org.simplepoint.plugin.ai.core.rest.controller.AiCompatibleGatewayController;
import org.simplepoint.plugin.ai.core.rest.controller.AiProviderDefinitionController;
import org.simplepoint.plugin.ai.core.rest.gateway.AiCompatibilityMapper;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol;
import org.simplepoint.plugin.ai.core.service.impl.AiProviderDefinitionServiceImpl;
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
class AiHttpCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainsOwnServicesWithoutOpeningManagementEndpoints() {
    var domain = catalog.plan(List.of("ai-catalog", "ai-knowledge"));
    assertThat(ModuleHttpPlan.from(domain).routes()).isEmpty();
    assertThat(domain.ownerOf(AiProviderDefinitionController.class)).isEmpty();
    assertThat(domain.ownerOf(AiCompatibleGatewayController.class)).isEmpty();
    assertThat(domain.ownerOf(AiProviderDefinitionServiceImpl.class).orElseThrow().id())
        .isEqualTo("ai-core");
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
                                    assertThat(path).startsWith("/ai/");
                                    mounted.add(
                                        route.getMethodsCondition()
                                            + " "
                                            + path.substring("/ai".length()));
                                  }));
              assertThat(mounted).isEqualTo(original);
              assertThat(mounted.size()).isGreaterThan(80);
              assertThat(
                      context.getBean(ModulePlan.class).modules().stream()
                          .map(module -> module.id()))
                  .containsExactly(
                      "ai-core", "ai-runtime", "ai-mcp", "ai-catalog", "ai-knowledge", "ai-http");
            });
  }

  @Test
  void mountedProviderWriteRetainsPermissionsAndWorkspaceScope() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final var providers = context.getBean(AiProviderDefinitionService.class);

              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.TENANT);
              policy.setIsAdministrator(false);
              var granted = new HashSet<>(Set.of("ai.workbench.providers.view"));
              policy.setResources(granted);
              authenticate("ai.workbench.providers.view");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              String path = "/ai/workbench/providers";
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post(path)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"name\":\"Example provider\"}")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(providers, never())
                  .create(
                      any(org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition.class));
              authenticate("ai.workbench.providers.create");
              granted.clear();
              granted.add("ai.workbench.providers.create");
              mvc.perform(
                      post(path)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"name\":\"Example provider\"}")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk());
              verify(providers)
                  .create(
                      any(org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition.class));
              policy.replaceScopeType(AuthorizationScopeType.PERSONAL);
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post(path)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"name\":\"Example provider\"}")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(providers, org.mockito.Mockito.times(1))
                  .create(
                      any(org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition.class));
              mvc.perform(get("/workbench/providers")).andExpect(status().isNotFound());
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
    return runner().withBean(ModulePlan.class, () -> catalog.plan(List.of("ai-http")));
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
            "org.simplepoint.plugin.ai.core.rest.controller",
            "org.simplepoint.plugin.ai.catalog.rest.controller",
            "org.simplepoint.plugin.ai.knowledge.rest.controller",
            "org.simplepoint.plugin.ai.runtime.rest.controller",
            "org.simplepoint.plugin.ai.mcp.rest.controller")) {
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
            .withUserConfiguration(Adapters.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AiProperties.class, AiProperties::new)
            .withBean(
                "aiInferenceExecutor", java.util.concurrent.Executor.class, () -> Runnable::run)
            .withBean(AiCompatibilityMapper.class)
            .withBean(OpenAiResponsesProtocol.class);
    for (Class<?> type : dependencies) {
      if (type == java.util.concurrent.Executor.class
          || type == ObjectMapper.class
          || type == AiProperties.class
          || type == AiCompatibilityMapper.class
          || type == OpenAiResponsesProtocol.class) {
        continue;
      } else if (type == DetailsProviderService.class) {
        runner =
            runner.withBean(DetailsProviderService.class, () -> mock(DetailsProviderService.class));
      } else {
        runner = withDomainDouble(runner, type);
      }
    }
    return runner;
  }

  private <T> WebApplicationContextRunner withDomainDouble(
      WebApplicationContextRunner runner, Class<T> type) {
    assertThat(type.getPackageName()).startsWith("org.simplepoint.");
    assertThat(catalog.plan(List.of("ai-catalog", "ai-knowledge")).ownerOf(type)).isPresent();
    if (type == org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations.class) {
      return runner.withBean("httpMcpGatewayOperations", type, () -> mock(type));
    }
    return runner.withBean(type, () -> mock(type));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = {
        "org.simplepoint.plugin.ai.core.rest.controller",
        "org.simplepoint.plugin.ai.catalog.rest.controller",
        "org.simplepoint.plugin.ai.knowledge.rest.controller",
        "org.simplepoint.plugin.ai.runtime.rest.controller",
        "org.simplepoint.plugin.ai.mcp.rest.controller"
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
