package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantLegacyAdoptionStore;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantLegacyInstallationAdapter;
import org.simplepoint.plugin.rbac.tenant.service.impl.ResourceAuthorizationVersionServiceImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Explicit offline executor; starts selected persistence only, with no HTTP, authentication or business workers. */
final class TenantAdoptionInstallation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private TenantAdoptionInstallation() {}

  static Map<String, Object> execute(boolean preview, ModulePlan modules, ModulePersistencePlan persistence,
      Properties properties, ConfigurableEnvironment environment, Map<String, DataSource> sources, String artifact) throws Exception {
    if (modules.modules().stream().noneMatch(module -> module.id().equals("identity"))) {
      throw new IllegalArgumentException("Tenant adoption requires the selected identity module");
    }
    UUID operation = InstallationOperation.stage(properties, "tenant-entitlement-adoption");
    String target = InstallationMain.required(properties, "installation.tenants.target");
    long generation = Long.parseLong(InstallationMain.required(properties, "installation.tenants.expected-generation"));
    Path reviewPath = Path.of(InstallationMain.required(properties, "installation.tenants.review-file"));
    String storage = InstallationStorage.fingerprint(sources);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(environment);
      context.registerBean(ModulePlan.class, () -> modules);
      for (var store : persistence.stores().values()) {
        context.registerBean(store.binding().dataSource(), DataSource.class, () -> sources.get(store.binding().store()));
      }
      context.register(InstallationAuthorities.Persistence.class, ResourceAuthorizationVersionServiceImpl.class, TenantLegacyAdoptionStore.class);
      context.refresh();
      var owner = new TenantLegacyInstallationAdapter(context.getBean(TenantLegacyAdoptionStore.class));
      Review review = preview ? new Review(operation, target, generation, artifact, storage, TenantAdoptionActions.inventory(owner))
          : JSON.readValue(InstallationFiles.read(reviewPath, 4194304), Review.class);
      if (!operation.equals(review.operation()) || !target.equals(review.target()) || generation != review.generation()
          || !artifact.equals(review.artifact()) || !storage.equals(review.storage())) {
        throw new IllegalArgumentException("Tenant adoption review does not match the installation");
      }
      var tenants = review.tenants().stream().map(TenantAdoptionActions.Entry::tenantId).toList();
      var actions = InstallationStorage.bind(List.of(TenantAdoptionActions.prepare(owner, tenants)), storage);
      var plan = new InstallationPlan(operation, target, generation, artifact,
          actions.stream().map(action -> action.definition()).toList());
      if (preview) {
        InstallationFiles.create(reviewPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
      } else {
        if (!plan.fingerprint().equals(InstallationMain.required(properties, "installation.tenants.review-fingerprint"))) {
          throw new IllegalArgumentException("Tenant adoption target list differs from the reviewed installation");
        }
        TenantAdoptionActions.preflight(owner, tenants);
        String journalStore = InstallationMain.required(properties, "installation.journal.store");
        DataSource source = sources.get(journalStore);
        if (source == null) {
          throw new IllegalArgumentException("Missing installation journal store");
        }
        new ModuleMigrationEngine().migrate(InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, source));
        var result = new InstallationRunner(new JdbcInstallationJournal(source)).run(plan, actions);
        if (!result.complete()) {
          throw new IllegalStateException("Tenant adoption is incomplete");
        }
        String receipt = actions.getFirst().completedReceipt()
            .orElseThrow(() -> new IllegalStateException("Tenant adoption is incomplete"));
        if (!receipt.equals(result.steps().getFirst().receiptFingerprint())) {
          throw new IllegalStateException("Tenant adoption receipt differs from its durable installation completion");
        }
      }
      return Map.of("operation", operation.toString(), "reviewFingerprint", plan.fingerprint(), "tenants", tenants.size(),
          "applied", !preview, "pendingTenantsRemain", !owner.pending(null, 1).isEmpty());
    }
  }

  record Review(UUID operation, String target, long generation, String artifact, String storage,
      List<TenantAdoptionActions.Entry> tenants) {
    Review {
      tenants = List.copyOf(tenants);
    }
  }
}
