package org.simplepoint.plugin.auditing.logging.module;

import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogRemoteService;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;

/** Declares the audit-logging module and its deployment-independent dependencies. */
@ModuleEvents(AuditEventDeclarations.class)
@ModuleMigrations
@PlatformModule(
    id = "audit-logging",
    packages = {
      "org.simplepoint.plugin.auditing.logging.api",
      "org.simplepoint.plugin.auditing.logging.module",
      "org.simplepoint.plugin.auditing.logging.repository",
      "org.simplepoint.plugin.auditing.logging.service"
    },
    provides = {ErrorLogRemoteService.class, ResourceGrantLogRemoteService.class})
public final class AuditLoggingModule {
  private AuditLoggingModule() {}
}
