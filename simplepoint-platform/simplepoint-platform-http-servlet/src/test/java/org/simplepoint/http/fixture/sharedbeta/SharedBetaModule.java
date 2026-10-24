package org.simplepoint.http.fixture.sharedbeta;

import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared prefix fixture using an inherited controller declaration. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "shared-beta", packages = "org.simplepoint.http.fixture.sharedbeta")
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
public class SharedBetaModule {
  /** Interface-owned mapping inherited by the concrete controller. */
  @RequestMapping("/oidc/clients")
  public interface ClientApi {}

  /** Concrete controller without a duplicated type mapping. */
  @RestController
  public static class Clients implements ClientApi {
    /** Identifies the selected client handler. */
    @GetMapping
    public String list() {
      return "clients";
    }
  }
}
