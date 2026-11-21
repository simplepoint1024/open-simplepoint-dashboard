package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInspection;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises the host's persisted review and native execution with a real control journal. */
class PhysicalOwnershipInstallationDatabaseTest {
  private static final String ARTIFACT = "a".repeat(64);
  private static final String PREFIX = "installation.ownership.namespaces.business.";
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reviewIsPrivateAndReadOnlyAndRestartVerifiesActualDirectory(boolean postgres)
      throws Exception {
    try (var control = new Namespace(true);
        var target = new Namespace(postgres)) {
      var properties = properties(target);
      var sources = Map.<String, DataSource>of("control", control.source);
      var review = execute("review", sources, properties);
      assertThat(review).containsEntry("applied", false).containsEntry("activated", false);
      assertThat(target.inspect().namespaceId()).isNull();
      Path file = Path.of(properties.getProperty("installation.ownership.review-file"));
      assertThat(Files.getPosixFilePermissions(file))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      String contents = Files.readString(file);
      assertThat(contents).doesNotContain("jdbc:", "password", "username");
      var saved =
          new ObjectMapper().readValue(contents, PhysicalOwnershipInstallation.Review.class);
      assertThat(saved.namespaces()).hasSize(1);
      final UUID identity = saved.namespaces().getFirst().namespaceId();
      assertThat(saved.namespaces().getFirst().descriptor().namespace()).isEqualTo(target.name);
      properties.setProperty(
          "installation.ownership.review-fingerprint", review.get("reviewFingerprint").toString());
      assertThat(execute("install", sources, properties))
          .containsEntry("applied", true)
          .containsEntry("verified", true)
          .containsEntry("legacyAdopted", false)
          .containsEntry("activated", false);
      assertThat(target.inspect().namespaceId()).isEqualTo(identity);
      assertThat(target.inspect().complete()).isTrue();
      try (var connection = target.source.getConnection()) {
        assertThatThrownBy(() -> PhysicalOwnershipSession.open(connection, target.name))
            .isInstanceOfSatisfying(
                PhysicalOwnershipException.class,
                failure ->
                    assertThat(failure.code())
                        .isEqualTo(PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED));
      }
      assertThat(execute("verify", sources, properties)).containsEntry("verified", true);
      execute("install", sources, properties);
      assertThat(
              new JdbcTemplate(control.source)
                  .queryForObject(
                      "SELECT COUNT(*) FROM simpoint_installation_attempts", Long.class))
          .isEqualTo(1L);
      assertThatThrownBy(() -> execute("review", sources, properties))
          .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
      assertThat(Files.readString(file)).isEqualTo(contents);
      new JdbcTemplate(target.source).execute("DROP TABLE simpoint_physical_object_owners_v1");
      assertThatThrownBy(() -> execute("verify", sources, properties))
          .hasMessageContaining("INCOMPLETE");
      assertThatThrownBy(() -> execute("install", sources, properties))
          .hasMessageContaining("INCOMPLETE");
      assertThat(target.inspect().namespaceId()).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedReviewInputsAreRejectedBeforeDirectoryOrJournalMutation(boolean postgres)
      throws Exception {
    try (var control = new Namespace(true);
        var otherControl = new Namespace(true);
        var target = new Namespace(postgres)) {
      var properties = properties(target);
      var sources = Map.<String, DataSource>of("control", control.source);
      var review = execute("review", sources, properties);
      properties.setProperty(
          "installation.ownership.review-fingerprint", review.get("reviewFingerprint").toString());
      for (var change :
          Map.of(
                  "installation.operation",
                  UUID.randomUUID().toString(),
                  "installation.ownership.target",
                  "another-target",
                  "installation.ownership.expected-generation",
                  "1",
                  PREFIX + "namespace",
                  "another_namespace",
                  PREFIX + "namespace-id",
                  UUID.randomUUID().toString(),
                  "installation.ownership.review-fingerprint",
                  "b".repeat(64))
              .entrySet()) {
        var changed = new Properties();
        changed.putAll(properties);
        changed.setProperty(change.getKey(), change.getValue());
        assertThatThrownBy(() -> execute("install", sources, changed))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(target.inspect().namespaceId()).isNull();
      }
      assertThatThrownBy(
              () ->
                  PhysicalOwnershipInstallation.execute(
                      "ownership-install", sources, properties, "b".repeat(64)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> execute("install", Map.of("control", otherControl.source), properties))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(
              new JdbcTemplate(control.source)
                  .queryForObject(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",
                      Long.class,
                      control.name))
          .isZero();
      assertThat(target.inspect().namespaceId()).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingDirectoryRequiresExplicitStableIdentityAndLegacyTablesRemainInactive(
      boolean postgres) throws Exception {
    try (var control = new Namespace(true);
        var target = new Namespace(postgres)) {
      var jdbc = new JdbcTemplate(target.source);
      jdbc.execute("CREATE TABLE legacy_business (id INTEGER PRIMARY KEY)");
      jdbc.update("INSERT INTO legacy_business VALUES (7)");
      UUID identity = UUID.randomUUID();
      try (var connection = target.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, target.name, identity);
      }
      var properties = properties(target);
      var sources = Map.<String, DataSource>of("control", control.source);
      assertThatThrownBy(() -> execute("review", sources, properties))
          .hasMessageContaining("TARGET_CHANGED");
      assertThat(
              Files.exists(Path.of(properties.getProperty("installation.ownership.review-file"))))
          .isFalse();
      properties.setProperty(PREFIX + "namespace-id", identity.toString());
      var review = execute("review", sources, properties);
      properties.setProperty(
          "installation.ownership.review-fingerprint", review.get("reviewFingerprint").toString());
      execute("install", sources, properties);
      try (var connection = target.source.getConnection()) {
        assertThatThrownBy(
                () -> PhysicalOwnershipInstaller.activateFresh(connection, target.name, identity))
            .isInstanceOfSatisfying(
                PhysicalOwnershipException.class,
                failure ->
                    assertThat(failure.code())
                        .isEqualTo(
                            PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION));
      }
      assertThat(target.inspect().namespaceId()).isEqualTo(identity);
      assertThat(jdbc.queryForObject("SELECT id FROM legacy_business", Integer.class)).isEqualTo(7);
    }
  }

  private Properties properties(Namespace target) {
    var properties = new Properties();
    properties.setProperty("installation.operation", UUID.randomUUID().toString());
    properties.setProperty("installation.journal.store", "control");
    properties.setProperty("installation.ownership.target", "test/physical-ownership");
    properties.setProperty("installation.ownership.expected-generation", "0");
    properties.setProperty(
        "installation.ownership.review-file", directory.resolve("review.json").toString());
    properties.setProperty("installation.ownership.namespaces", "business");
    properties.setProperty(PREFIX + "url", target.source.getUrl());
    properties.setProperty(PREFIX + "username", target.source.getUsername());
    properties.setProperty(PREFIX + "password", target.source.getPassword());
    properties.setProperty(PREFIX + "namespace", target.name);
    return properties;
  }

  private static Map<String, Object> execute(
      String action, Map<String, DataSource> sources, Properties properties) throws Exception {
    return PhysicalOwnershipInstallation.execute(
        "ownership-" + action, sources, properties, ARTIFACT);
  }

  private static final class Namespace implements AutoCloseable {
    final String name = "host_own_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource base;
    final DriverManagerDataSource source;
    final boolean postgres;

    Namespace(boolean postgres) {
      this.postgres = postgres;
      String prefix = "PHYSICAL_OWNERSHIP_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      String url = System.getenv(prefix + "_URL");
      Assumptions.assumeTrue(url != null, "Disposable native database required");
      String user = System.getenv(prefix + "_USER");
      String password = System.getenv(prefix + "_PASSWORD");
      base = new DriverManagerDataSource(url, user, password);
      new JdbcTemplate(base).execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
      if (postgres) {
        source =
            new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + name, user, password);
      } else {
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        int query = url.indexOf('?');
        source =
            new DriverManagerDataSource(
                url.substring(0, slash + 1) + name + (query < 0 ? "" : url.substring(query)),
                user,
                password);
      }
    }

    PhysicalOwnershipInspection inspect() throws SQLException {
      try (var connection = source.getConnection()) {
        return PhysicalOwnershipInstaller.inspect(connection, name);
      }
    }

    @Override
    public void close() {
      new JdbcTemplate(base)
          .execute(
              "DROP " + (postgres ? "SCHEMA " : "DATABASE ") + name + (postgres ? " CASCADE" : ""));
    }
  }
}
