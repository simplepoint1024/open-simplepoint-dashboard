package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly;

/**
 * Prepared table binding must retain the exact execution connection, including remapped catalogs.
 */
class FederationWriteBindingTest {
  @Test
  void sourceIdentitySelectsTheOwningSessionAndPreservesItsTransaction() throws Exception {
    try (var first = FederationWriteDataSource.pin(pool(), false);
        var second = FederationWriteDataSource.pin(pool(), false);
        var assembly = assembly(List.of(first, second))) {
      var one = first.ownedConnection();
      var two = second.ownedConnection();
      assertNotSame(one, two);
      one.setAutoCommit(false);
      try {
        assertSame(one, assembly.writeConnection(first));
        assertSame(two, assembly.writeConnection(second));
        assertFalse(assembly.writeConnection(first).getAutoCommit());
        assertTrue(assembly.writeConnection(second).getAutoCommit());
        assertEquals(List.of(one, two), assembly.writeConnections());
      } finally {
        one.rollback();
      }
    }
  }

  @Test
  void equalButUnownedSourceCannotBorrowOrImpersonateTheOwner() throws Exception {
    var calls = new AtomicInteger();
    DataSource impersonator =
        (DataSource)
            Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  calls.incrementAndGet();
                  if (method.getName().equals("equals")) {
                    return true;
                  }
                  throw new AssertionError("Unowned datasource must not be inspected or borrowed");
                });
    try (var source = FederationWriteDataSource.pin(pool(), false);
        var assembly = assembly(List.of(source))) {
      assertThrows(SQLException.class, () -> assembly.writeConnection(impersonator));
      assertThrows(SQLException.class, () -> assembly.writeConnection(null));
      assertEquals(0, calls.get());
      assertSame(source.ownedConnection(), assembly.writeConnection(source));
    }
  }

  @Test
  void mixedReadAndClosedAssembliesCannotProvideWriteBindings() throws Exception {
    try (var source = FederationWriteDataSource.pin(pool(), false);
        var readPool = pool();
        var mixed = assembly(List.of(source, readPool));
        var read =
            new FederationCalciteCatalogAssembly(
                "ds", List.of("ds"), root -> {}, List.of(source), false, 0, 1, false)) {
      // Validate the complete scope before returning even its first valid entry.
      assertThrows(SQLException.class, () -> mixed.writeConnection(source));
      assertThrows(SQLException.class, mixed::writeConnections);
      assertThrows(SQLException.class, () -> read.writeConnection(source));
      assertThrows(SQLException.class, read::writeConnections);
    }
    var source = FederationWriteDataSource.pin(pool(), false);
    var closed = assembly(List.of(source));
    var owner = closed.writeConnection(source);
    closed.close();
    assertTrue(owner.isClosed());
    assertThrows(SQLException.class, () -> closed.writeConnection(source));
    assertThrows(SQLException.class, closed::writeConnections);
  }

  private static FederationCalciteCatalogAssembly assembly(List<SimpleDataSource> sources) {
    return new FederationCalciteCatalogAssembly("ds", List.of("ds"), root -> {}, sources);
  }

  private static SimpleDataSource pool() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID());
    return new SimpleDataSource(source);
  }
}
