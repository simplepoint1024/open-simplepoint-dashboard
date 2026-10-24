package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.plugin.oidc.api.service.OidcClientService;
import org.simplepoint.plugin.oidc.rest.controller.ExternalIdentityProviderController;
import org.simplepoint.plugin.oidc.rest.controller.OidcClientController;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Real selected HTTP adapters with explicit domain mocks; does not boot persistence or OAuth. */
class OptionalCommonHttpAssemblyTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @Test
  void identityAdapterRetainsPreferencePathsAndMemberMethodAuthorization() {
    runner(List.of("identity-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(23);
              var paths = routes(context.getBean(RequestMappingHandlerMapping.class), "");
              assertThat(paths)
                  .anyMatch(path -> path.endsWith(" /common/resources"))
                  .anyMatch(path -> path.endsWith(" /common/ops/microapps"))
                  .anyMatch(path -> path.endsWith(" /common/tenants"))
                  .anyMatch(path -> path.endsWith(" /common/platform/tenants"))
                  .anyMatch(
                      path ->
                          path.endsWith(" /common/platform/tenants/{tenantId}/resource-adoption"))
                  .anyMatch(
                      path ->
                          path.endsWith(" /common/platform/tenants/{tenantId}/package-instances"))
                  .anyMatch(path -> path.endsWith(" /common/tenants/current-resources"));
              var preferences =
                  context.getBean(
                      org.simplepoint.plugin.rbac.core.api.service.UserPreferenceService.class);
              when(preferences.getPreference("ui/table"))
                  .thenReturn(java.util.Optional.of("saved"));
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/users/preferences").param("key", "ui/table"))
                  .andExpect(status().isOk());
              verify(preferences).getPreference("ui/table");
              mvc.perform(get("/common/i18n/languages/mapping")).andExpect(status().isNotFound());
              var members =
                  context.getBean(
                      org.simplepoint.plugin.rbac.core.api.service.TenantMemberService.class);
              var controller =
                  context.getBean(
                      org.simplepoint.plugin.rbac.core.rest.controller.TenantMemberController
                          .class);
              var page = org.springframework.data.domain.PageRequest.of(0, 10);
              authenticate("unrelated.view");
              assertThatThrownBy(() -> controller.list(Map.of(), page))
                  .isInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(members);
              authenticate("users.view");
              when(members.list(Map.of(), page))
                  .thenReturn(org.springframework.data.domain.Page.empty());
              assertThat(controller.list(Map.of(), page).getBody()).isEmpty();
              verify(members).list(Map.of(), page);
            });
  }

  @Test
  void legacyAdoptionAdapterRequiresAuthenticationBeforeCallingTheDomain() {
    runner(List.of("identity-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var controller =
                  context.getBean(
                      org.simplepoint.plugin.rbac.tenant.rest.controller
                          .TenantLegacyAdoptionController.class);
              final var service =
                  context.getBean(
                      org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionService
                          .class);
              SecurityContextHolder.clearContext();
              assertThatThrownBy(() -> controller.preview("tenant"))
                  .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
              assertThatThrownBy(() -> controller.adopt("tenant"))
                  .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
              verifyNoInteractions(service);
            });
  }

  @Test
  void oauthClientOperationsRequireTheirOwnGrantBeforeReachingTheDomain() {
    runner(List.of("oidc-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var controller = context.getBean(OidcClientController.class);
              final var service = context.getBean(OidcClientService.class);
              var data = new Client();
              var configuration = new OidcClientConfigurationDto();
              final var writes =
                  List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                      () -> controller.addConfiguration(configuration),
                      () -> controller.modifyConfiguration("test-client", configuration),
                      () -> controller.add(data),
                      () -> controller.modify(data),
                      () -> controller.remove("test-client"));
              authenticate("unrelated.view");
              assertThatThrownBy(() -> controller.configuration("test-client"))
                  .isInstanceOf(AccessDeniedException.class);
              assertThatThrownBy(
                      () ->
                          controller.limit(
                              Map.of(), org.springframework.data.domain.PageRequest.of(0, 10)))
                  .isInstanceOf(AccessDeniedException.class);
              writes.forEach(
                  action -> assertThatThrownBy(action).isInstanceOf(AccessDeniedException.class));
              verifyNoInteractions(service);
              authenticate("oauthClients.view");
              controller.configuration("test-client");
              verify(service).configuration("test-client");
              writes.forEach(
                  action -> assertThatThrownBy(action).isInstanceOf(AccessDeniedException.class));
              authenticate("oauthClients.create");
              controller.addConfiguration(configuration);
              controller.add(data);
              verify(service).createConfiguration(configuration);
              verify(service).create(data);
              authenticate("oauthClients.edit");
              controller.modifyConfiguration("test-client", configuration);
              controller.modify(data);
              verify(service).updateConfiguration("test-client", configuration);
              verify(service).modifyById(data);
              authenticate("oauthClients.delete");
              controller.remove("test-client");
              verify(service).removeByIds(Set.of("test-client"));
            });
  }

  private static void authenticate(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "test-user", null, List.of(new SimpleGrantedAuthority(authority))));
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainOnlySelectionOwnsNoControllersAndExposesNoBrowserRoutes() {
    runner(List.of("identity", "oidc", "i18n"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/i18n/languages/mapping")).andExpect(status().isNotFound());
              mvc.perform(get("/common/oidc/clients")).andExpect(status().isNotFound());
            });
  }

  @Test
  void selectedRealAdaptersPreserveEveryLegacyMappingWithOneLogicalPrefix() {
    var legacy = new AtomicReference<Set<String>>();
    runner(List.of("identity-http", "oidc-http", "i18n-http"), false)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              legacy.set(routes(context.getBean(RequestMappingHandlerMapping.class), "/common"));
            });
    runner(List.of("identity-http", "oidc-http", "i18n-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(31);
              assertThat(routes(context.getBean(RequestMappingHandlerMapping.class), ""))
                  .isEqualTo(legacy.get());
              when(context.getBean(I18nLanguageService.class).mapping())
                  .thenReturn(Map.of("en-US", "English"));
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/i18n/languages/mapping"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$['en-US']").value("English"));
              mvc.perform(get("/i18n/languages/mapping")).andExpect(status().isNotFound());
            });
  }

  @Test
  void selectingOnlyOidcKeepsLocaleAbsentAndPreservesMethodAuthorization() {
    runner(List.of("oidc-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(2);
              var service = context.getBean(ExternalIdentityProviderService.class);
              var controller = context.getBean(ExternalIdentityProviderController.class);
              SecurityContextHolder.getContext()
                  .setAuthentication(
                      UsernamePasswordAuthenticationToken.authenticated(
                          "test-user",
                          null,
                          List.of(new SimpleGrantedAuthority("unrelated.view"))));
              assertThatThrownBy(() -> controller.remove("test-provider"))
                  .isInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(service);
              SecurityContextHolder.getContext()
                  .setAuthentication(
                      UsernamePasswordAuthenticationToken.authenticated(
                          "test-user",
                          null,
                          List.of(new SimpleGrantedAuthority("identityProviders.delete"))));
              assertThat(controller.remove("test-provider").getBody())
                  .containsExactly("test-provider");
              verify(service).removeByIds(Set.of("test-provider"));
              MockMvcBuilders.webAppContextSetup(context)
                  .build()
                  .perform(get("/common/i18n/languages/mapping"))
                  .andExpect(status().isNotFound());
            });
  }

  private WebApplicationContextRunner runner(List<String> roots, boolean modular) {
    ModulePlan plan = catalog.plan(roots);
    var runner =
        new WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
            .withUserConfiguration(MethodSecurity.class)
            .withInitializer(
                context -> {
                  var registry = (BeanDefinitionRegistry) context.getBeanFactory();
                  var scanner = new ClassPathScanningCandidateComponentProvider(false);
                  scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
                  for (var module : plan.modules()) {
                    if (!module.configuration().isAnnotationPresent(ModuleHttp.class)) {
                      continue;
                    }
                    for (String root : module.packages()) {
                      for (var definition : scanner.findCandidateComponents(root)) {
                        try {
                          Class<?> type = Class.forName(definition.getBeanClassName());
                          for (Class<?> dependency :
                              type.getConstructors()[0].getParameterTypes()) {
                            if (!context.getBeanFactory().containsSingleton(dependency.getName())) {
                              context
                                  .getBeanFactory()
                                  .registerSingleton(dependency.getName(), mock(dependency));
                            }
                          }
                          for (var field : type.getDeclaredFields()) {
                            if (field.isAnnotationPresent(
                                    org.springframework.beans.factory.annotation.Autowired.class)
                                && !context
                                    .getBeanFactory()
                                    .containsSingleton(field.getType().getName())) {
                              context
                                  .getBeanFactory()
                                  .registerSingleton(
                                      field.getType().getName(), mock(field.getType()));
                            }
                          }
                          registry.registerBeanDefinition(type.getName(), definition);
                        } catch (ClassNotFoundException failure) {
                          throw new IllegalStateException(failure);
                        }
                      }
                    }
                  }
                });
    return modular ? runner.withBean(ModulePlan.class, () -> plan) : runner;
  }

  private static Set<String> routes(RequestMappingHandlerMapping mappings, String prefix) {
    var result = new TreeSet<String>();
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              if (handler.getBeanType().getPackageName().contains(".rest.")) {
                mapping
                    .getPatternValues()
                    .forEach(
                        path -> result.add(mapping.getMethodsCondition() + " " + prefix + path));
              }
            });
    assertThat(result).isNotEmpty();
    return result;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurity {}
}
