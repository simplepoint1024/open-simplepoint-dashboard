package org.simplepoint.http.fixture.domain;

/** Isolated fixture for selected-module HTTP ownership and route checks. */
@org.simplepoint.platform.composition.PlatformModule(
    id = "domain",
    packages = "org.simplepoint.http.fixture.domain")
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class DomainModule {}
