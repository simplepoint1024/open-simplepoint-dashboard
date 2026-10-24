package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserDirectoryPage;
import org.simplepoint.plugin.rbac.core.api.service.UserDirectory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class UserDirectoryBoundaryTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void wireExportsDirectoryOnlyAndKeepsTheServiceIdentityBoundary() throws Exception {
    var search = UserDirectory.class.getMethod("search", String.class, int.class, int.class);
    var entityManager = mock(EntityManager.class);
    new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean(UserDirectoryImpl.class, () -> new UserDirectoryImpl(entityManager))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = context.getBean(LocalServiceRegistry.class);
              assertThat(
                      registry.find("security.user-directory", "1").orElseThrow().interfaceType())
                  .isEqualTo(UserDirectory.class);
              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              var request =
                  new RemoteRequest(
                      "security.user-directory",
                      "1",
                      "search",
                      List.of("ab", 0, 20),
                      "directory-query");
              var rejected = codec.decodeResponse(dispatcher.dispatchJson(codec.encode(request)));
              assertThat(rejected.success()).isFalse();
              var principal =
                  new DefaultOAuth2AuthenticatedPrincipal(
                      "display",
                      Map.of("client_id", "dna", "grant_type", "client_credentials"),
                      List.of());
              SecurityContextHolder.getContext()
                  .setAuthentication(
                      new TestingAuthenticationToken(
                          principal, null, "SCOPE_service-router.invoke"));
              var response = codec.decodeResponse(dispatcher.dispatchJson(codec.encode(request)));
              assertThat(response.success()).isTrue();
              assertThat(codec.result(UserDirectory.class, search, response.data()))
                  .isEqualTo(new UserDirectoryPage(List.of(), 0));
              for (String operation :
                  List.of("removeAll", "findByEmail", "findByIdForAuthorization")) {
                var hidden =
                    codec.decodeResponse(
                        dispatcher.dispatchJson(
                            codec.encode(
                                new RemoteRequest(
                                    "security.user-directory",
                                    "1",
                                    operation,
                                    List.of(),
                                    "hidden"))));
                assertThat(hidden.success()).isFalse();
              }
              verifyNoInteractions(entityManager);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.plugin.rbac.core.api.service")
  static class Router {}
}
