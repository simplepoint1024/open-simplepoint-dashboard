package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;

/** Actual Common controller metadata; these fixtures are not production module declarations. */
class CommonHttpRoutePlanningTest {
  @Test
  void realIdentityOidcAndLocaleControllersCanShareCommonWithoutOwningEachOthersPaths() {
    var catalog = new ModuleCatalog(List.of(IdentityHttp.class, OidcHttp.class, LocaleHttp.class));
    var split =
        catalog.deploy(
            Map.of(
                "identity-host", List.of("identity-http"),
                "oidc-host", List.of("oidc-http"),
                "locale-host", List.of("locale-http")));
    var combined =
        catalog.deploy(Map.of("combined", List.of("identity-http", "oidc-http", "locale-http")));
    assertThat(combined.httpRoutes().keySet()).isEqualTo(split.httpRoutes().keySet());
    assertThat(combined.httpRoutes().values()).containsOnly("combined");
    assertThat(split.httpRoutes())
        .containsEntry("/common/users", "identity-host")
        .containsEntry("/common/platform/tenants", "identity-host")
        .containsEntry("/common/tenants", "identity-host")
        .containsEntry("/common/role-resource-configuration/roles", "identity-host")
        .containsEntry("/common/oidc/clients", "oidc-host")
        .containsEntry("/common/oidc/external-providers", "oidc-host")
        .containsEntry("/common/i18n/messages", "locale-host")
        .doesNotContainKey("/common");
    var identities =
        ModuleHttpPlan.from(split.hosts().get("identity-host")).routes().get("identity-http");
    assertThat(identities)
        .contains("/common/users")
        .doesNotContain("/common/users/preferences", "/common/users/me");
    assertThat(ModuleHttpPlan.from(split.hosts().get("oidc-host")).routes().get("oidc-http"))
        .containsExactlyInAnyOrder("/common/oidc/clients", "/common/oidc/external-providers");
    assertThat(ModuleHttpPlan.from(split.hosts().get("locale-host")).routes().get("locale-http"))
        .hasSize(6);
  }

  @PlatformModule(
      id = "identity-http",
      packages = {
        "org.simplepoint.plugin.rbac.core.rest",
        "org.simplepoint.plugin.rbac.tenant.rest",
        "org.simplepoint.plugin.rbac.resource.rest"
      })
  @ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
  static class IdentityHttp {}

  @PlatformModule(id = "oidc-http", packages = "org.simplepoint.plugin.oidc.rest")
  @ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
  static class OidcHttp {}

  @PlatformModule(id = "locale-http", packages = "org.simplepoint.plugin.i18n.rest")
  @ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
  static class LocaleHttp {}
}
