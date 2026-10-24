package org.simplepoint.http.fixture.alpha;

/** Isolated fixture for selected-module HTTP ownership and route checks. */
@org.simplepoint.platform.composition.PlatformModule(
    id = "alpha",
    packages = "org.simplepoint.http.fixture.alpha")
@org.simplepoint.platform.composition.ModuleHttp(namespace = "/alpha")
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class AlphaModule {}
