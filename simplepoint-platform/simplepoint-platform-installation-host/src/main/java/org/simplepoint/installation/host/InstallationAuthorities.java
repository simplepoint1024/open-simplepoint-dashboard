package org.simplepoint.installation.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.composition.ModuleDictionaryPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleResourcePlan;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.plugin.oidc.service.installation.ServiceClientInstallation;
import org.simplepoint.plugin.oidc.service.repository.impl.RegisteredClientRepositoryImpl;
import org.simplepoint.plugin.rbac.resource.api.deployment.ResourceDeploymentChange;
import org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationStore;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit privileged executor binding; no domain scanning, runtime initializers or workers. */
final class InstallationAuthorities {
  private InstallationAuthorities() {}

  static Map<String, Object> provision(boolean preview, ModulePlan modules, ModulePersistencePlan persistence,
      Properties properties, ConfigurableEnvironment environment, Map<String, DataSource> sources,
      String artifact) throws Exception {
    UUID operation = UUID.fromString(InstallationMain.required(properties, "installation.operation"));
    var clients = new TreeMap<String, String>();
    ModuleClientPlan.identities(modules, Map.of()).forEach((module, base) ->
        clients.put(module, ModuleClientPlan.deploymentClientId(base, operation)));
    var credentials = new ArrayList<ServiceClientInstallation.Credential>();
    for (var module : modules.modules()) {
      if (!module.consumes().isEmpty()) {
        credentials.add(new ServiceClientInstallation.Credential(operation, module.id(), clients.get(module.id()),
            InstallationMain.required(properties, "simplepoint.modules.credentials." + module.id() + ".client-secret")));
      }
    }
    var resources = new ArrayList<ResourceDeploymentChange>();
    ModuleResourcePlan.owners(modules).forEach((module, owner) -> resources.add(new ResourceDeploymentChange(
        owner, Long.parseLong(InstallationMain.required(properties, "installation.resources." + owner + ".expected-generation")),
        operation, clients.get(module))));
    var dictionaries = new ArrayList<DictionaryInstallation>();
    ModuleDictionaryPlan.declarations(modules).forEach((module, declaration) -> dictionaries.add(new DictionaryInstallation(
        operation, Long.parseLong(InstallationMain.required(properties, "installation.dictionaries." + module + ".expected-generation")),
        module, clients.get(module), declaration)));
    credentials.sort(java.util.Comparator.comparing(ServiceClientInstallation.Credential::module));
    resources.sort(java.util.Comparator.comparing(ResourceDeploymentChange::owner));
    dictionaries.sort(java.util.Comparator.comparing(DictionaryInstallation::moduleCode));

    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(environment);
      context.registerBean(ModulePlan.class, () -> modules);
      for (var store : persistence.stores().values()) {
        context.registerBean(store.binding().dataSource(), DataSource.class, () -> sources.get(store.binding().store()));
      }
      context.registerBean(PasswordEncoder.class, () -> new BCryptPasswordEncoder());
      context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> new com.fasterxml.jackson.databind.ObjectMapper());
      context.register(Persistence.class, RegisteredClientRepositoryImpl.class, ServiceClientInstallation.class,
          ResourceCatalogWriteBoundary.class, ResourceRegistrationProperties.class, ResourceRegistrationCallerPolicy.class,
          ResourceRegistrationDeployments.class, DictionaryCatalogWriteBoundary.class, DictionaryRegistrationStore.class);
      context.refresh();
      var clientInstallation = context.getBean(ServiceClientInstallation.class);
      var resourceInstallation = context.getBean(ResourceRegistrationDeployments.class);
      var dictionaryInstallation = context.getBean(DictionaryRegistrationStore.class);
      var actions = new ArrayList<InstallationAction>();
      if (!credentials.isEmpty()) {
        var inputs = new ArrayList<String>();
        for (var credential : credentials) {
          inputs.add(credential.operation().toString());
          inputs.add(credential.module());
          inputs.add(credential.clientId());
          inputs.add(credential.secret());
        }
        actions.add(new VerifiedInstallationAction("module-clients", InstallationFingerprint.of(inputs.toArray(String[]::new)),
            () -> clientInstallation.provision(credentials), () -> clientInstallation.installed(credentials)));
      }
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      if (!resources.isEmpty()) {
        actions.add(new VerifiedInstallationAction("resource-authorities",
            InstallationFingerprint.of(json.writeValueAsString(resources)),
            () -> resourceInstallation.apply(resources), () -> resourceInstallation.installed(resources)));
      }
      if (!dictionaries.isEmpty()) {
        actions.add(new VerifiedInstallationAction("dictionary-authorities",
            InstallationFingerprint.of(json.writeValueAsString(dictionaries)),
            () -> dictionaryInstallation.install(dictionaries), () -> dictionaryInstallation.installed(dictionaries)));
      }
      // Inspect all committed effects before running any step. A conflicting old operation never becomes a retry.
      for (var action : actions) {
        action.completedReceipt();
      }
      if (!resources.isEmpty() && !resourceInstallation.installed(resources)) {
        for (var command : resources) {
          if (resourceInstallation.current(command.owner()).map(state -> state.generation()).orElse(0L)
              != command.expectedGeneration()) {
            throw new IllegalStateException("Resource authority changed before installation");
          }
        }
      }
      if (!dictionaries.isEmpty() && !dictionaryInstallation.installed(dictionaries)) {
        for (var command : dictionaries) {
          if (dictionaryInstallation.generation(command.moduleCode()) != command.expectedGeneration()) {
            throw new IllegalStateException("Dictionary authority changed before installation");
          }
        }
      }
      var scoped = InstallationStorage.bind(actions, InstallationStorage.fingerprint(sources));
      var plan = new InstallationPlan(InstallationOperation.stage(properties, "authorities"),
          InstallationMain.required(properties, "installation.authorities.target"),
          Long.parseLong(InstallationMain.required(properties, "installation.authorities.expected-generation")),
          artifact, scoped.stream().map(InstallationAction::definition).toList());
      if (!preview) {
        if (!plan.fingerprint().equals(InstallationMain.required(properties, "installation.authorities.review-fingerprint"))) {
          throw new IllegalArgumentException("Authority installation differs from the reviewed fingerprint");
        }
        String journalStore = InstallationMain.required(properties, "installation.journal.store");
        DataSource source = sources.get(journalStore);
        if (source == null) {
          throw new IllegalArgumentException("Missing installation journal store");
        }
        new ModuleMigrationEngine().migrate(InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, source));
        if (!new InstallationRunner(new JdbcInstallationJournal(source)).run(plan, scoped).complete()) {
          throw new IllegalStateException("Authority installation is incomplete");
        }
        for (var action : scoped) {
          if (action.completedReceipt().isEmpty()) {
            throw new IllegalStateException("Installed authority is no longer present");
          }
        }
      }
      return Map.of("operation", operation.toString(), "moduleClients", clients,
          "provisionedClients", credentials.size(), "resourceAuthorities", resources.size(),
          "dictionaryAuthorities", dictionaries.size(), "reviewFingerprint", plan.fingerprint(), "applied", !preview);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
