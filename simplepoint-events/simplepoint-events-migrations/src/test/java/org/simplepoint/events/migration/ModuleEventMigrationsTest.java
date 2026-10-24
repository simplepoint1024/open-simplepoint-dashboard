package org.simplepoint.events.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ModuleEventMigrationsTest {
  private final ModuleCatalog catalog =
      new ModuleCatalog(List.of(One.class, Two.class, None.class));

  @Test
  void serviceDiscoveryContributesOncePerSelectedEventStoreWithStableHistoryIdentity() {
    var together = plan(List.of("one", "two", "none"), false);
    assertThat(together.undeclaredModules()).isEmpty();
    assertThat(together.schemas()).hasSize(1);
    assertThat(together.schemas().getFirst().module()).isEqualTo(ModuleEventMigrations.OWNER);
    var split = plan(List.of("one", "two", "none"), true);
    assertThat(split.schemas())
        .extracting(ModuleMigrationPlan.ModuleSchema::store)
        .containsExactly("default", "other");
    assertThat(split.schemas())
        .extracting(ModuleMigrationPlan.ModuleSchema::historyTable)
        .containsOnly(together.schemas().getFirst().historyTable());
    assertThat(plan(List.of("none"), false).schemas()).isEmpty();
    assertThat(plan(List.of("one"), false).schemas())
        .containsExactly(together.schemas().getFirst());
  }

  @Test
  void freshLedgerInstallsOnceAndRetainsRowsWhenAnotherOwnerJoinsTheStore() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var engine = new ModuleMigrationEngine();
    var sources = Map.of("default", (javax.sql.DataSource) source);
    assertThat(engine.migrate(plan(List.of("one"), false), sources).getFirst().applied())
        .isEqualTo(1);
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "insert into sp_event_quarantine_v1 "
            + "(id,owner,subscription,body_base64,original_bytes,created_at) values "
            + "('fixture','legacy-one','one.receive','e30=',2,1)");
    assertThat(engine.migrate(plan(List.of("two"), false), sources).getFirst().applied()).isZero();
    engine.verify(plan(List.of("one", "two"), false), sources);
    assertThat(jdbc.queryForObject("select owner from sp_event_quarantine_v1", String.class))
        .isEqualTo("legacy-one");
  }

  @Test
  void legacyLedgerRequiresExplicitAdoptionBeforeAnyMigrationHistoryIsCreated() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("create table sp_event_jobs_v1 (id varchar(36) primary key)");
    assertThatThrownBy(
            () ->
                new ModuleMigrationEngine()
                    .migrate(plan(List.of("one"), false), Map.of("default", source)))
        .hasMessageContaining("LEGACY_SCHEMA_ADOPTION_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                    + "where lower(table_name) like 'sp_migration_%'",
                Integer.class))
        .isZero();
  }

  @Test
  void versionOneMatchesTheDeployedPortableLedgerAsset() throws Exception {
    String legacy =
        new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    var schema = plan(List.of("one"), false).schemas().getFirst();
    assertThat(schema.tables())
        .containsExactly("sp_event_jobs_v1", "sp_event_replays_v1", "sp_event_quarantine_v1");
    for (String dialect : List.of("h2", "postgresql")) {
      assertThat(schema.scripts().get(dialect)).hasSize(1);
      assertThat(schema.scripts().get(dialect).getFirst().sql()).isEqualTo(legacy);
    }
  }

  private ModuleMigrationPlan plan(List<String> selected, boolean split) {
    var primary = binding("default");
    var bindings =
        new ModuleStorageBindings(
            Map.of(
                "one",
                primary,
                "two",
                split ? binding("other") : primary,
                "none",
                binding("unused")));
    return ModuleMigrationPlan.discover(catalog.plan(selected), bindings);
  }

  private static ModuleStorageBindings.Binding binding(String store) {
    return new ModuleStorageBindings.Binding(
        store, store + "Source", store + "Emf", store + "Em", store + "Tx", store + "Jdbc");
  }

  /** Metadata fixture; ledger ownership does not depend on producer identity or subscriptions. */
  public static class Manifest implements EventDeclarations {
    @Override
    public String identity() {
      return "fixture";
    }

    @Override
    public List<EventPublication> publications() {
      return List.of(new EventPublication("fixture.event", 1));
    }

    @Override
    public List<EventSubscription> subscriptions() {
      return List.of();
    }
  }

  /** A distinct producer for a separately selected module. */
  public static final class SecondManifest extends Manifest {
    @Override
    public String identity() {
      return "fixture-two";
    }
  }

  @PlatformModule(id = "one", packages = "fixture.migrations.one")
  @ModuleMigrations(ownsSchema = false)
  @ModuleEvents(Manifest.class)
  static class One {}

  @PlatformModule(id = "two", packages = "fixture.migrations.two")
  @ModuleMigrations(ownsSchema = false)
  @ModuleEvents(SecondManifest.class)
  static class Two {}

  @PlatformModule(id = "none", packages = "fixture.migrations.none")
  @ModuleMigrations(ownsSchema = false)
  static class None {}
}
