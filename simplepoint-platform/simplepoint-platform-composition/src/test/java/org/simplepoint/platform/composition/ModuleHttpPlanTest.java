package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleHttpPlanTest {
  @Test
  void namespacesRemainStableWhenAdaptersMoveBetweenHosts() {
    var catalog = new ModuleCatalog(List.of(Alpha.class, Beta.class));
    assertEquals(
        Map.of("/alpha", "one", "/beta", "two"),
        catalog.deploy(Map.of("one", List.of("alpha"), "two", List.of("beta"))).httpRoutes());
    assertEquals(
        Map.of("/alpha", "aggregate", "/beta", "aggregate"),
        catalog.deploy(Map.of("aggregate", List.of("alpha", "beta"))).httpRoutes());
    assertEquals(
        Map.of("alpha", "/alpha"),
        ModuleHttpPlan.from(catalog.plan(List.of("alpha"))).namespaces());
    assertTrue(ModuleHttpPlan.contains("/work", "/work/records"));
    assertFalse(ModuleHttpPlan.contains("/work", "/workflow/records"));
  }

  @Test
  void invalidOrOverlappingNamespacesFailBeforeAnyRuntimeStarts() {
    var invalid = new ModuleCatalog(List.of(Invalid.class));
    assertEquals(
        "INVALID_HTTP_NAMESPACE",
        assertThrows(ModuleCompositionException.class, () -> invalid.plan(List.of("invalid")))
            .code());
    for (Class<?> collision : List.of(Same.class, Nested.class)) {
      var catalog = new ModuleCatalog(List.of(Alpha.class, collision));
      assertEquals(
          "CONFLICTING_HTTP_NAMESPACE",
          assertThrows(
                  ModuleCompositionException.class,
                  () -> catalog.plan(List.of("alpha", "collision")))
              .code());
      assertEquals(
          "CONFLICTING_HTTP_NAMESPACE",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      catalog.deploy(Map.of("one", List.of("alpha"), "two", List.of("collision"))))
              .code());
    }
  }

  @Test
  void rootMountRequiresExplicitBoundedRoutesAndCannotCaptureAnotherOwner() {
    for (Class<?> type : List.of(Unbounded.class, CatchAll.class)) {
      var catalog = new ModuleCatalog(List.of(type));
      assertThrows(ModuleCompositionException.class, () -> catalog.plan(List.of("protocol")));
    }
    var catalog = new ModuleCatalog(List.of(Protocol.class, Alpha.class));
    assertEquals(
        Set.of("/.well-known/example", "/mcp"),
        ModuleHttpPlan.from(catalog.plan(List.of("protocol", "alpha"))).routes().get("protocol"));
    var collision = new ModuleCatalog(List.of(Protocol.class, ProtocolCollision.class));
    assertEquals(
        "CONFLICTING_HTTP_NAMESPACE",
        assertThrows(
                ModuleCompositionException.class,
                () -> collision.plan(List.of("protocol", "collision")))
            .code());
  }

  @PlatformModule(id = "protocol", packages = "fixture.protocol")
  @ModuleHttp(namespace = "/")
  static class Unbounded {}

  @PlatformModule(id = "protocol", packages = "fixture.protocol")
  @ModuleHttp(namespace = "/", routes = CatchAllRoutes.class)
  static class CatchAll {}

  public static class CatchAllRoutes implements ModuleHttpRoutes {
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/");
    }
  }

  @PlatformModule(id = "protocol", packages = "fixture.protocol")
  @ModuleHttp(namespace = "/", routes = ProtocolRoutes.class)
  static class Protocol {}

  public static class ProtocolRoutes implements ModuleHttpRoutes {
    public Set<String> roots(ModuleDescriptor module) {
      return Set.of("/mcp", "/.well-known/example");
    }
  }

  @PlatformModule(id = "collision", packages = "fixture.collision")
  @ModuleHttp(namespace = "/mcp")
  static class ProtocolCollision {}

  @PlatformModule(id = "alpha", packages = "fixture.alpha")
  @ModuleHttp(namespace = "/alpha")
  static class Alpha {}

  @PlatformModule(id = "beta", packages = "fixture.beta")
  @ModuleHttp(namespace = "/beta")
  static class Beta {}

  @PlatformModule(id = "invalid", packages = "fixture.invalid")
  @ModuleHttp(namespace = "/alpha/../escape")
  static class Invalid {}

  @PlatformModule(id = "collision", packages = "fixture.same")
  @ModuleHttp(namespace = "/alpha")
  static class Same {}

  @PlatformModule(id = "collision", packages = "fixture.nested")
  @ModuleHttp(namespace = "/alpha/nested")
  static class Nested {}
}
