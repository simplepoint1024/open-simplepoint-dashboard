package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.remoting.RemoteContract;

class ModuleCatalogTest {

  @TempDir Path temporary;

  @Test
  void requiredClosureIsOrderedWithoutActivatingUnselectedModules() {
    var catalog = new ModuleCatalog(List.of(Form.class, Data.class, Unrelated.class));
    ModulePlan plan = catalog.plan(List.of("form"));

    assertEquals(
        List.of("data", "form"), plan.modules().stream().map(ModuleDescriptor::id).toList());
    assertEquals(Map.of("records:1", "data"), plan.capabilityOwners());
    assertEquals(Map.of("approval:1", Approval.class), plan.remoteCapabilities());
  }

  @Test
  void declarationsAndRootOrderDoNotChangePlan() {
    ModulePlan first =
        new ModuleCatalog(List.of(Form.class, Data.class)).plan(List.of("form", "data"));
    ModulePlan second =
        new ModuleCatalog(List.of(Data.class, Form.class)).plan(List.of("data", "form", "form"));

    assertEquals(first, second);
  }

  @Test
  void planAndCatalogCannotBeMutated() {
    var catalog = new ModuleCatalog(List.of(Data.class));
    ModulePlan plan = catalog.plan(List.of("data"));

    assertThrows(UnsupportedOperationException.class, () -> catalog.modules().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.modules().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.capabilityOwners().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.remoteCapabilities().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> plan.modules().getFirst().provides().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> plan.modules().getFirst().packages().clear());
  }

  @Test
  void missingAndIncompatibleLocalDependenciesHaveStableDiagnostics() {
    assertCode(
        "MISSING_MODULE", () -> new ModuleCatalog(List.of(Form.class)).plan(List.of("form")));
    assertCode("MISSING_MODULE", () -> new ModuleCatalog(List.of()).plan(List.of("unknown")));
    assertCode(
        "INCOMPATIBLE_MODULE_VERSION",
        () -> new ModuleCatalog(List.of(Form.class, DataV2.class)).plan(List.of("form")));
  }

  @Test
  void duplicateModuleIdsFailBeforeSelection() {
    assertCode("DUPLICATE_MODULE", () -> new ModuleCatalog(List.of(Data.class, DataV2.class)));
    assertCode("DUPLICATE_MODULE", () -> new ModuleCatalog(List.of(Data.class, Data.class)));
  }

  @Test
  void localDependencyCycleFailsWithItsPath() {
    var failure =
        assertCode(
            "DEPENDENCY_CYCLE",
            () -> new ModuleCatalog(List.of(CycleA.class, CycleB.class)).plan(List.of("cycle-a")));

    assertTrue(failure.getMessage().contains("cycle-a -> cycle-b -> cycle-a"));
  }

  @Test
  void bidirectionalApiConsumptionIsNotAnActivationCycle() {
    ModulePlan plan =
        new ModuleCatalog(List.of(Data.class, ApprovalModule.class))
            .plan(List.of("data", "approval"));

    assertTrue(plan.remoteCapabilities().isEmpty());
    assertEquals(Map.of("records:1", "data", "approval:1", "approval"), plan.capabilityOwners());
  }

  @Test
  void overlappingPackagesAndCompetingProvidersFail() {
    assertCode(
        "OVERLAPPING_PACKAGES",
        () ->
            new ModuleCatalog(List.of(Data.class, Overlap.class)).plan(List.of("data", "overlap")));
    assertCode(
        "AMBIGUOUS_CAPABILITY",
        () ->
            new ModuleCatalog(List.of(Data.class, Alternative.class))
                .plan(List.of("data", "alternative")));
  }

  @Test
  void externalBindingsCannotHideIncompatibleJavaContracts() {
    assertCode(
        "INCOMPATIBLE_CAPABILITY_TYPE",
        () ->
            new ModuleCatalog(List.of(Consumer.class, OtherConsumer.class))
                .plan(List.of("consumer", "other-consumer")));
    assertCode(
        "INCOMPATIBLE_CAPABILITY_TYPE",
        () ->
            new ModuleCatalog(List.of(Data.class, OtherConsumer.class))
                .plan(List.of("data", "other-consumer")));
  }

  @Test
  void componentOwnershipRequiresActualPackageBoundary() {
    var plan =
        new ModuleCatalog(List.of(Owner.class, SimilarPrefix.class))
            .plan(List.of("owner", "similar"));

    assertEquals("owner", plan.ownerOf(ModuleCatalogTest.class).orElseThrow().id());
    assertTrue(plan.ownerOf(String.class).isEmpty());
  }

