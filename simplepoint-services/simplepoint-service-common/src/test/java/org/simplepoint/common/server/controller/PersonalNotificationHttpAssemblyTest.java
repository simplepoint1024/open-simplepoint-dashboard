package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.notification.api.service.NotificationInboxService;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.rest.push.NotificationRedisPushSubscriber;
import org.simplepoint.plugin.notification.rest.push.NotificationSseBroker;
import org.simplepoint.plugin.personal.api.PersonalService;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Real optional HTTP packages, security and SSE lifecycle; domain persistence uses explicit mocks.
 */
class PersonalNotificationHttpAssemblyTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void domainOnlySelectionDoesNotStartBrowserEndpointsOrPushWorkers() {
    runner(List.of("notification"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
              assertThat(context.getBeansOfType(NotificationSseBroker.class)).isEmpty();
              assertThat(context.getBeansOfType(NotificationRedisPushSubscriber.class)).isEmpty();
              assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                  .isEmpty();
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/personal/overview")).andExpect(status().isNotFound());
              mvc.perform(get("/common/notifications/stream")).andExpect(status().isNotFound());
            });
  }

  @Test
  void combinedAdaptersRetainEveryLegacyMappingAndOnlySelectedPushWorkers() {
    var legacy = new AtomicReference<Set<String>>();
    runner(List.of("personal-http", "notification-http"), false)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              legacy.set(routes(context.getBean(RequestMappingHandlerMapping.class), "/common"));
            });
    runner(List.of("personal-http", "notification-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(4);
              assertThat(routes(context.getBean(RequestMappingHandlerMapping.class), ""))
                  .isEqualTo(legacy.get());
              assertThat(
                      context
                          .getBean(ScheduledAnnotationBeanPostProcessor.class)
                          .getScheduledTasks())
                  .hasSize(1);
              assertThat(context.getBean(NotificationRedisPushSubscriber.class).isRunning())
                  .isFalse();
              authenticate("unrelated.view");
              var inbox = context.getBean(NotificationInboxService.class);
              when(inbox.unreadCount()).thenReturn(7L);
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/notifications/unread-count"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.count").value(7));
              mvc.perform(put("/common/notifications/message-1/read")).andExpect(status().isOk());
              verify(inbox).markRead("message-1");
              mvc.perform(get("/notifications/unread-count")).andExpect(status().isNotFound());
              mvc.perform(get("/personal/overview")).andExpect(status().isNotFound());
            });
  }

  @Test
  void personalOnlySelectionRequiresAuthenticationAndDoesNotActivateNotifications() {
    runner(List.of("personal-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(2);
              assertThat(context.getBeansOfType(NotificationSseBroker.class)).isEmpty();
              var service = context.getBean(PersonalService.class);
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              assertThatThrownBy(() -> mvc.perform(get("/common/personal/overview")))
                  .hasRootCauseInstanceOf(AuthenticationCredentialsNotFoundException.class);
              verifyNoInteractions(service);
              authenticate("unrelated.view");
              mvc.perform(get("/common/personal/overview")).andExpect(status().isOk());
              verify(service).overview();
              mvc.perform(get("/common/notifications/unread-count"))
                  .andExpect(status().isNotFound());
            });
  }

  @Test
  void notificationOnlySelectionRetainsManagementAuthorizationAndKeepsPersonalHttpAbsent() {
    runner(List.of("notification-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(2);
              var service = context.getBean(NotificationManagementService.class);
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              authenticate("notifications.view");
              assertThatThrownBy(
                      () -> mvc.perform(post("/common/system/notifications/draft/publish")))
                  .hasRootCauseInstanceOf(AccessDeniedException.class);
              verifyNoInteractions(service);
              authenticate("notifications.publish");
              mvc.perform(post("/common/system/notifications/draft/publish"))
                  .andExpect(status().isOk());
              verify(service).publish("draft");
              mvc.perform(get("/common/personal/overview")).andExpect(status().isNotFound());
            });
  }

  @Test
  void contextShutdownCompletesActiveStreamsAndStopsHeartbeats() {
    var response = new AtomicReference<MvcResult>();
    var broker = new AtomicReference<NotificationSseBroker>();
    var scheduler = new AtomicReference<ScheduledAnnotationBeanPostProcessor>();
    var audience = new AudienceContext("user", AuthorizationScopeType.TENANT, "tenant");
    runner(List.of("notification-http"), true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              authenticate("unrelated.view");
              when(context.getBean(NotificationInboxService.class).currentAudience())
                  .thenReturn(audience);
              broker.set(context.getBean(NotificationSseBroker.class));
              scheduler.set(context.getBean(ScheduledAnnotationBeanPostProcessor.class));
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              response.set(
                  mvc.perform(get("/common/notifications/stream"))
                      .andExpect(status().isOk())
                      .andExpect(request().asyncStarted())
                      .andReturn());
              assertThat(response.get().getResponse().getContentAsString()).contains("event:ready");
            });
    assertThat(response.get().getAsyncResult(1000)).isNull();
    assertThat(scheduler.get().getScheduledTasks()).isEmpty();
    assertThatThrownBy(() -> broker.get().connect(audience))
        .isInstanceOf(IllegalStateException.class);
    broker.get().destroy();
  }

  private WebApplicationContextRunner runner(List<String> roots, boolean modular) {
    ModulePlan plan = catalog.plan(roots);
    var runner =
        new WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
            .withUserConfiguration(MethodSecurity.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PersonalService.class, () -> mock(PersonalService.class))
            .withBean(DetailsProviderService.class, () -> mock(DetailsProviderService.class))
            .withBean(NotificationInboxService.class, () -> mock(NotificationInboxService.class))
            .withBean(
                NotificationManagementService.class,
                () -> mock(NotificationManagementService.class))
            .withInitializer(
                context -> {
                  var scanner =
                      new ClassPathBeanDefinitionScanner(
                          (BeanDefinitionRegistry) context.getBeanFactory());
                  plan.modules().stream()
                      .filter(
                          module -> module.configuration().isAnnotationPresent(ModuleHttp.class))
                      .forEach(module -> scanner.scan(module.packages().toArray(String[]::new)));
                });
    return modular ? runner.withBean(ModulePlan.class, () -> plan) : runner;
  }

  private static Set<String> routes(RequestMappingHandlerMapping mapping, String prefix) {
    var result = new TreeSet<String>();
    mapping
        .getHandlerMethods()
        .forEach(
            (route, handler) ->
                route
                    .getPatternValues()
                    .forEach(
                        path -> result.add(route.getMethodsCondition() + " " + prefix + path)));
    return result;
  }

  private static void authenticate(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "test-user", null, List.of(new SimpleGrantedAuthority(authority))));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurity {}
}
