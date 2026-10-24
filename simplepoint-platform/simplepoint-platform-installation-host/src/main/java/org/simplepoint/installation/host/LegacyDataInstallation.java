package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferAdapter;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfers;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapRegistryWriteBoundary;
import org.simplepoint.platform.composition.ModuleDictionaryPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.dictionary.DictionaryAdoptionInstallation;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptionAdapter;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptions;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Offline legacy transfer through domain ports; the reviewed plan never contains business values. */
final class LegacyDataInstallation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private LegacyDataInstallation() {}

  static Map<String, Object> execute(boolean preview, ModulePlan modules, ModulePersistencePlan persistence,
      Properties properties, ConfigurableEnvironment environment, Map<String, DataSource> sources,
      String artifact) throws Exception {
    UUID operation = InstallationOperation.stage(properties, "legacy-data-adoption");
    String target = InstallationMain.required(properties, "installation.legacy.target");
    long generation = Long.parseLong(InstallationMain.required(properties, "installation.legacy.expected-generation"));
    Path reviewPath = Path.of(InstallationMain.required(properties, "installation.legacy.review-file"));
    String storage = InstallationStorage.fingerprint(sources);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(environment);
      context.registerBean(ModulePlan.class, () -> modules);
      for (var store : persistence.stores().values()) {
        context.registerBean(store.binding().dataSource(), DataSource.class, () -> sources.get(store.binding().store()));
      }
      context.register(InstallationAuthorities.Persistence.class, BootstrapRegistryWriteBoundary.class,
          BootstrapLegacyTransfers.class, BootstrapLegacyTransferAdapter.class,
          DictionaryCatalogWriteBoundary.class, DictionaryLegacyAdoptions.class, DictionaryLegacyAdoptionAdapter.class);
      context.refresh();
      var registry = context.getBean(BootstrapLegacyTransferAdapter.class);
      var dictionaries = context.getBean(DictionaryLegacyAdoptionAdapter.class);
      var declarations = ModuleDictionaryPlan.declarations(modules);
      final Review review;
      if (preview) {
        var targets = JSON.readValue(InstallationFiles.read(Path.of(InstallationMain.required(properties,
            "installation.legacy.targets-file")), 1048576), BootstrapTarget[].class);
        if (targets.length > 256) {
          throw new IllegalArgumentException("Too many legacy bootstrap targets");
        }
        var commands = new ArrayList<BootstrapLegacyTransfer.Command>();
        for (var input : targets) {
          var command = new BootstrapLegacyTransfer.Command(operation, "module/" + input.targetModule(),
              registry.preview(input.identity()));
          BootstrapAdoptionInstallation.prepare(modules, registry, command);
          commands.add(command);
        }
        var dictionaryReviews = new TreeMap<String, DictionaryLegacyAdoption.Preview>();
        for (var declaration : declarations.values()) {
          commands.add(new BootstrapLegacyTransfer.Command(operation, "owner/identity/dictionary",
              registry.preview(new BootstrapLegacyTransfer.Identity(declaration.moduleCode(),
                  "dictionary", declaration.contributionKey()))));
          dictionaryReviews.put(declaration.moduleCode(), dictionaries.preview(declaration));
        }
        review = new Review(operation, target, generation, artifact, storage, commands, dictionaryReviews);
      } else {
        review = JSON.readValue(InstallationFiles.read(reviewPath, 4194304), Review.class);
      }
      if (!review.operation().equals(operation) || !review.target().equals(target)
          || review.generation() != generation || !review.artifact().equals(artifact) || !review.storage().equals(storage)
          || !review.dictionaries().keySet().equals(declarations.keySet())) {
        throw new IllegalArgumentException("Legacy review does not match this deployment or artifact");
      }
      var actions = new ArrayList<InstallationAction>();
      var seenDictionaries = new java.util.HashSet<String>();
      var seenSources = new java.util.HashSet<BootstrapLegacyTransfer.Identity>();
      for (var command : review.transfers()) {
        if (!operation.equals(command.operationId()) || !seenSources.add(command.expected().identity())) {
          throw new IllegalArgumentException("Legacy transfer operation mismatch or duplicate source");
        }
        if (command.targetScope().startsWith("module/")) {
          actions.add(BootstrapAdoptionInstallation.prepare(modules, registry, command));
        } else {
          String module = command.expected().identity().moduleCode();
          var declaration = declarations.get(module);
          if (declaration == null || !seenDictionaries.add(module)) {
            throw new IllegalArgumentException("Unexpected or duplicate legacy dictionary transfer");
          }
          actions.addAll(new DictionaryAdoptionInstallation(registry, dictionaries).prepare(target,
              generation, artifact, declaration, review.dictionaries().get(module), command).actions());
        }
      }
      if (!seenDictionaries.equals(declarations.keySet())) {
        throw new IllegalArgumentException("Incomplete legacy dictionary review");
      }
      var scoped = InstallationStorage.bind(actions, storage);
      var plan = new InstallationPlan(operation, target, generation, artifact,
          scoped.stream().map(InstallationAction::definition).toList());
      if (preview) {
        InstallationFiles.create(reviewPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
      } else {
        String approved = InstallationMain.required(properties, "installation.legacy.review-fingerprint");
        if (!plan.fingerprint().equals(approved)) {
          throw new IllegalArgumentException("Legacy installation plan differs from the reviewed fingerprint");
        }
        String journalStore = InstallationMain.required(properties, "installation.journal.store");
        DataSource source = sources.get(journalStore);
        if (source == null) {
          throw new IllegalArgumentException("Missing installation journal store");
        }
        new ModuleMigrationEngine().migrate(InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, source));
        var result = new InstallationRunner(new JdbcInstallationJournal(source)).run(plan, scoped);
        if (!result.complete()) {
          throw new IllegalStateException("Legacy installation is incomplete");
        }
      }
      return Map.of("reviewFingerprint", plan.fingerprint(), "steps", actions.size(),
          "transfers", review.transfers().size(), "dictionaries", declarations.size(), "applied", !preview);
    }
  }

  record BootstrapTarget(String targetModule, BootstrapLegacyTransfer.Identity identity) {}

  record Review(UUID operation, String target, long generation, String artifact, String storage,
      List<BootstrapLegacyTransfer.Command> transfers, Map<String, DictionaryLegacyAdoption.Preview> dictionaries) {
    Review {
      transfers = List.copyOf(transfers);
      dictionaries = Map.copyOf(dictionaries);
    }
  }
}
