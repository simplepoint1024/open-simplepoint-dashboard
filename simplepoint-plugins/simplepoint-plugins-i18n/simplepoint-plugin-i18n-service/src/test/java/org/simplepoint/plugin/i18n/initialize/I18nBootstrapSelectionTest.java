package org.simplepoint.plugin.i18n.initialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.entity.Message;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.i18n.api.repository.I18nMessageRepository;
import org.simplepoint.plugin.i18n.api.service.I18nMessageManagementService;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class I18nBootstrapSelectionTest {
  @Test
  void modularBootstrapDoesNotImportUnselectedClasspathMessages() throws Exception {
    assertNamespaces(true, false);
  }

  @Test
  void legacyBootstrapRetainsHistoricalAndMigratedMessages() throws Exception {
    assertNamespaces(false, true);
  }

  private void assertNamespaces(boolean modular, boolean includeUnselected) throws Exception {
    var initializer = new I18nAutoRegistrationInitializer();
    var factory = new DefaultListableBeanFactory();
    if (modular) {
      factory.registerSingleton("plan", new ModulePlan(List.of(), Map.of(), Map.of()));
    }
    initializer.setModulePlan(factory.getBeanProvider(ModulePlan.class));
    var namespaces = mock(I18nNamespaceService.class);
    var messages = mock(I18nMessageManagementService.class);
    var repository = mock(I18nMessageRepository.class);
    when(namespaces.findAll(anyMap())).thenReturn(List.of());
    when(repository.findExistingKeys(anyCollection())).thenReturn(Set.of());
    initializer
        .i18nMessagesBootstrapContribution(namespaces, messages, repository)
        .contribution()
        .action()
        .run();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<Message>> declarations = ArgumentCaptor.forClass(Set.class);
    verify(messages).create(declarations.capture());
    assertThat(
            declarations.getValue().stream()
                .anyMatch(message -> "unselected-fixture".equals(message.getNamespace())))
        .isEqualTo(includeUnselected);
    assertThat(declarations.getValue().stream().map(Message::getNamespace))
        .contains("countries", "languages", "messages", "namespaces", "regions", "timezones");
  }
}
