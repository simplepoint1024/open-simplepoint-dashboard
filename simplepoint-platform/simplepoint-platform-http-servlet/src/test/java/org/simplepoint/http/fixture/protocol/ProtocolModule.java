package org.simplepoint.http.fixture.protocol;

import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Root protocol fixture with complete method-level paths. */
@PlatformModule(id = "protocol", packages = "org.simplepoint.http.fixture.protocol")
@ModuleHttp(namespace = "/", routes = ControllerHttpRoutes.class)
@Configuration(proxyBeanMethods = false)
public class ProtocolModule {
  /** Public metadata and protocol endpoints. */
  @RestController
  public static class Metadata {
    /** Returns metadata without a class-level route. */
    @GetMapping("/.well-known/oauth-protected-resource/example/{code}")
    public String metadata() {
      return "metadata";
    }

    /** Returns the protocol response. */
    @GetMapping("/mcp/{code}")
    public String protocol() {
      return "protocol";
    }
  }
}
