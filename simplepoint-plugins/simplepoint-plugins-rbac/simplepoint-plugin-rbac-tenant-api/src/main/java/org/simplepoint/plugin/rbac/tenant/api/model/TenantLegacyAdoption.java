package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;

/** Explicit migration evidence; no historical purchase or activation date is inferred. */
public record TenantLegacyAdoption(String tenantId, Instant adoptedAt, String adoptedBy, int resourceCount, int instanceCount) {
  /** Bounded preflight summary; execution rereads current definitions and relations. */
  public record Preview(String tenantId, int directoryAdditions, int legacyResourceCount, int baselineResourceCount,
      List<Source> sources) {
    /** Retains the exact preflight source summaries without mutable caller collections. */
    public Preview {
      sources = List.copyOf(sources);
    }
  }

  /** Source facts are tenant-owned package evidence, not an additional resource-origin category. */
  public record Source(String packageCode, String packageName, TenantPackageInstance.Acquisition acquisition, int resourceCount) {}
}
