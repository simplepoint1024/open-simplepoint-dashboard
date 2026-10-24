package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the client constraint upgrade without rewriting the already versioned baseline. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class OidcClientMigrationPostgresTest {
  @Test
  void upgradesExistingClientsAndLeavesOtherSchemaAndClientIdentityConstraintsIntact() {
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("oidc"));
    var persistence =
        ModulePersistencePlan.discover(
            modules, new StandardEnvironment(), new DefaultResourceLoader());
    var discovered = ModuleMigrationPlan.discover(modules, persistence.bindings());
    var schema = discovered.schemas().getFirst();
    // This test isolates V1 -> V2; newer binding migrations have separate upgrade coverage.
    var latest =
        new ModuleMigrationPlan(
            List.of(
                new ModuleSchema(
                    schema.module(),
                    schema.store(),
                    schema.historyTable(),
                    schema.tables(),
                    Map.of("postgresql", schema.scripts().get("postgresql").subList(0, 2)))));
    var original =
        new ModuleMigrationPlan(
            List.of(
                new ModuleSchema(
                    schema.module(),
                    schema.store(),
                    schema.historyTable(),
                    schema.tables(),
                    Map.of("postgresql", List.of(schema.scripts().get("postgresql").getFirst())))));
    var engine = new ModuleMigrationEngine();
    try (var upgraded = new MigrationPostgresDatabase();
        var untouched = new MigrationPostgresDatabase()) {
      engine.migrate(original, Map.of("default", upgraded.source()));
      engine.migrate(original, Map.of("default", untouched.source()));
      var jdbc = upgraded.jdbc();
      insertClient(jdbc, "original", "original", "2026-01-01T00:00:00Z", "encoded-secret");
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertClient(jdbc, "same-time", "same-time", "2026-01-01T00:00:00Z", "different"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertClient(jdbc, "public", "public", "2026-01-02T00:00:00Z", null));
      jdbc.update(
          "insert into simpoint_ac_oauth2_authorization"
              + " (id,registered_client_id,principal_name,authorization_grant_type,access_token_value)"
              + " values"
              + " ('authorization','original','alice','authorization_code','existing-token')");
      jdbc.update(
          "insert into simpoint_ac_oauth2_consent"
              + " (registered_client_id,principal_name,authorities)"
              + " values ('original','alice','SCOPE_openid')");
      final var untouchedContract = untouched.physicalContract("simpoint_ac_oauth2_%");
      assertEquals(
          1, engine.migrate(latest, Map.of("default", upgraded.source())).getFirst().applied());
      insertClient(jdbc, "second", "second", "2026-01-01T00:00:00Z", "encoded-secret");
      insertClient(jdbc, "public", "public", "2026-01-01T00:00:00Z", null);
      assertThrows(
          DataIntegrityViolationException.class,
          () -> insertClient(jdbc, "duplicate", "original", "2026-01-03T00:00:00Z", null));
      assertEquals(
          "existing-token",
          jdbc.queryForObject(
              "select access_token_value from simpoint_ac_oauth2_authorization", String.class));
      assertEquals(
          "SCOPE_openid",
          jdbc.queryForObject("select authorities from simpoint_ac_oauth2_consent", String.class));
      assertEquals(
          "original",
          jdbc.queryForObject(
              "select registered_client_id from simpoint_ac_oauth2_authorization", String.class));
      assertEquals(
          "encoded-secret",
          jdbc.queryForObject(
              "select client_secret from simpoint_ac_oauth2_client where id='original'",
              String.class));
      assertEquals(untouchedContract, untouched.physicalContract("simpoint_ac_oauth2_%"));
      engine.verify(latest, Map.of("default", upgraded.source()));
      assertTrue(
          engine.migrate(latest, Map.of("default", upgraded.source())).stream()
              .allMatch(result -> result.applied() == 0));
    }
  }

  private static void insertClient(
      JdbcTemplate jdbc, String id, String clientId, String issued, String secret) {
    jdbc.update(
        "insert into simpoint_ac_oauth2_client"
            + " (id,client_id,client_id_issued_at,client_secret) values (?,?,?::timestamptz,?)",
        id,
        clientId,
        issued,
        secret);
  }
}
