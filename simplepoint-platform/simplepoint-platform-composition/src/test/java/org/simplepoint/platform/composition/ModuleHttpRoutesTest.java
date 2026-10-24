package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleHttpRoutesTest {
  @Test
  void subtreesRemainStableAcrossSplitAndAggregateHosts() {
    var catalog = new ModuleCatalog(List.of(Identity.class, Clients.class));
    assertEquals(
        Map.of("/common/users", "one", "/common/oidc", "two"),
        catalog.deploy(Map.of("one", List.of("identity"), "two", List.of("clients"))).httpRoutes());
    assertEquals(
        Map.of("/common/users", "all", "/common/oidc", "all"),
        catalog.deploy(Map.of("all", List.of("identity", "clients"))).httpRoutes());
    var plan = ModuleHttpPlan.from(catalog.plan(List.of("identity", "clients")));
    assertEquals(Map.of("identity", "/common", "clients", "/common"), plan.namespaces());
    assertEquals(Set.of("/common/users"), plan.routes().get("identity"));
    assertThrows(UnsupportedOperationException.class, () -> plan.routes().clear());
    assertThrows(UnsupportedOperationException.class, () -> plan.routes().get("identity").clear());
  }

  @Test
  void wholeNamespaceAndNestedOwnerConflictsFailAcrossHosts() {
    for (Class<?> conflicting : List.of(Whole.class, Nested.class)) {
      var catalog = new ModuleCatalog(List.of(Identity.class, conflicting));
      assertEquals(
          "CONFLICTING_HTTP_NAMESPACE",
          assertThrows(
                  ModuleCompositionException.class,
                  () -> catalog.deploy(Map.of("one", List.of("identity"), "two", List.of("other"))))
              .code());
    }
  }

  @Test
  void malformedReadersFailBeforeRuntimeStarts() {
    for (Class<?> module : List.of(Empty.class, Invalid.class, Unconstructable.class)) {
      var failure =
          assertThrows(
              ModuleCompositionException.class,
              () -> new ModuleCatalog(List.of(module)).plan(List.of("invalid")));
      assertEquals(
          module == Unconstructable.class
              ? "MODULE_HTTP_ROUTES_FAILED"
              : "INVALID_MODULE_HTTP_ROUTES",
          failure.code());
    }
  }

  public static class UserRoots implements ModuleHttpRoutes {
    @Override
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/users", "/users/preferences");
    }
  }

  public static class ClientRoots implements ModuleHttpRoutes {
    @Override
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/oidc");
    }
  }

  public static class NestedRoots implements ModuleHttpRoutes {
    @Override
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/users/preferences");
    }
  }

  public static class EmptyRoots implements ModuleHttpRoutes {
    @Override
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of();
    }
  }

  public static class InvalidRoots implements ModuleHttpRoutes {
    @Override
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/../escape");
    }
  }

  public static class UnconstructableRoots extends EmptyRoots {
    private UnconstructableRoots() {}
  }

  @PlatformModule(id = "identity", packages = "fixture.identity")
  @ModuleHttp(namespace = "/common", routes = UserRoots.class)
  static class Identity {}

  @PlatformModule(id = "clients", packages = "fixture.clients")
  @ModuleHttp(namespace = "/common", routes = ClientRoots.class)
  static class Clients {}

  @PlatformModule(id = "other", packages = "fixture.other")
  @ModuleHttp(namespace = "/common")
  static class Whole {}

  @PlatformModule(id = "other", packages = "fixture.other")
  @ModuleHttp(namespace = "/common", routes = NestedRoots.class)
  static class Nested {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid")
  @ModuleHttp(namespace = "/common", routes = EmptyRoots.class)
  static class Empty {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid")
  @ModuleHttp(namespace = "/common", routes = InvalidRoots.class)
  static class Invalid {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid")
  @ModuleHttp(namespace = "/common", routes = UnconstructableRoots.class)
  static class Unconstructable {}
}
