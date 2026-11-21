package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Real target ownership through the runtime adapter; control datasource lookup is isolated. */
class PhysicalModelOwnershipRegistryDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void datasourceAliasesCannotReserveTheSamePhysicalObjectForDifferentOwners(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres);
        var connection = fixture.source.getConnection()) {
      UUID identity = UUID.randomUUID();
      RuntimeOwnershipTestFixture.installFresh(connection, fixture.namespace, identity);
      var first = fixture.registry();
      var alias = fixture.registry();
      String model = UUID.randomUUID().toString();
      assertThat(first.reserve("tenant-a", model, fixture.placement("first"), "records"))
          .isEqualTo(identity.toString());
      assertThat(alias.reserve("tenant-a", model, fixture.placement("alias"), "records"))
          .isEqualTo(identity.toString());
      // No control row was committed. Its reservation must nevertheless remain durable.
      reject(
          () ->
              alias.reserve(
                  "tenant-b", UUID.randomUUID().toString(), fixture.placement("alias"), "records"),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      fixture.execute(connection, "CREATE TABLE " + fixture.table("records") + " (id INTEGER)");
      assertThat(first.reserve("tenant-a", model, fixture.placement("first"), "records"))
          .isEqualTo(identity.toString());
      try (var session = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        assertThat(session.owner("records", true))
            .contains(new PhysicalObjectOwner("dna-runtime", "tenant-a", model));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void registrationNeverAutoInstallsOrAdoptsExistingTables(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres);
        var connection = fixture.source.getConnection()) {
      var registry = fixture.registry();
      var placement = fixture.placement("first");
      String model = UUID.randomUUID().toString();
      reject(
          () -> registry.reserve("tenant-a", model, placement, "records"),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE);
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.install(connection, fixture.namespace, identity);
      reject(
          () -> registry.reserve("tenant-a", model, placement, "records"),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE);
      PhysicalOwnershipInstaller.activateFresh(connection, fixture.namespace, identity);
      fixture.execute(connection, "CREATE TABLE " + fixture.table("legacy") + " (id INTEGER)");
      reject(
          () -> registry.reserve("tenant-a", model, placement, "legacy"),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      try (var session = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        assertThat(session.owner("legacy", true)).isEmpty();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void namespaceContentionPreventsRegistrationUntilTheExecutingSessionReleases(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres);
        var connection = fixture.source.getConnection()) {
      UUID identity = UUID.randomUUID();
      RuntimeOwnershipTestFixture.installFresh(connection, fixture.namespace, identity);
      var registry = fixture.registry();
      var placement = fixture.placement("first");
      String model = UUID.randomUUID().toString();
      try (var session = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        reject(
            () -> registry.reserve("tenant-a", model, placement, "records"),
            DnaRuntimeErrorCode.PHYSICAL_MODEL_REGISTRATION_CONFLICT);
        assertThat(session.owner("records", true)).isEmpty();
      }
      assertThat(registry.reserve("tenant-a", model, placement, "records"))
          .isEqualTo(identity.toString());
    }
  }

  private static void reject(Runnable operation, DnaRuntimeErrorCode code) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
