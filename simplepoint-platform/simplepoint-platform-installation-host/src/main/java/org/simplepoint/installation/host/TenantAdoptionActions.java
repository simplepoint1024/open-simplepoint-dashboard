package org.simplepoint.installation.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionMaintenance;

/** Pure installer bridge: pins tenant identities, while each domain transaction reads current definitions. */
final class TenantAdoptionActions {
  private static final int MAX_TENANTS = 10000;

  private TenantAdoptionActions() {}

  record Entry(String tenantId, int directoryAdditions, int legacyResources, int baselineResources) {}

  static List<Entry> inventory(TenantLegacyAdoptionMaintenance owner) {
    var result = new ArrayList<Entry>();
    String after = null;
    while (true) {
      var page = owner.pending(after, 100);
      if (page.size() > 100 || result.size() + page.size() > MAX_TENANTS) {
        throw new IllegalArgumentException("Tenant adoption inventory exceeds its bound");
      }
      for (String tenant : page) {
        identity(tenant);
        if (after != null && tenant.compareTo(after) <= 0) {
          throw new IllegalArgumentException("Tenant adoption cursor did not advance");
        }
        var preview = owner.preview(tenant);
        if (!tenant.equals(preview.tenantId())) {
          throw conflict();
        }
        result.add(new Entry(tenant, preview.directoryAdditions(), preview.legacyResourceCount(), preview.baselineResourceCount()));
        after = tenant;
      }
      if (page.size() < 100) {
        return List.copyOf(result);
      }
    }
  }

  static InstallationAction prepare(TenantLegacyAdoptionMaintenance owner, List<String> tenantIds) {
    final var tenants = List.copyOf(tenantIds);
    if (tenants.size() > MAX_TENANTS || !tenants.equals(tenants.stream().distinct().sorted().toList())) {
      throw new IllegalArgumentException("Tenant adoption inventory must be bounded, unique and ordered");
    }
    tenants.forEach(TenantAdoptionActions::identity);
    var step = new InstallationPlan.Step("tenant-entitlements", "tenant-legacy-entitlements", 1,
        InstallationFingerprint.of(tenants.toArray(String[]::new)), InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    return new InstallationAction() {
      @Override
      public InstallationPlan.Step definition() {
        return step;
      }

      @Override
      public String apply() {
        var receipts = new ArrayList<String>();
        for (String tenant : tenants) {
          var receipt = owner.completed(tenant).orElseGet(() -> owner.adopt(tenant));
          receipts.add(receipt(tenant, receipt));
        }
        return InstallationFingerprint.of(receipts.toArray(String[]::new));
      }

      @Override
      public Optional<String> completedReceipt() {
        var receipts = new ArrayList<String>();
        for (String tenant : tenants) {
          var receipt = owner.completed(tenant);
          if (receipt.isEmpty()) {
            return Optional.empty();
          }
          receipts.add(receipt(tenant, receipt.orElseThrow()));
        }
        return Optional.of(InstallationFingerprint.of(receipts.toArray(String[]::new)));
      }
    };
  }

  /** Checks every unfinished tenant before the installer journal or any business fact is changed. */
  static void preflight(TenantLegacyAdoptionMaintenance owner, List<String> tenants) {
    for (String tenant : tenants) {
      var completion = owner.completed(tenant);
      if (completion.isPresent()) {
        receipt(tenant, completion.orElseThrow());
      } else if (!Objects.equals(tenant, owner.preview(tenant).tenantId())) {
        throw conflict();
      }
    }
  }

  private static String receipt(String tenant, TenantLegacyAdoption receipt) {
    if (receipt == null || !tenant.equals(receipt.tenantId()) || receipt.adoptedAt() == null
        || receipt.adoptedBy() == null || receipt.adoptedBy().isBlank()
        || receipt.resourceCount() < 0 || receipt.instanceCount() < 0) {
      throw conflict();
    }
    return InstallationFingerprint.of(tenant, receipt.adoptedAt().toString(), receipt.adoptedBy(),
        Integer.toString(receipt.resourceCount()), Integer.toString(receipt.instanceCount()));
  }

  private static void identity(String tenant) {
    if (tenant == null || tenant.isBlank() || tenant.length() > 36) {
      throw new IllegalArgumentException("Invalid tenant adoption identity");
    }
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException("INSTALLATION_TENANT_ADOPTION_RECEIPT_CONFLICT");
  }
}