  @Test
  void indexDiscoveryDoesNotInitializeConfigurationClasses() throws Exception {
    writeIndex(temporary, "# declarations\n\n" + Indexed.class.getName() + " # module\n");
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {temporary.toUri().toURL()}, getClass().getClassLoader())) {
      ModulePlan plan = ModuleCatalog.discover(loader).plan(List.of("indexed"));

      assertEquals(Indexed.class, plan.modules().getFirst().configuration());
      assertFalse(Initialization.initialized);
    }
  }

  @Test
  void allClasspathIndexesParticipateInDuplicateChecks() throws Exception {
    Path first = temporary.resolve("first");
    Path second = temporary.resolve("second");
    writeIndex(first, Data.class.getName());
    writeIndex(second, DataV2.class.getName());
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {second.toUri().toURL(), first.toUri().toURL()},
            getClass().getClassLoader())) {
      assertCode("DUPLICATE_MODULE", () -> ModuleCatalog.discover(loader));
    }
  }

  @Test
  void malformedOrOversizedIndexesFailInsteadOfPartiallyLoading() throws Exception {
    writeIndex(temporary, "missing.Configuration");
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {temporary.toUri().toURL()}, getClass().getClassLoader())) {
      assertCode("MODULE_LOAD_FAILED", () -> ModuleCatalog.discover(loader));
      writeIndex(temporary, "#".repeat(65_537));
      assertCode("INDEX_LIMIT", () -> ModuleCatalog.discover(loader));
    }
  }

  @Test
  void invalidDeclarationsAreRejected() {
    assertCode("INVALID_MODULE", () -> new ModuleCatalog(List.of(String.class)));
    assertCode("INVALID_MODULE_IDENTITY", () -> new ModuleCatalog(List.of(InvalidId.class)));
    assertCode("INVALID_PACKAGE", () -> new ModuleCatalog(List.of(Wildcard.class)));
    assertCode("DUPLICATE_DEPENDENCY", () -> new ModuleCatalog(List.of(DuplicateDependency.class)));
    assertCode("INVALID_CAPABILITY", () -> new ModuleCatalog(List.of(InvalidContract.class)));
  }

  @Test
  void deploymentDerivesRemoteRoutesAndRemovesThemAfterAggregation() {
    var catalog = new ModuleCatalog(List.of(Data.class, ApprovalModule.class, Form.class));
    var split =
        catalog.deploy(Map.of("business", List.of("form"), "approvals", List.of("approval")));
    var merged = catalog.deploy(Map.of("standalone", List.of("form", "approval")));

    assertEquals(Map.of("approval:1", "approvals"), split.routesFor("business"));
    assertEquals(Map.of("records:1", "business"), split.routesFor("approvals"));
    assertEquals(Map.of(), merged.routesFor("standalone"));
    assertEquals(split.capabilityHosts().keySet(), merged.capabilityHosts().keySet());
    assertThrows(UnsupportedOperationException.class, () -> split.hosts().clear());
    assertThrows(UnsupportedOperationException.class, () -> split.capabilityHosts().clear());
    assertThrows(UnsupportedOperationException.class, () -> split.routesFor("business").clear());
    assertCode("UNKNOWN_HOST", () -> split.routesFor("absent"));
  }

  @Test
  void fullDeploymentRejectsUnresolvedRemoteCapabilities() {
    var catalog = new ModuleCatalog(List.of(Data.class, ApprovalModule.class));

    assertCode("MISSING_CAPABILITY", () -> catalog.deploy(Map.of("business", List.of("data"))));
    assertCode("INVALID_HOST_COUNT", () -> catalog.deploy(Map.of()));
    assertCode("INVALID_HOST", () -> catalog.deploy(Map.of("bad host", List.of("data"))));
    assertCode("INVALID_HOST", () -> catalog.deploy(Map.of("empty", List.of())));
  }

  @Test
  void deploymentsCannotSilentlyDuplicateModuleOwnershipThroughLocalClosure() {
    var catalog = new ModuleCatalog(List.of(Data.class, Form.class, ApprovalModule.class));
    var error =
        assertCode(
            "DUPLICATE_MODULE_HOST",
            () ->
                catalog.deploy(Map.of("one", List.of("form"), "two", List.of("data", "approval"))));

    assertTrue(error.getMessage().contains("data: one, two"));
  }

  @Test
  void deploymentsRejectCompetingOrIncompatibleRemoteProviders() {
    var catalog =
        new ModuleCatalog(
            List.of(Data.class, Alternative.class, OtherConsumer.class, ApprovalModule.class));

    assertCode(
        "AMBIGUOUS_CAPABILITY",
        () ->
            catalog.deploy(
                Map.of("one", List.of("data", "approval"), "two", List.of("alternative"))));
    assertCode(
        "INCOMPATIBLE_CAPABILITY_TYPE",
        () ->
            catalog.deploy(
                Map.of("one", List.of("data", "approval"), "two", List.of("other-consumer"))));
  }

  private static ModuleCompositionException assertCode(final String code, final Runnable action) {
    var failure = assertThrows(ModuleCompositionException.class, action::run);
    assertEquals(code, failure.code());
    return failure;
  }

  @Test
  void startupDependenciesParticipateInDeploymentValidationWithoutDuplicatedContractLists() {
    var catalog = new ModuleCatalog(List.of(StartupConsumer.class, DataV2.class));
    var plan = catalog.plan(List.of("startup"));
    assertEquals(Map.of("records:1", Records.class), plan.remoteCapabilities());
    assertEquals(List.of(Startup.class), plan.modules().getFirst().initializers());
    assertCode("MISSING_CAPABILITY", () -> catalog.deploy(Map.of("consumer", List.of("startup"))));
    assertEquals(
        Map.of("records:1", "provider"),
        catalog
            .deploy(Map.of("consumer", List.of("startup"), "provider", List.of("data")))
            .routesFor("consumer"));
  }

  @Test
  void malformedOrConflictingInitializerDeclarationsAreRejected() {
    assertCode(
        "INVALID_MODULE_INITIALIZER", () -> new ModuleCatalog(List.of(DuplicateStartup.class)));
    assertCode(
        "INVALID_MODULE_INITIALIZER",
        () -> new ModuleCatalog(List.of(MissingStartupDeclaration.class)));
    assertCode(
        "INCOMPATIBLE_CAPABILITY_TYPE", () -> new ModuleCatalog(List.of(ConflictingStartup.class)));
  }

  private static void writeIndex(final Path root, final String content) throws Exception {
    Path index = root.resolve(ModuleCatalog.INDEX);
    Files.createDirectories(index.getParent());
    Files.writeString(index, content);
  }

  @RemoteContract(name = "records")
  interface Records {}

  @RemoteContract(name = "records")
  interface OtherRecords {}

  @RemoteContract(name = "approval")
  interface Approval {}

  @PlatformModule(
      id = "data",
      packages = "example.data",
      provides = Records.class,
      consumes = Approval.class)
  static class Data {}

  @PlatformModule(id = "data", version = 2, packages = "example.data", provides = Records.class)
  static class DataV2 {}

  @PlatformModule(
      id = "form",
      packages = "example.form",
      requires = @ModuleDependency(id = "data"),
      consumes = {Records.class, Approval.class})
  static class Form {}

  @PlatformModule(id = "unused", packages = "example.unused")
  static class Unrelated {}

  @PlatformModule(
      id = "approval",
      packages = "example.approval",
      provides = Approval.class,
      consumes = Records.class)
  static class ApprovalModule {}

  @PlatformModule(
      id = "cycle-a",
      packages = "example.cyclea",
      requires = @ModuleDependency(id = "cycle-b"))
  static class CycleA {}

  @PlatformModule(
      id = "cycle-b",
      packages = "example.cycleb",
      requires = @ModuleDependency(id = "cycle-a"))
  static class CycleB {}

  @PlatformModule(id = "overlap", packages = "example.data.nested")
  static class Overlap {}

  @PlatformModule(id = "alternative", packages = "example.alternative", provides = Records.class)
  static class Alternative {}

  @PlatformModule(id = "consumer", packages = "example.consumer", consumes = Records.class)
  static class Consumer {}

  @PlatformModule(id = "other-consumer", packages = "example.other", consumes = OtherRecords.class)
  static class OtherConsumer {}

  @PlatformModule(id = "owner", packages = "org.simplepoint.platform.composition")
  static class Owner {}

  @PlatformModule(id = "similar", packages = "org.simplepoint.platform.compositionextra")
  static class SimilarPrefix {}

  @PlatformModule(id = "indexed", packages = "example.indexed")
  static class Indexed {
    static {
      Initialization.initialized = true;
    }
  }

  static class Initialization {
    static boolean initialized;
  }

  @PlatformModule(id = "INVALID", packages = "example.invalid")
  static class InvalidId {}

  @PlatformModule(id = "wildcard", packages = "example.*")
  static class Wildcard {}

  @PlatformModule(
      id = "duplicate",
      packages = "example.duplicate",
      requires = {@ModuleDependency(id = "data"), @ModuleDependency(id = "data")})
  static class DuplicateDependency {}

  @PlatformModule(id = "invalid", packages = "example.invalid", provides = String.class)
  static class InvalidContract {}

  @ModuleInitialization(consumes = Records.class)
  static class Startup implements ModuleInitializer {
    @Override
    public Runnable prepare(ModuleInitializationContext context) {
      throw new AssertionError("Planning must not run an initializer");
    }
  }

  static class UndeclaredStartup extends Startup {}

  @PlatformModule(id = "startup", packages = "example.startup", initializers = Startup.class)
  static class StartupConsumer {}

  @PlatformModule(
      id = "duplicate-startup",
      packages = "example.duplicatestartup",
      initializers = {Startup.class, Startup.class})
  static class DuplicateStartup {}

  @PlatformModule(
      id = "missing-startup",
      packages = "example.missingstartup",
      initializers = UndeclaredStartup.class)
  static class MissingStartupDeclaration {}

  @PlatformModule(
      id = "conflicting-startup",
      packages = "example.conflictingstartup",
      initializers = Startup.class,
      consumes = OtherRecords.class)
  static class ConflictingStartup {}
}
