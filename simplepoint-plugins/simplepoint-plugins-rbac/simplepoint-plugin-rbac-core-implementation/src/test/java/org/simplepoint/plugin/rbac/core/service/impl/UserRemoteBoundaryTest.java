package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.remoting.RemoteContractResolver;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UserRemoteBoundaryTest {
  @Test
  void localManagementStaysLocalAndRemovedEntityLookupCannotBeDispatched() {
    assertThat(RemoteContractResolver.isRemoteContract(UserManagementService.class)).isFalse();
    assertThat(UsersServiceImpl.class.isAnnotationPresent(RemoteProvider.class)).isFalse();
    var users = mock(UserManagementService.class);
    new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean(UserManagementService.class, () -> users)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(UserManagementService.class)).isSameAs(users);
              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              for (String operation :
                  List.of(
                      "loadUserByPhoneOrEmail",
                      "removeAll",
                      "findByIdForAuthorization",
                      "changePassword")) {
                var response =
                    codec.decodeResponse(
                        dispatcher.dispatchJson(
                            codec.encode(
                                new RemoteRequest(
                                    "security.user",
                                    "1",
                                    operation,
                                    List.of("user@example.test"),
                                    "blocked"))));
                assertThat(response.success()).as(operation).isFalse();
              }
              verifyNoInteractions(users);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.plugin.rbac.core.api.service")
  static class Router {}
}
