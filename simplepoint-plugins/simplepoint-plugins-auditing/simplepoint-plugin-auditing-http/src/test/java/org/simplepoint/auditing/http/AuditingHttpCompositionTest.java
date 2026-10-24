package org.simplepoint.auditing.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.simplepoint.plugin.auditing.logging.rest.controller.LoginLogController;
import org.simplepoint.plugin.auditing.redis.api.service.RedisMonitoringService;
import org.simplepoint.plugin.auditing.redis.rest.controller.RedisMonitoringController;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryQueryService;
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
class AuditingHttpCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainsOwnServicesWithoutOpeningManagementEndpoints() {
    var domain = catalog.plan(List.of("audit-logging", "audit-rate-limit", "audit-redis"));
    assertThat(ModuleHttpPlan.from(domain).routes()).isEmpty();
    assertThat(domain.ownerOf(LoginLogController.class)).isEmpty();
    assertThat(domain.ownerOf(RedisMonitoringController.class)).isEmpty();
    assertThat(domain.ownerOf(RedisEntryQueryService.class).orElseThrow().id())
        .isEqualTo("audit-redis");
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
                                    assertThat(path).startsWith("/auditing/");
                                    mounted.add(
                                        route.getMethodsCondition()
                                            + " "
                                            + path.substring("/auditing".length()));
                                  }));
              assertThat(mounted).isEqualTo(original);
              assertThat(mounted.size()).isGreaterThan(15);
              assertThat(
                      context.getBean(ModulePlan.class).modules().stream()
                          .map(module -> module.id()))
                  .containsExactly(
                      "audit-logging", "audit-rate-limit", "audit-redis", "auditing-http");
            });
  }

  @Test
  void mountedRedisWriteRetainsPermissionsAndWorkspaceScope() {
    modular()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var redis = context.getBean(RedisMonitoringService.class);
              when(redis.create(any()))
                  .thenReturn(
                      new org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail());
              var policy = new AuthorizationContext();
              policy.setScopeType(AuthorizationScopeType.PLATFORM);
              policy.setIsAdministrator(true);
              policy.setResources(Set.of("redis.entries.view"));
              authenticate("redis.entries.view");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              String path = "/auditing/redis/entries";
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post(path)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"key\":\"example\",\"value\":\"hello\"}")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(redis, never()).create(any());
              authenticate("redis.entries.create");
              policy.setResources(Set.of("redis.entries.create"));
              mvc.perform(
                      post(path)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"key\":\"example\",\"value\":\"hello\"}")
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy))
                  .andExpect(status().isOk());
              verify(redis).create(any());
              policy.replaceScopeType(AuthorizationScopeType.TENANT);
              assertThatThrownBy(
                      () ->
                          mvc.perform(
                              post(path)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"key\":\"example\",\"value\":\"hello\"}")
                                  .requestAttr(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, policy)))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verify(redis, org.mockito.Mockito.times(1)).create(any());
              mvc.perform(get("/redis/entries")).andExpect(status().isNotFound());
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
    return runner().withBean(ModulePlan.class, () -> catalog.plan(List.of("auditing-http")));
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
            "org.simplepoint.plugin.auditing.logging.rest",
            "org.simplepoint.plugin.auditing.ratelimit.rest",
            "org.simplepoint.plugin.auditing.redis.rest")) {
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
      if (type == DetailsProviderService.class) {
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
    assertThat(
            catalog.plan(List.of("audit-logging", "audit-rate-limit", "audit-redis")).ownerOf(type))
        .isPresent();
    return runner.withBean(type, () -> mock(type));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = {
        "org.simplepoint.plugin.auditing.logging.rest",
        "org.simplepoint.plugin.auditing.ratelimit.rest",
        "org.simplepoint.plugin.auditing.redis.rest"
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
