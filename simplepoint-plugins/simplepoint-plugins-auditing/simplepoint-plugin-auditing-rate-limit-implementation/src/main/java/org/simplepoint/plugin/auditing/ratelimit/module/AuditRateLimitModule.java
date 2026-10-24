package org.simplepoint.plugin.auditing.ratelimit.module;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the audit-rate-limit module and its deployment-independent dependencies. */
@ModuleMigrations
@PlatformModule(
    id = "audit-rate-limit",
    packages = {
      "org.simplepoint.plugin.auditing.ratelimit.api",
      "org.simplepoint.plugin.auditing.ratelimit.module",
      "org.simplepoint.plugin.auditing.ratelimit.repository",
      "org.simplepoint.plugin.auditing.ratelimit.service"
    })
public final class AuditRateLimitModule {
  private AuditRateLimitModule() {}
}
