package org.simplepoint.http.fixture.sharedalpha;

import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared prefix fixture with overlapping roots inside a single owner and legacy aliases. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "shared-alpha", packages = "org.simplepoint.http.fixture.sharedalpha")
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
public class SharedAlphaModule {
  /** Same controller with current and legacy aliases. */
  @RestController
  @RequestMapping({"/users", "/platform/users"})
  public static class Accounts {
    /** Identifies the selected account handler. */
    @GetMapping
    public String list() {
      return "accounts";
    }
  }

  /** Nested route owned by the account module. */
  @RestController
  @RequestMapping("/users/preferences")
  public static class Preferences {
    /** Identifies the nested handler. */
    @GetMapping
    public String current() {
      return "preferences";
    }
  }
}
