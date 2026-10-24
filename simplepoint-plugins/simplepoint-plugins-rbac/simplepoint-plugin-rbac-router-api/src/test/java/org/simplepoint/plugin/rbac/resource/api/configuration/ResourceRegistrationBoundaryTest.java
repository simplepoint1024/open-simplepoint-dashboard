package org.simplepoint.plugin.rbac.resource.api.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.simplepoint.security.service.ResourceService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ResourceRegistrationBoundaryTest {

  @Test
  void ownershipFailureSurvivesTheJsonDispatcherWithItsContractCode() {
    var provider = mock(ResourceRegistrationService.class);
    var failure =
        new ResourceRegistrationException(ResourceRegistrationException.Code.OWNERSHIP_CONFLICT);
    doThrow(failure).when(provider).sync(eq("foreign"), any());
    new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean("registrationProvider", ResourceRegistrationService.class, () -> provider)
        .run(
            context -> {
              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              var declaration = new ResourceDeclaration();
              declaration.setCode("untrusted-input");
              var response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "sync",
                                  List.of("foreign", Set.of(declaration)),
                                  "rejection-trace"))));
              assertThat(response.success()).isFalse();
              assertThat(response.errorCode()).isEqualTo(failure.remoteErrorCode());
              assertThat(response.message()).doesNotContain("untrusted-input");
              var restored =
                  (ResourceRegistrationException)
                      new ResourceRegistrationException.Decoder()
                          .decode(response.errorCode(), response.message());
              assertThat(restored.messageKey())
                  .isEqualTo("resources.registration.ownershipConflict");
            });
  }

  @Test
  void localManagementIsNotExportedByTheDeclarationContract() {
    var provider = mock(ResourceRegistrationService.class);
    var management = mock(ResourceService.class);
    var entity = new Resource();
    entity.setId("owned-id");
    when(management.findById("owned-id")).thenReturn(Optional.of(entity));
    new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean("registrationProvider", ResourceRegistrationService.class, () -> provider)
        .withBean(ResourceService.class, () -> management)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ResourceService.class)).isSameAs(management);
              assertThat(context.getBean(ResourceService.class).findById("owned-id"))
                  .contains(entity);
              var remote = context.getBean(ResourceRegistrationService.class);
              assertThat(remote).isNotSameAs(provider);
              var registry = context.getBean(LocalServiceRegistry.class);
              assertThat(registry.find("security.resource", "1")).isEmpty();
              assertThat(registry.find("security.resource", "2")).isEmpty();
              assertThat(registry.find("security.resource", "3")).isEmpty();
              assertThat(registry.find("security.resource", "4").orElseThrow().interfaceType())
                  .isEqualTo(ResourceRegistrationService.class);
              var declaration = new ResourceDeclaration();
              declaration.setCode("test.view");
              declaration.setName("业务入口");
              remote.sync("test", Set.of(declaration));
              verify(provider).sync("test", Set.of(declaration));

              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              var response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "removeAll",
                                  List.of(),
                                  "test-trace"))));
              assertThat(response.success()).isFalse();
              verify(management, never()).removeAll();

              response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "sync",
                                  List.of("wire", Set.of(declaration)),
                                  "wire-trace"))));
              assertThat(response.success()).isTrue();
              verify(provider).sync(eq("wire"), any());
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.security.service")
  static class Router {}
}
