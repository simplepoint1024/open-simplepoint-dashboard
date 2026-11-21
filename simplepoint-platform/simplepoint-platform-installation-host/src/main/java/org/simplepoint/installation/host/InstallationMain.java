package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Trusted offline installation executable; never exposed as a business HTTP/RPC endpoint. */
public final class InstallationMain {
  private InstallationMain() {}

  /** Uses a private local configuration and reports only non-secret results. */
  public static void main(String[] args) throws Exception {
    if (args.length != 2
        || !java.util.Set.of(
                "plan",
                "migrate",
                "verify",
                "provision",
                "provision-review",
                "broker",
                "broker-review",
                "legacy-review",
                "legacy-adopt",
                "schema-review",
                "schema-adopt",
                "tenants-review",
                "tenants-adopt",
                "ownership-review",
                "ownership-install",
                "ownership-verify",
                "ownership-inventory-review",
                "ownership-inventory-verify",
                "ownership-target-review",
                "ownership-target-verify",
                "ownership-schema-review",
                "ownership-schema-verify",
                "ownership-adoption-review",
                "ownership-adopt",
                "ownership-adoption-verify",
                "ownership-cleanup-review",
                "ownership-cleanup-adopt",
                "ownership-cleanup-verify")
            .contains(args[0])) {
      throw new IllegalArgumentException(
          "Usage:"
              + " plan|migrate|verify|provision-review|provision|broker-review|broker"
              + "|legacy-review|legacy-adopt"
              + "|schema-review|schema-adopt|tenants-review|tenants-adopt"
              + "|ownership-review|ownership-install|ownership-verify"
              + "|ownership-inventory-review|ownership-inventory-verify"
              + "|ownership-target-review|ownership-target-verify"
              + "|ownership-schema-review|ownership-schema-verify"
              + "|ownership-adoption-review|ownership-adopt|ownership-adoption-verify"
              + "|ownership-cleanup-review|ownership-cleanup-adopt|ownership-cleanup-verify"
              + " /private/installation.properties");
    }
    var properties = readPrivateProperties(Path.of(args[1]));
    var environment = new StandardEnvironment();
    var values = new TreeMap<String, Object>();
    properties.forEach((key, value) -> values.put(key.toString(), value));
    environment.getPropertySources().addFirst(new MapPropertySource("installation", values));
    ModulePlan modules =
        ModuleCatalog.discover(InstallationMain.class.getClassLoader())
            .plan(
                Arrays.stream(required(properties, "simplepoint.modules.active").split(","))
                    .map(String::trim)
                    .toList());
    var persistence =
        ModulePersistencePlan.discover(modules, environment, new DefaultResourceLoader());
    final var migrations = ModuleMigrationPlan.discover(modules, persistence.bindings());
    var summary = new TreeMap<String, Object>();
    String artifact = InstallationArtifact.fingerprint();
    summary.put("releaseFingerprint", artifact);
    summary.put("modules", modules.modules().stream().map(module -> module.id()).toList());
    summary.put(
        "resourceOwners", org.simplepoint.platform.composition.ModuleResourcePlan.owners(modules));
    summary.put(
        "dictionaryModules",
        org.simplepoint.platform.composition.ModuleDictionaryPlan.declarations(modules).keySet());
    var consumed = new TreeMap<String, Object>();
    modules.modules().forEach(module -> consumed.put(module.id(), module.consumes().keySet()));
    summary.put("consumedContracts", consumed);
    summary.put(
        "events", org.simplepoint.events.composition.ModuleEventPlan.from(modules).channels());
    summary.put("schemaContributions", migrations.schemas().size());
    summary.put(
        "ownedTables",
        migrations.schemas().stream().mapToInt(schema -> schema.tables().size()).sum());
    summary.put(
        "schemas",
        migrations.schemas().stream()
            .map(
                schema ->
                    Map.of(
                        "module",
                        schema.module(),
                        "store",
                        schema.store(),
                        "historyTable",
                        schema.historyTable(),
                        "tables",
                        schema.tables(),
                        "scripts",
                        schema.scripts().entrySet().stream()
                            .collect(
                                java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry ->
                                        entry.getValue().stream()
                                            .map(
                                                script ->
                                                    Map.of(
                                                        "name",
                                                        script.name(),
                                                        "sha256",
                                                        script.sha256()))
                                            .toList()))))
            .toList());
    if (!"plan".equals(args[0])) {
      var sources = new TreeMap<String, javax.sql.DataSource>();
      for (String store : persistence.stores().keySet()) {
        String prefix = "installation.stores." + store + ".";
        sources.put(
            store,
            new DriverManagerDataSource(
                required(properties, prefix + "url"),
                required(properties, prefix + "username"),
                required(properties, prefix + "password")));
      }
      var engine = new ModuleMigrationEngine();
      if (args[0].startsWith("schema-")) {
        summary.put(
            "schemaAdoption",
            SchemaAdoptionInstallation.execute(
                "schema-review".equals(args[0]), migrations, sources, properties, artifact));
        System.out.println(new ObjectMapper().writeValueAsString(summary));
        return;
      }
      if ("migrate".equals(args[0])) {
        engine.migrate(migrations, sources);
      }
      engine.verify(migrations, sources);
      summary.put("schemaVerified", true);
      if (args[0].startsWith("ownership-cleanup-")) {
        summary.put(
            "retiredCleanup",
            DnaRetiredCleanupInstallation.execute(
                args[0], modules, persistence.bindings(), properties, sources, artifact));
      } else if (args[0].startsWith("ownership-inventory-")) {
        summary.put(
            "physicalBindingInventory",
            DnaOwnershipInventoryInstallation.execute(
                "ownership-inventory-review".equals(args[0]),
                modules,
                persistence.bindings(),
                properties,
                sources,
                artifact));
      } else if (args[0].startsWith("ownership-target-")) {
        summary.put(
            "physicalBindingTargets",
            DnaOwnershipTargetInstallation.execute(
                "ownership-target-review".equals(args[0]),
                modules,
                persistence.bindings(),
                properties,
                sources,
                artifact));
      } else if (args[0].startsWith("ownership-schema-")) {
        summary.put(
            "physicalOwnershipSchema",
            DnaOwnershipSchemaInstallation.execute(
                "ownership-schema-review".equals(args[0]),
                modules,
                persistence.bindings(),
                properties,
                sources,
                artifact));
      } else if (args[0].startsWith("ownership-adoption-") || args[0].equals("ownership-adopt")) {
        summary.put(
            "physicalOwnershipAdoption",
            DnaOwnershipAdoptionInstallation.execute(
                args[0], modules, persistence.bindings(), properties, sources, artifact));
      } else if (args[0].startsWith("ownership-")) {
        summary.put(
            "physicalOwnership",
            PhysicalOwnershipInstallation.execute(args[0], sources, properties, artifact));
      }
      if (args[0].startsWith("legacy-")) {
        summary.put(
            "legacy",
            LegacyDataInstallation.execute(
                "legacy-review".equals(args[0]),
                modules,
                persistence,
                properties,
                environment,
                sources,
                artifact));
      }
      if (args[0].startsWith("tenants-")) {
        summary.put(
            "tenantAdoption",
            TenantAdoptionInstallation.execute(
                "tenants-review".equals(args[0]),
                modules,
                persistence,
                properties,
                environment,
                sources,
                artifact));
      }
      if ("broker".equals(args[0]) || "broker-review".equals(args[0])) {
        summary.put(
            "broker",
            BrokerInstallationExecution.execute(
                "broker-review".equals(args[0]),
                org.simplepoint.events.composition.ModuleEventPlan.from(modules),
                properties,
                sources,
                artifact));
      }
      if ("provision".equals(args[0]) || "provision-review".equals(args[0])) {
        summary.put(
            "authorities",
            InstallationAuthorities.provision(
                "provision-review".equals(args[0]),
                modules,
                persistence,
                properties,
                environment,
                sources,
                artifact));
      }
    }
    System.out.println(new ObjectMapper().writeValueAsString(summary));
  }

  private static Properties readPrivateProperties(Path path) throws Exception {
    var properties = new Properties();
    try (var input =
        new java.io.StringReader(
            new String(
                InstallationFiles.read(path, 1048576), java.nio.charset.StandardCharsets.UTF_8))) {
      properties.load(input);
    }
    return properties;
  }

  static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing installation configuration: " + key);
    }
    return value;
  }
}
