package org.simplepoint.auditing.http;

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

class AuditingResourceInitializationTest {
  @Test
  void actualPackagedDeclarationsRegisterHistoricalOwnerAndMatchSelectedRequestScopes()
      throws Exception {
    var loader = getClass().getClassLoader();
    var plan = ModuleCatalog.discover(loader).plan(List.of("auditing-http"));
    var adapter =
        plan.modules().stream()
            .filter(module -> module.id().equals("auditing-http"))
            .findFirst()
            .orElseThrow();
    assertEquals(Optional.of("auditing"), adapter.resourceOwner());
    assertEquals(
        Set.of("security.resource:4", "i18n.message-registration:2", "security.authorization-context:1"), adapter.consumes().keySet());
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
    verify(resources).sync(eq("auditing"), declarations.capture());
    org.junit.jupiter.api.Assertions.assertTrue(declarations.getValue().stream().anyMatch(resource -> "monitoring.view".equals(resource.getCode())));
    assertFalse(claimed.isEmpty());
    var scopes = new ClasspathResourceScopeRegistry(new ObjectMapper(), plan, loader);
    assertEquals(claimed, scopes.snapshot().keySet());
    ArgumentCaptor<Set<MessageDeclaration>> translated = ArgumentCaptor.captor();
    ArgumentCaptor<Set<org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration>> namespaces =
        ArgumentCaptor.captor();
    verify(messages).register(namespaces.capture(), translated.capture());
    assertEquals(6, namespaces.getValue().size());
    assertEquals(
        2, translated.getValue().stream().map(MessageDeclaration::namespace).distinct().count());
    assertEquals(
        Set.of("en-US", "zh-CN"),
        translated.getValue().stream()
            .map(MessageDeclaration::locale)
            .collect(java.util.stream.Collectors.toSet()));
  }
}
