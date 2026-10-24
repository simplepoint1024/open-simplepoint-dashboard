package org.simplepoint.plugin.i18n.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nMessageManagementService;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class I18nRemoteBoundaryTest {

  @Test
  void queryAndRegistrationContractsCannotInvokeEntityCrud() {
    var messages = mock(I18nMessageManagementService.class);
    var namespaces = mock(I18nNamespaceService.class);
    var repository = mock(I18nMessageRepository.class);
    when(messages.mapping("zh-CN", "test")).thenReturn(Map.of("title", "业务入口"));
    new ApplicationContextRunner().withUserConfiguration(Router.class)
        .withBean(I18nMessageManagementService.class, () -> messages)
        .withBean(I18nMessageRegistrationService.class,
            () -> new I18nMessageRegistrationServiceImpl(namespaces, messages, repository))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(I18nMessageManagementService.class)).isSameAs(messages);
          assertThat(context.getBean(I18nMessageService.class)).isNotSameAs(messages);
          assertThat(context.getBean(I18nMessageService.class).mapping("zh-CN", "test"))
              .containsEntry("title", "业务入口");
          var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
          var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
          for (String capability : List.of("i18n.message", "i18n.message-registration")) {
            var response = codec.decodeResponse(dispatcher.dispatchJson(codec.encode(new RemoteRequest(
                capability, capability.endsWith("registration") ? "2" : "1", "removeAll", List.of(), "trace"))));
            assertThat(response.success()).isFalse();
          }
          verify(messages, never()).removeAll();
          verify(namespaces, never()).removeAll();

          var namespace = new NamespaceDeclaration("test", "测试", "example", "namespace-description");
          var message = new MessageDeclaration("ja-JP", "test", "title", "顧客", "message-description", false);
          var response = codec.decodeResponse(dispatcher.dispatchJson(codec.encode(new RemoteRequest(
              "i18n.message-registration", "2", "register",
              List.of(Set.of(namespace), Set.of(message)), "register-trace"))));
          assertThat(response.success()).isTrue();
          verifyOwnedEntities(namespaces, messages);
        });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void verifyOwnedEntities(I18nNamespaceService namespaces, I18nMessageManagementService messages) {
    ArgumentCaptor<Collection<Namespace>> namespaceCaptor = ArgumentCaptor.forClass(Collection.class);
    ArgumentCaptor<Collection<Message>> messageCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(namespaces).create(namespaceCaptor.capture());
    verify(messages).create(messageCaptor.capture());
    assertThat(namespaceCaptor.getValue()).singleElement().satisfies(value -> {
      assertThat(value.getId()).isNull();
      assertThat(value.getCode()).isEqualTo("test");
      assertThat(value.getName()).isEqualTo("测试");
      assertThat(value.getModule()).isEqualTo("example");
      assertThat(value.getDescription()).isEqualTo("namespace-description");
    });
    assertThat(messageCaptor.getValue()).singleElement().satisfies(value -> {
      assertThat(value.getId()).isNull();
      assertThat(value.getCreatedAt()).isNull();
      assertThat(value.getLocale()).isEqualTo("ja-JP");
      assertThat(value.getNamespace()).isEqualTo("test");
      assertThat(value.getCode()).isEqualTo("title");
      assertThat(value.getMessage()).isEqualTo("顧客");
      assertThat(value.getDescription()).isEqualTo("message-description");
      assertThat(value.getGlobal()).isFalse();
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = {"org.simplepoint.core.locale", "org.simplepoint.plugin.i18n.api.service"})
  static class Router {}
}
