package org.simplepoint.plugin.common.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.permission.ResourcePermissionCatalog;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleResources;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class CommonUiResourceInitializationTest {
  @Test
  void actualPackagedDeclarationsRegisterHistoricalOwnerAndMatchSelectedRequestScopes()
      throws Exception {
    var loader = getClass().getClassLoader();
    var plan = ModuleCatalog.discover(loader).plan(List.of("common-ui"));
    var adapter =
        plan.modules().stream()
            .filter(module -> module.id().equals("common-ui"))
            .findFirst()
            .orElseThrow();
    assertEquals(Optional.of("common"), adapter.resourceOwner());
    assertEquals(
        Set.of("security.resource:4", "i18n.message-registration:2", "i18n.message:1"), adapter.consumes().keySet());
    var resources = mock(ResourceRegistrationService.class);
    var messages = mock(I18nMessageRegistrationService.class);
    var claimed = new HashSet<String>();
    var context =
        new ModuleInitializationContext() {
          @Override
          public String moduleId() {
            return adapter.id();
          }

          @Override
          public Optional<String> resourceOwner() {
            return adapter.resourceOwner();
          }

          @Override
          public void claimResourceCodes(Set<String> codes) {
            claimed.addAll(codes);
          }

          @Override
          public void requireResourceParents(java.util.Map<String, String> parents) {
            org.junit.jupiter.api.Assertions.assertFalse(parents.isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(parents.values().stream().allMatch("common"::equals));
          }

          @Override
          public <T> T client(Class<T> type) {
            if (type == ResourceRegistrationService.class) {
              return type.cast(resources);
            }
            if (type == I18nMessageRegistrationService.class) {
              return type.cast(messages);
            }
            throw new AssertionError("Unexpected registration contract");
          }

          @Override
          public byte[] resource(String path) {
            return ModuleResources.read(moduleId(), loader, path);
          }
        };
    var registration =
        new ResourceModuleInitializer(
            new DefaultListableBeanFactory().getBeanProvider(ResourcePermissionCatalog.class));
    var resourceAction = registration.prepare(context);
    var languageAction = new I18nModuleInitializer().prepare(context);
    verifyNoInteractions(resources, messages);
    resourceAction.run();
    languageAction.run();
    ArgumentCaptor<Set<ResourceDeclaration>> declarations = ArgumentCaptor.captor();
    verify(resources).sync(eq("common"), declarations.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        claimed.containsAll(Set.of("dashboard.view", "storage.objects.view", "personal.view")));
    assertFalse(claimed.isEmpty());
    var scopes = new ClasspathResourceScopeRegistry(new ObjectMapper(), plan, loader);
    assertEquals(claimed, scopes.snapshot().keySet());
    ArgumentCaptor<Set<MessageDeclaration>> translated = ArgumentCaptor.captor();
    ArgumentCaptor<Set<org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration>> namespaces =
        ArgumentCaptor.captor();
    verify(messages).register(namespaces.capture(), translated.capture());
    assertEquals(7, namespaces.getValue().size());
    assertFalse(translated.getValue().isEmpty());
    assertEquals(
        Set.of("en-US", "zh-CN"),
        translated.getValue().stream()
            .map(MessageDeclaration::locale)
            .collect(java.util.stream.Collectors.toSet()));
  }
}
