package org.simplepoint.platform.dictionary.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryDefinitions;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;
import org.simplepoint.platform.dictionary.ModuleDictionaries;

class DictionaryModuleInitializerTest {
  @Test
  void preparationHasNoWritesAndUsesOnlyTheBoundClientWithDeclaredPriority() throws Exception {
    var calls = new AtomicInteger();
    DictionaryRegistrationService service =
        declaration -> {
          calls.incrementAndGet();
          return new DictionaryRegistrationReceipt(
              "example", "seed", "1", UUID.randomUUID(), 1, DictionaryFingerprint.of(declaration));
        };
    var catalog = new ModuleCatalog(List.of(Example.class, Identity.class));
    var plan = catalog.plan(List.of("example"));
    assertTrue(plan.remoteCapabilities().containsKey("security.dictionary-registration:1"));
    var tasks = new DictionaryModuleInitializer(plan).prepareTasks(context(service));
    assertEquals(0, calls.get());
    assertEquals(310, tasks.getFirst().order());
    tasks.getFirst().action().run();
    assertEquals(1, calls.get());
  }

  @Test
  void missingOrWrongCompletionNeverReportsSuccess() {
    var plan = new ModuleCatalog(List.of(Example.class)).plan(List.of("example"));
    for (DictionaryRegistrationService service :
        List.<DictionaryRegistrationService>of(
            declaration -> null,
            declaration ->
                new DictionaryRegistrationReceipt(
                    "other",
                    "seed",
                    "1",
                    UUID.randomUUID(),
                    1,
                    DictionaryFingerprint.of(declaration)))) {
      var action = new DictionaryModuleInitializer(plan).prepare(context(service));
      assertThrows(ModuleCompositionException.class, action::run);
    }
  }

  @Test
  void installationPlanUsesStableModuleIdentitiesAcrossHostsAndRequiresExplicitGenerations() {
    var catalog = new ModuleCatalog(List.of(Example.class, Identity.class));
    var all = catalog.deploy(Map.of("one", List.of("example", "identity")));
    var split = catalog.deploy(Map.of("a", List.of("example"), "b", List.of("identity")));
    UUID operation = UUID.randomUUID();
    var first = DictionaryRegistrationPlan.prepare(all, Map.of(), operation, Map.of("example", 0L));
    assertEquals(
        first,
        DictionaryRegistrationPlan.prepare(split, Map.of(), operation, Map.of("example", 0L)));
    assertThrows(
        ModuleCompositionException.class,
        () -> DictionaryRegistrationPlan.prepare(all, Map.of(), operation, Map.of()));
    assertThrows(UnsupportedOperationException.class, first::clear);
  }

  @Test
  void errorsDecodeOnlyRecognizedContractCodes() {
    var decoder = new DictionaryRegistrationException.Decoder();
    for (var code : DictionaryRegistrationException.Code.values()) {
      var error = new DictionaryRegistrationException(code);
      var restored =
          (DictionaryRegistrationException)
              decoder.decode(error.remoteErrorCode(), "untrusted input");
      assertEquals(error.code(), restored.code());
      assertTrue(restored.messageKey().startsWith("dictionaries.registration."));
      assertTrue(!restored.getMessage().contains("untrusted"));
    }
    assertEquals(null, decoder.decode("DICTIONARY_REGISTRATION:UNKNOWN", "anything"));
  }

  private static ModuleInitializationContext context(DictionaryRegistrationService service) {
    return new ModuleInitializationContext() {
      @Override
      public String moduleId() {
        return "example";
      }

      @Override
      public <T> T client(Class<T> contract) {
        assertEquals(DictionaryRegistrationService.class, contract);
        return contract.cast(service);
      }

      @Override
      public byte[] resource(String path) {
        throw new AssertionError("No resource scan");
      }
    };
  }

  @PlatformModule(
      id = "example",
      packages = "example.domain",
      initializers = DictionaryModuleInitializer.class)
  @ModuleDictionaries(Definitions.class)
  static class Example {}

  @PlatformModule(
      id = "identity",
      packages = "example.identity",
      provides = DictionaryRegistrationService.class)
  static class Identity {}

  public static class Definitions implements DictionaryDefinitions {
    public Definitions() {}

    @Override
    public DictionaryContribution declaration() {
      return new DictionaryContribution(
          "example",
          "seed",
          "1",
          310,
          List.of(new DictionaryDeclaration("example.code", "Name", null, 10, List.of())));
    }
  }
}
