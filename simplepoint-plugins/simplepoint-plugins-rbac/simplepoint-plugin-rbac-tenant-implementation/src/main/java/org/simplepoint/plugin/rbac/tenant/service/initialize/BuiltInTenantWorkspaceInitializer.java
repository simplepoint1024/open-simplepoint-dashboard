package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.service.impl.BuiltInTenantWorkspaceWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Declares the existing seed contribution; an owned domain proxy supplies its atomic write boundary. */
@Component
public class BuiltInTenantWorkspaceInitializer {
  private final BuiltInTenantWorkspaceWriter writer;

  /** Keeps startup declaration discovery free of persistence effects. */
  public BuiltInTenantWorkspaceInitializer(BuiltInTenantWorkspaceWriter writer) {
    this.writer = writer;
  }

  /** Retains the original key, version and ordering; existing successful startup records are not replayed. */
  @Bean
  public PlatformBootstrapContribution builtInTenantWorkspaceBootstrapContribution() {
    return () -> BootstrapContribution.versioned(
        "rbac-tenant", "workspace", "default-tenant-workspace", "3", 400, writer::initializeWorkspace);
  }
}
