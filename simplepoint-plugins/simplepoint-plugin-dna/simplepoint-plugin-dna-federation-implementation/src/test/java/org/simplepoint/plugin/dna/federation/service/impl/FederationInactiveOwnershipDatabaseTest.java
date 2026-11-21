package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

class FederationInactiveOwnershipDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void installedDirectoryDoesNotMakeUnadoptedLegacyTablesWritable(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, false)) {
      UUID identity = UUID.randomUUID();
      fixture.create("legacy", false);
      fixture.execute("INSERT INTO legacy VALUES (1,7)");
      try (var connection = fixture.pool.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, fixture.name, identity);
        assertTrue(PhysicalOwnershipInstaller.inspect(connection, fixture.name).complete());
        assertEquals(
            PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION,
            assertThrows(
                    PhysicalOwnershipException.class,
                    () ->
                        PhysicalOwnershipInstaller.activateFresh(
                            connection, fixture.name, identity))
                .code());
      }
      for (String sql :
          new String[] {
            "UPDATE ds.legacy SET amount=9 WHERE id=1",
            "DELETE FROM ds.legacy WHERE id=1",
            "INSERT INTO ds.legacy VALUES (2,8)"
          }) {
        assertEquals(
            FederationSqlWriteException.Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE,
            assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null))
                .errorCode(),
            sql);
      }
      for (String sql :
          new String[] {"DROP TABLE ds.legacy", "CREATE TABLE ds.not_created (id INTEGER)"}) {
        assertEquals(
            FederationSqlWriteException.Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE,
            assertThrows(
                    FederationSqlWriteException.class,
                    () -> fixture.console.executeDdl(new SqlConsoleRequest("ds", sql)))
                .errorCode(),
            sql);
      }
      assertEquals(7, fixture.scalar("SELECT amount FROM legacy WHERE id=1"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM legacy"));
    }
  }
}
