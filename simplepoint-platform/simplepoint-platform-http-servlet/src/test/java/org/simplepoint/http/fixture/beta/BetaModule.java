package org.simplepoint.http.fixture.beta;

/** Isolated fixture for selected-module HTTP ownership and route checks. */
@org.simplepoint.platform.composition.PlatformModule(
    id = "beta",
    packages = "org.simplepoint.http.fixture.beta")
@org.simplepoint.platform.composition.ModuleHttp(namespace = "/beta")
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class BetaModule {}
