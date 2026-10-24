package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleResources;
import org.simplepoint.plugin.i18n.api.configuration.I18nModuleMessageAutoConfiguration;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.i18n.api.model.MessageDeclaration;
import org.simplepoint.plugin.i18n.api.model.NamespaceDeclaration;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class PackagedInitializationTest {
  @Test
  void everyMigratedDomainLoadsItsOwnDeclarationsAndKeepsLegacyRegistrationCoverage()
      throws Exception {
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    var factory = new DefaultListableBeanFactory();
    var legacy = new Registry();
    factory.registerSingleton("registration", legacy);
    new I18nModuleMessageAutoConfiguration(
            factory.getBeanProvider(I18nMessageRegistrationService.class), "legacy-host")
        .run(null);
    for (String id : List.of("identity", "i18n", "oidc", "storage")) {
      var module =
          catalog.modules().stream()
              .filter(candidate -> candidate.id().equals(id))
              .findFirst()
              .orElseThrow();
      assertTrue(module.initializers().contains(I18nModuleInitializer.class));
      var messages = I18nModuleInitializer.readDeclarations(id, getClass().getClassLoader());
      assertFalse(messages.isEmpty());
      messages.forEach(
          (locale, namespaces) ->
              namespaces.forEach(
                  (namespace, entries) ->
                      entries.forEach(
                          (code, message) ->
                              assertTrue(
                                  legacy.messages.stream()
                                      .anyMatch(
                                          registered ->
                                              registered.locale().equals(locale)
                                                  && registered.namespace().equals(namespace)
                                                  && registered.code().equals(code)
                                                  && registered.message().equals(message)),
                                  id + ":" + locale + ":" + namespace + ":" + code))));
    }
  }

  @Test
  void actualI18nModuleDeclaresItsStartupDependencyAndLoadsEveryPackagedTranslation()
      throws Exception {
    var module =
        ModuleCatalog.discover(getClass().getClassLoader())
            .plan(List.of("i18n"))
            .modules()
            .getFirst();
    assertEquals(
        List.of(
            org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer.class,
            I18nModuleInitializer.class),
        module.initializers());
    assertEquals(
        I18nMessageRegistrationService.class, module.consumes().get("i18n.message-registration:2"));
    var service = new Registry();
    var initializer = new I18nModuleInitializer();
    var action =
        initializer.prepare(
            new ModuleInitializationContext() {
              @Override
              public String moduleId() {
                return "i18n";
              }

              @Override
              public <T> T client(Class<T> contract) {
                return contract.cast(service);
              }

              @Override
              public byte[] resource(String path) {
                return ModuleResources.read(moduleId(), getClass().getClassLoader(), path);
              }
            });
    assertEquals(0, service.calls);
    action.run();
    assertEquals(3, service.calls);
    assertEquals(
        Set.of("countries", "languages", "messages", "namespaces", "regions", "timezones"),
        service.messages.stream()
            .map(MessageDeclaration::namespace)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        Set.of("en-US", "zh-CN"),
        service.messages.stream()
            .map(MessageDeclaration::locale)
            .collect(java.util.stream.Collectors.toSet()));
    assertFalse(service.messages.isEmpty());
    assertTrue(service.messages.stream().allMatch(message -> message.message() != null));

    var legacyRegistry = new Registry();
    var factory = new DefaultListableBeanFactory();
    factory.registerSingleton("registration", legacyRegistry);
    new I18nModuleMessageAutoConfiguration(
            factory.getBeanProvider(I18nMessageRegistrationService.class), "legacy-host")
        .run(null);
    assertTrue(
        legacyRegistry.messages.containsAll(service.messages),
        "Legacy hosts must continue registering the moved module translations");
  }

  private static final class Registry implements I18nMessageRegistrationService {
    private int calls;
    private final Set<MessageDeclaration> messages = new HashSet<>();

    @Override
    public Set<String> findExistingNamespaceCodes() {
      calls++;
      return Set.of();
    }

    @Override
    public Set<String> findExistingMessageKeys(Collection<String> namespaces) {
      calls++;
      return Set.of();
    }

    @Override
    public void register(
        Set<NamespaceDeclaration> namespaces, Set<MessageDeclaration> declarations) {
      calls++;
      messages.addAll(declarations);
    }
  }
}
