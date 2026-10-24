package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModuleResourcePlanTest {
  @Test
  void resourceOwnerDoesNotFollowAdapterOrHostNames() {
    var catalog = new ModuleCatalog(List.of(Adapter.class, Domain.class));
    assertEquals(
        Map.of("adapter", "historical-owner"),
        ModuleResourcePlan.owners(catalog.plan(List.of("adapter", "domain"))));
    assertEquals(
        Map.of("historical-owner", "host-two"),
        catalog
            .deploy(Map.of("host-one", List.of("domain"), "host-two", List.of("adapter")))
            .resourceHosts());
    assertEquals(Map.of(), ModuleResourcePlan.owners(catalog.plan(List.of("domain"))));
  }

  @Test
  void registrationPolicyUsesModuleCredentialsAcrossAllTopologies() {
    var catalog = new ModuleCatalog(List.of(Adapter.class, Domain.class));
    var split = catalog.deploy(Map.of("first", List.of("domain"), "second", List.of("adapter")));
    var combined = catalog.deploy(Map.of("combined", List.of("domain", "adapter")));
    assertEquals(
        Map.of("historical-owner", "simplepoint-module-adapter"),
        ModuleResourcePlan.registrationClients(split, Map.of()));
    assertEquals(
        ModuleResourcePlan.registrationClients(split, Map.of()),
        ModuleResourcePlan.registrationClients(combined, Map.of()));
    assertEquals(
        Map.of("historical-owner", "installed-adapter-client"),
        ModuleResourcePlan.registrationClients(
            split, Map.of("adapter", "installed-adapter-client")));
    assertEquals(
        "SHARED_MODULE_CLIENT_ID",
        assertThrows(
                ModuleCompositionException.class,
                () ->
                    ModuleResourcePlan.registrationClients(
                        split, Map.of("domain", "shared", "adapter", "shared")))
            .code());
    assertEquals(
        "UNKNOWN_MODULE_CLIENT_ID",
        assertThrows(
                ModuleCompositionException.class,
                () -> ModuleResourcePlan.registrationClients(split, Map.of("absent", "unselected")))
            .code());
  }

  @Test
  void freshDeploymentClientsRemainStableAcrossHostRelocationButChangeAcrossReleases() {
    var catalog = new ModuleCatalog(List.of(Adapter.class, Domain.class));
    var split = catalog.deploy(Map.of("first", List.of("domain"), "second", List.of("adapter")));
    var combined = catalog.deploy(Map.of("combined", List.of("domain", "adapter")));
    var operation = java.util.UUID.randomUUID();
    var clients = ModuleClientPlan.deploymentIdentities(split, Map.of(), operation);
    assertEquals(clients, ModuleClientPlan.deploymentIdentities(combined, Map.of(), operation));
    assertEquals(
        Map.of("historical-owner", clients.get("adapter")),
        ModuleResourcePlan.deploymentRegistrationClients(split, Map.of(), operation));
    org.junit.jupiter.api.Assertions.assertNotEquals(
        clients,
        ModuleClientPlan.deploymentIdentities(combined, Map.of(), java.util.UUID.randomUUID()));
    assertThrows(
        ModuleCompositionException.class,
        () ->
            ModuleClientPlan.deploymentIdentities(
                split, Map.of("domain", "x".repeat(256)), operation));
    assertThrows(
        ModuleCompositionException.class,
        () -> ModuleClientPlan.deploymentIdentities(split, Map.of(), null));
  }

  @Test
  void invalidClientIdentifiersCannotBecomePoliciesOrRuntimeCredentials() {
    var plan = new ModuleCatalog(List.of(Adapter.class)).plan(List.of("adapter"));
    for (String invalid :
        List.of("", " ", " leading", "line\nbreak", "*", "x".repeat(257), "\uD800")) {
      assertEquals(
          "INVALID_MODULE_CLIENT_ID",
          assertThrows(
                  ModuleCompositionException.class,
                  () -> ModuleClientPlan.identities(plan, Map.of("adapter", invalid)))
              .code());
    }
  }

  @Test
  void twoPartialWritersCannotShareAnOwnerEvenOnDifferentHosts() {
    var catalog = new ModuleCatalog(List.of(Adapter.class, Duplicate.class));
    assertEquals(
        "DUPLICATE_RESOURCE_OWNER",
        assertThrows(
                ModuleCompositionException.class,
                () -> catalog.plan(List.of("adapter", "duplicate")))
            .code());
    assertEquals(
        "DUPLICATE_RESOURCE_OWNER",
        assertThrows(
                ModuleCompositionException.class,
                () ->
                    catalog.deploy(Map.of("one", List.of("adapter"), "two", List.of("duplicate"))))
            .code());
  }

  @Test
  void missingRegistrationOrOwnershipAndInvalidIdentitiesFailDuringPlanning() {
    for (var sample :
        Map.of(
                MissingRegistrar.class,
                "RESOURCE_INITIALIZER_REQUIRED",
                MissingOwner.class,
                "MODULE_RESOURCE_OWNER_REQUIRED",
                InvalidOwner.class,
                "INVALID_RESOURCE_OWNER")
            .entrySet()) {
      var catalog = new ModuleCatalog(List.of(sample.getKey()));
      assertEquals(
          sample.getValue(),
          assertThrows(ModuleCompositionException.class, () -> catalog.plan(List.of("invalid")))
              .code());
    }
  }

  @ModuleInitialization(registersResources = true)
  static class Registrar implements ModuleInitializer {
    @Override
    public Runnable prepare(ModuleInitializationContext context) {
      return () -> {};
    }
  }

  @PlatformModule(id = "adapter", packages = "fixture.adapter", initializers = Registrar.class)
  @ModuleResourceOwner("historical-owner")
  static class Adapter {}

  @PlatformModule(id = "domain", packages = "fixture.domain")
  static class Domain {}

  @PlatformModule(id = "duplicate", packages = "fixture.duplicate", initializers = Registrar.class)
  @ModuleResourceOwner("historical-owner")
  static class Duplicate {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid")
  @ModuleResourceOwner("historical-owner")
  static class MissingRegistrar {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid", initializers = Registrar.class)
  static class MissingOwner {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid", initializers = Registrar.class)
  @ModuleResourceOwner("../other")
  static class InvalidOwner {}
}
