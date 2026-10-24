package org.simplepoint.composition.fixture.missing;

import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Configuration;

/** Fixture used to verify explicit module selection and startup validation. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "missing", packages = "org.simplepoint.composition.fixture.missing",
    provides = Greeting.class)
public class MissingModule {}
