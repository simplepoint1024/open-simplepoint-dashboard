package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolverTest.binding;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Native PostgreSQL/MySQL target coordinates; no schema adoption or runtime activation. */
class PhysicalBindingTargetDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aliasesAndDefaultNamespacesCannotHideDuplicatePhysicalOwnership(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var inventory =
          new PhysicalBindingInventory(
              1,
              List.of(
                  binding("one", "source", "", "", "records", null),
                  binding("two", "alias", fixture.catalog, fixture.name, "records", null)));
      var sources =
          Map.<PhysicalBindingTargetResolver.Source, DataSource>of(
              new PhysicalBindingTargetResolver.Source("source", ""), fixture.source,
              new PhysicalBindingTargetResolver.Source("alias", fixture.catalog), fixture.source);
      assertThatThrownBy(() -> PhysicalBindingTargetResolver.review(inventory, sources))
          .hasMessage("DNA_OWNERSHIP_TARGET_COLLISION");
      try (var connection = fixture.source.getConnection()) {
        assertThat(PhysicalOwnershipInstaller.inspect(connection, fixture.name).namespaceId())
            .isNull();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reviewPreservesUnicodeAndQuotedNamesAndDoesNotActivateDirectory(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var id = UUID.randomUUID();
      try (var connection = fixture.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, fixture.name, id);
      }
      var inventory =
          new PhysicalBindingInventory(
              1,
              List.of(
                  binding("one", "source", "", "", "客户_取引", id.toString()),
                  binding("two", "source", "", "", "CaseSensitive", id.toString())));
      var review = fixture.review(inventory);
      assertThat(review.controlFingerprint()).isEqualTo(inventory.fingerprint());
      assertThat(review.bindings())
          .extracting(PhysicalBindingTargetReview.Binding::table)
          .containsExactly("客户_取引", "CaseSensitive");
      assertThat(review.bindings())
          .allSatisfy(
              value -> {
                assertThat(value.namespace().target().namespace()).isEqualTo(fixture.name);
                assertThat(value.namespace().namespaceId()).isEqualTo(id);
              });
      assertThat(fixture.review(inventory).fingerprint()).isEqualTo(review.fingerprint());
      try (var connection = fixture.source.getConnection()) {
        assertThatThrownBy(() -> PhysicalOwnershipSession.open(connection, fixture.name))
            .isInstanceOfSatisfying(
                PhysicalOwnershipException.class,
                failure ->
                    assertThat(failure.code())
                        .isEqualTo(PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void staleNamespaceIdentityAndWrongConnectedCatalogFailClosed(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var inventory =
          new PhysicalBindingInventory(
              1,
              List.of(binding("one", "source", "", "", "records", UUID.randomUUID().toString())));
      assertThatThrownBy(() -> fixture.review(inventory))
          .hasMessage("DNA_OWNERSHIP_TARGET_NAMESPACE_IDENTITY");
      try (var connection = fixture.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, fixture.name, UUID.randomUUID());
      }
      assertThatThrownBy(() -> fixture.review(inventory))
          .hasMessage("DNA_OWNERSHIP_TARGET_NAMESPACE_IDENTITY");
      var wrong =
          new PhysicalBindingInventory(
              1, List.of(binding("one", "source", "wrong-catalog", "", "records", null)));
      assertThatThrownBy(
              () ->
                  PhysicalBindingTargetResolver.review(
                      wrong,
                      Map.of(
                          new PhysicalBindingTargetResolver.Source("source", "wrong-catalog"),
                          fixture.source)))
          .hasMessage("DNA_OWNERSHIP_TARGET_CATALOG");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changingDefaultNamespaceOrDirectoryInstallationInvalidatesReview(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var other = new Fixture(postgres)) {
      var inventory =
          new PhysicalBindingInventory(
              1, List.of(binding("one", "source", "", "", "records", null)));
      var before = fixture.review(inventory);
      assertThat(other.review(inventory).fingerprint()).isNotEqualTo(before.fingerprint());
      try (var connection = fixture.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, fixture.name, UUID.randomUUID());
      }
      assertThat(fixture.review(inventory).fingerprint()).isNotEqualTo(before.fingerprint());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sameTableNameAcrossIndependentNamespacesDoesNotCollide(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var other = new Fixture(postgres)) {
      var inventory =
          new PhysicalBindingInventory(
              1,
              List.of(
                  binding("one", "source", "", "", "records", null),
                  binding("two", "other", "", "", "records", null)));
      var review =
          PhysicalBindingTargetResolver.review(
              inventory,
              Map.of(
                  new PhysicalBindingTargetResolver.Source("source", ""), fixture.source,
                  new PhysicalBindingTargetResolver.Source("other", ""), other.source));
      assertThat(review.bindings()).hasSize(2);
      UUID reused = UUID.randomUUID();
      try (var connection = fixture.source.getConnection();
          var second = other.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, fixture.name, reused);
        PhysicalOwnershipInstaller.install(second, other.name, reused);
      }
      assertThatThrownBy(
              () ->
                  PhysicalBindingTargetResolver.review(
                      inventory,
                      Map.of(
                          new PhysicalBindingTargetResolver.Source("source", ""), fixture.source,
                          new PhysicalBindingTargetResolver.Source("other", ""), other.source)))
          .hasMessage("DNA_OWNERSHIP_TARGET_IDENTITY_COLLISION");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unavailableNamespaceAndOverlongNamesNeverFallBackOrTruncate(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var missing =
          new PhysicalBindingInventory(
              1,
              List.of(
                  binding(
                      "one",
                      "source",
                      "",
                      "missing_" + UUID.randomUUID().toString().replace("-", ""),
                      "records",
                      null)));
      if (postgres) {
        assertThatThrownBy(() -> fixture.review(missing))
            .hasMessage("DNA_OWNERSHIP_TARGET_NAMESPACE_UNAVAILABLE")
            .hasCauseInstanceOf(PhysicalOwnershipException.class);
      } else {
        assertThatThrownBy(() -> fixture.review(missing)).hasMessage("DNA_OWNERSHIP_TARGET_SCHEMA");
      }
      var overlong =
          new PhysicalBindingInventory(
              1, List.of(binding("one", "source", "", "", "x".repeat(65), null)));
      assertThatThrownBy(() -> fixture.review(overlong))
          .hasMessage("DNA_OWNERSHIP_TARGET_INVALID_OBJECT_NAME")
          .hasCauseInstanceOf(PhysicalOwnershipException.class);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final boolean postgres;
    final String name = "own_target_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource base;
    final DriverManagerDataSource source;
    final String catalog;

    Fixture(boolean postgres) throws Exception {
      this.postgres = postgres;
      String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      String url = System.getenv(prefix + "_URL");
      Assumptions.assumeTrue(url != null, "Disposable native database required");
      String user = System.getenv(prefix + "_USER");
      String password = System.getenv(prefix + "_PASSWORD");
      base = new DriverManagerDataSource(url, user, password);
      try (var connection = base.getConnection()) {
        execute(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
      }
      try {
        String targetUrl;
        if (postgres) {
          targetUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + name;
        } else {
          URI endpoint = URI.create(url.substring(5));
          targetUrl =
              "jdbc:"
                  + new URI(
                      endpoint.getScheme(),
                      endpoint.getUserInfo(),
                      endpoint.getHost(),
                      endpoint.getPort(),
                      "/" + name,
                      endpoint.getQuery(),
                      endpoint.getFragment());
        }
        source = new DriverManagerDataSource(targetUrl, user, password);
        try (var connection = source.getConnection()) {
          catalog = connection.getCatalog();
        }
      } catch (Exception failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    PhysicalBindingTargetReview review(PhysicalBindingInventory inventory) {
      return PhysicalBindingTargetResolver.review(
          inventory, Map.of(new PhysicalBindingTargetResolver.Source("source", ""), source));
    }

    @Override
    public void close() throws SQLException {
      try (var connection = base.getConnection()) {
        execute(
            connection,
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + "IF EXISTS "
                + name
                + (postgres ? " CASCADE" : ""));
      }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(60);
        statement.execute(sql);
      }
    }
  }
}
