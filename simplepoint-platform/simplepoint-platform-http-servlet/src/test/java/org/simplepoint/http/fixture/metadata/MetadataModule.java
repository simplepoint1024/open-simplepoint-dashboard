package org.simplepoint.http.fixture.metadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Planning must read declarations even when conditions disable construction of their beans. */
@PlatformModule(id = "metadata", packages = "org.simplepoint.http.fixture.metadata")
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
public class MetadataModule {
  /** Composed mapping for a conditionally disabled controller. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @RequestMapping("/reserved/{id}")
  public @interface ReservedApi {}

  /** Fails immediately if metadata inspection initializes this class. */
  @RestController
  @Profile("disabled-fixture")
  @ReservedApi
  public static class NeverInitialized {
    static {
      if (Boolean.parseBoolean("true")) {
        throw new IllegalStateException("Metadata planning initialized a controller");
      }
    }
  }
}
