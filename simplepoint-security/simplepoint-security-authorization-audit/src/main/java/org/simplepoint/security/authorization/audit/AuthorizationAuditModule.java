package org.simplepoint.security.authorization.audit;

import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Configuration;

/** Owns durable authentication audit facts independently of the HTTP adapter and host. */
@PlatformModule(
    id = "authorization-audit",
    packages = "org.simplepoint.security.authorization.audit")
@ModuleEvents(AuthorizationEventDeclarations.class)
@ModuleMigrations(ownsSchema = false)
@ModulePersistence
@Configuration(proxyBeanMethods = false)
public class AuthorizationAuditModule {}
