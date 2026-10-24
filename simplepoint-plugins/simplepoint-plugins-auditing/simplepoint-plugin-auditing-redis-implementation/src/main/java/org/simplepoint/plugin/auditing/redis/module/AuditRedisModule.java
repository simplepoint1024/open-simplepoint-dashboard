package org.simplepoint.plugin.auditing.redis.module;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the audit-redis module and its deployment-independent dependencies. */
@ModuleMigrations(ownsSchema = false)
@PlatformModule(
    id = "audit-redis",
    packages = {
      "org.simplepoint.plugin.auditing.redis.api",
      "org.simplepoint.plugin.auditing.redis.module",
      "org.simplepoint.plugin.auditing.redis.service"
    })
public final class AuditRedisModule {
  private AuditRedisModule() {}
}
