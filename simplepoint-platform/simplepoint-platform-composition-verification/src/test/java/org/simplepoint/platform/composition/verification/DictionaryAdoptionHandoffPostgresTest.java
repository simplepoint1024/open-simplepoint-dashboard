package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferAdapter;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferPort;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfers;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapRegistryWriteBoundary;
import org.simplepoint.platform.bootstrap.registry.service.impl.PlatformContributionServiceImpl;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryItemDeclaration;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoptionPort;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptionAdapter;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptions;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationStore;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;

/** Actual module stores and transactions, including a restart between independent commits. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class DictionaryAdoptionHandoffPostgresTest {
  @Test
  void retiredRegistryAndDictionaryAdoptionResumeAcrossRestartAndTargetFailure() {
    try (var registryDatabase = new MigrationPostgresDatabase();
        var identityDatabase = new MigrationPostgresDatabase("migration_identity_test")) {
      var declaration =
          new DictionaryContribution(
              "test",
              "seed",
              "1",
              300,
              List.of(
                  new DictionaryDeclaration(
                      "legacy.example",
                      "Default",
                      null,
                      10,
                      List.of(new DictionaryItemDeclaration("YES", "Yes", null, null, 10)))));
      org.junit.jupiter.api.Assertions.assertNotEquals(
          registryDatabase.jdbc().queryForObject("select current_database()", String.class),
          identityDatabase.jdbc().queryForObject("select current_database()", String.class));
      var operation = UUID.randomUUID();
      BootstrapLegacyTransfer.Command transfer;
      DictionaryLegacyAdoption.Preview dataPreview;
      try (var context = open(registryDatabase, identityDatabase)) {
        var registry = context.getBean(PlatformContributionService.class);
        registry.markRunning("common", "test", "dictionary", "seed", "1", "test:dictionary:seed:1");
        registry.markApplied("common", "test", "dictionary", "seed", "1", "test:dictionary:seed:1");
        identityDatabase
            .jdbc()
            .update(
                "insert into simpoint_saas_dictionaries(id,code,name,enabled)"
                    + " values ('legacy','legacy.example','管理员名称',false)");
        identityDatabase
            .jdbc()
            .update(
                "insert into"
                    + " simpoint_saas_dictionary_items(id,dictionary_code,item_value,name,enabled)"
                    + " values ('option','legacy.example','YES','日本語',false)");
        dataPreview = context.getBean(DictionaryLegacyAdoptionPort.class).preview(declaration);
        var source = context.getBean(BootstrapLegacyTransferPort.class);
        transfer =
            new BootstrapLegacyTransfer.Command(
                operation,
                "owner/identity/dictionary",
                source.preview(new BootstrapLegacyTransfer.Identity("test", "dictionary", "seed")));
        source.transfer(transfer);
        assertEquals(
            0L,
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_dictionary_legacy_adoptions", Long.class));
      }
      var beforeDictionaries =
          identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionaries");
      var beforeItems =
          identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionary_items");
      try (var context = open(registryDatabase, identityDatabase)) {
        var registry = context.getBean(PlatformContributionService.class);
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.shouldApply(
                    "common", "test", "dictionary", "seed", "1", "test:dictionary:seed:1"));
        var source = context.getBean(BootstrapLegacyTransferPort.class);
        var proof = source.transfer(transfer);
        var command =
            new DictionaryLegacyAdoption.Command(
                declaration,
                dataPreview,
                new DictionaryLegacyAdoption.LegacyCompletion(
                    proof.operationId(),
                    proof.targetScope(),
                    proof.identity().moduleCode(),
                    proof.identity().key(),
                    proof.version(),
                    proof.checksum(),
                    proof.fingerprint()));
        var target = context.getBean(DictionaryLegacyAdoptionPort.class);
        identityDatabase
            .jdbc()
            .execute(
                "alter table simpoint_dictionary_legacy_adoptions"
                    + " add constraint reject_handoff check (module_code <> 'test')");
        assertThrows(RuntimeException.class, () -> target.adopt(command));
        assertEquals(
            0L,
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_dictionary_registration_owners", Long.class));
        assertEquals(proof, source.transfer(transfer));
        identityDatabase
            .jdbc()
            .execute(
                "alter table simpoint_dictionary_legacy_adoptions drop constraint reject_handoff");
        var adopted = target.adopt(command);
        assertEquals(adopted, target.adopt(command));
        var installation =
            new DictionaryInstallation(
                operation,
                0,
                declaration.moduleCode(),
                ModuleClientPlan.deploymentClientId("fixture", operation),
                declaration);
        context.getBean(DictionaryRegistrationStore.class).install(List.of(installation));
        assertTrue(
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select applied from simpoint_dictionary_registration_approvals",
                    Boolean.class));
        assertEquals(
            beforeDictionaries,
            identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionaries"));
        assertEquals(
            beforeItems,
            identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionary_items"));
        assertFalse(
            registryDatabase
                .jdbc()
                .queryForObject(
                    "select exists(select 1 from pg_tables where schemaname=current_schema() and"
                        + " tablename='simpoint_saas_dictionaries')",
                    Boolean.class));
        assertFalse(
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select exists(select 1 from pg_tables where schemaname=current_schema() and"
                        + " tablename='simpoint_platform_contribution')",
                    Boolean.class));
      }
    }
  }

  @Test
  void durableInstallerReconcilesSourceCommitAndResumesFailedTargetAfterRestart() {
    try (var registryDatabase = new MigrationPostgresDatabase();
        var identityDatabase = new MigrationPostgresDatabase("migration_identity_test");
        var journalDatabase = new MigrationPostgresDatabase("migration_installation_test")) {
      var migrationPlan =
          org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations.plan("journal");
      new ModuleMigrationEngine()
          .migrate(migrationPlan, Map.of("journal", journalDatabase.source()));
      var journal =
          new org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal(
              journalDatabase.source());
      var declaration =
          new DictionaryContribution(
              "test",
              "seed",
              "1",
              300,
              List.of(
                  new DictionaryDeclaration(
                      "legacy.example",
                      "Default",
                      null,
                      10,
                      List.of(new DictionaryItemDeclaration("YES", "Yes", null, null, 10)))));
      var operation = UUID.randomUUID();
      String release =
          org.simplepoint.platform.installation.InstallationFingerprint.of("pinned-test-release");
      BootstrapLegacyTransfer.Command transfer;
      DictionaryLegacyAdoption.Preview preview;
      org.simplepoint.platform.installation.InstallationPlan pinnedPlan;
      try (var context = open(registryDatabase, identityDatabase)) {
        var registry = context.getBean(PlatformContributionService.class);
        registry.markRunning("common", "test", "dictionary", "seed", "1", "test:dictionary:seed:1");
        registry.markApplied("common", "test", "dictionary", "seed", "1", "test:dictionary:seed:1");
        identityDatabase
            .jdbc()
            .update(
                "insert into simpoint_saas_dictionaries(id,code,name,enabled)"
                    + " values ('legacy','legacy.example','Preserved administrator name',false)");
        identityDatabase
            .jdbc()
            .update(
                "insert into"
                    + " simpoint_saas_dictionary_items(id,dictionary_code,item_value,name,enabled)"
                    + " values ('option','legacy.example','YES','Preserved option',false)");
        var source = context.getBean(BootstrapLegacyTransferPort.class);
        var target = context.getBean(DictionaryLegacyAdoptionPort.class);
        transfer =
            new BootstrapLegacyTransfer.Command(
                operation,
                "owner/identity/dictionary",
                source.preview(new BootstrapLegacyTransfer.Identity("test", "dictionary", "seed")));
        preview = target.preview(declaration);
        var prepared =
            new org.simplepoint.platform.installation.dictionary.DictionaryAdoptionInstallation(
                    source, target)
                .prepare("dictionary-upgrade", 0, release, declaration, preview, transfer);
        pinnedPlan = prepared.plan();
        journal.open(pinnedPlan);
        journal.claim(pinnedPlan, 0);
        // Source commits, but the installer loses its journal acknowledgement before restart.
        try {
          prepared.actions().getFirst().apply();
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
        assertEquals(
            org.simplepoint.platform.installation.InstallationJournal.State.RUNNING,
            journal.read(pinnedPlan).steps().getFirst().state());
      }
      var beforeDictionaries =
          identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionaries");
      var beforeItems =
          identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionary_items");
      try (var context = open(registryDatabase, identityDatabase)) {
        var prepared =
            new org.simplepoint.platform.installation.dictionary.DictionaryAdoptionInstallation(
                    context.getBean(BootstrapLegacyTransferPort.class),
                    context.getBean(DictionaryLegacyAdoptionPort.class))
                .prepare("dictionary-upgrade", 0, release, declaration, preview, transfer);
        assertEquals(pinnedPlan.fingerprint(), prepared.plan().fingerprint());
        var runner =
            new org.simplepoint.platform.installation.InstallationRunner(
                new org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal(
                    journalDatabase.source()));
        identityDatabase
            .jdbc()
            .execute(
                "alter table simpoint_dictionary_legacy_adoptions"
                    + " add constraint reject_installer check (module_code <> 'test')");
        assertEquals(
            org.simplepoint.platform.installation.InstallationException.Code.ACTION_FAILED,
            assertThrows(
                    org.simplepoint.platform.installation.InstallationException.class,
                    () -> runner.run(prepared.plan(), prepared.actions()))
                .code());
        assertEquals(
            org.simplepoint.platform.installation.InstallationJournal.State.COMPLETE,
            journal.read(pinnedPlan).steps().getFirst().state());
        assertFalse(journal.read(pinnedPlan).complete());
        assertEquals(
            0L,
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_dictionary_registration_owners", Long.class));
        identityDatabase
            .jdbc()
            .execute(
                "alter table simpoint_dictionary_legacy_adoptions drop constraint"
                    + " reject_installer");
        assertTrue(runner.run(prepared.plan(), prepared.actions()).complete());
        assertTrue(runner.run(prepared.plan(), prepared.actions()).complete());
        assertEquals(
            1L,
            journalDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_installation_attempts where step_index=0",
                    Long.class));
        assertEquals(
            2L,
            journalDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_installation_attempts where step_index=1",
                    Long.class));
        assertEquals(
            0L,
            identityDatabase
                .jdbc()
                .queryForObject(
                    "select count(*) from simpoint_dictionary_registration_approvals", Long.class));
        assertEquals(
            beforeDictionaries,
            identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionaries"));
        assertEquals(
            beforeItems,
            identityDatabase.jdbc().queryForList("select * from simpoint_saas_dictionary_items"));
        assertEquals(
            3,
            java.util.Set.of(
                    registryDatabase
                        .jdbc()
                        .queryForObject("select current_database()", String.class),
                    identityDatabase
                        .jdbc()
                        .queryForObject("select current_database()", String.class),
                    journalDatabase
                        .jdbc()
                        .queryForObject("select current_database()", String.class))
                .size());
      }
    }
  }

  private AnnotationConfigApplicationContext open(
      MigrationPostgresDatabase registry, MigrationPostgresDatabase identity) {
    var context = new AnnotationConfigApplicationContext();
    try {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "stores",
                  Map.of(
                      "simplepoint.persistence.modules.identity", "identity",
                      "simplepoint.persistence.modules.bootstrap-registry", "registry",
                      "simplepoint.persistence.sources.identity", "identitySource",
                      "simplepoint.persistence.sources.registry", "registrySource")));
      context.registerBean("identitySource", javax.sql.DataSource.class, identity::source);
      context.registerBean("registrySource", javax.sql.DataSource.class, registry::source);
      var modules =
          ModuleCatalog.discover(getClass().getClassLoader())
              .plan(List.of("identity", "bootstrap-registry"));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var migrations = ModuleMigrationPlan.discover(modules, persistence.bindings());
      new ModuleMigrationEngine()
          .migrate(
              migrations, Map.of("identity", identity.source(), "registry", registry.source()));
      context.registerBean(ModulePlan.class, () -> modules);
      context.register(
          Persistence.class,
          PlatformContributionServiceImpl.class,
          BootstrapRegistryWriteBoundary.class,
          BootstrapLegacyTransfers.class,
          BootstrapLegacyTransferAdapter.class,
          DictionaryCatalogWriteBoundary.class,
          DictionaryLegacyAdoptions.class,
          DictionaryLegacyAdoptionAdapter.class,
          DictionaryRegistrationStore.class);
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
