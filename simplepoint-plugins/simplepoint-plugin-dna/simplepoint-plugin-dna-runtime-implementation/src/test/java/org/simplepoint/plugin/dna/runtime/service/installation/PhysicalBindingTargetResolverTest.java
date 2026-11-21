package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PhysicalBindingTargetResolverTest {
  @Test
  void incompleteExtraAndRequestScopedSourcesFailBeforeConnections() {
    var source = mock(DataSource.class);
    var inventory = inventory("catalog", "schema", "records");
    assertThatThrownBy(() -> PhysicalBindingTargetResolver.review(inventory, Map.of()))
        .hasMessage("DNA_OWNERSHIP_TARGET_SOURCE_BINDINGS");
    assertThatThrownBy(
            () ->
                PhysicalBindingTargetResolver.review(
                    inventory,
                    Map.of(new PhysicalBindingTargetResolver.Source("other", "catalog"), source)))
        .hasMessage("DNA_OWNERSHIP_TARGET_SOURCE_BINDINGS");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> PhysicalBindingTargetResolver.review(inventory, sources(source)))
          .hasMessage("DNA_OWNERSHIP_INVENTORY_OFFLINE_REQUIRED");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(source);
  }

  @Test
  void inheritedConnectionTransactionIsNeverCommittedOrRolledBack() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    assertThatThrownBy(
            () ->
                PhysicalBindingTargetResolver.review(
                    inventory("catalog", "schema", "records"), sources(source)))
        .hasMessage("DNA_OWNERSHIP_TARGET_TRANSACTION");
    verify(connection).close();
    verify(connection, never()).commit();
    verify(connection, never()).rollback();
    verify(connection, never()).getMetaData();
  }

  @Test
  void driverDiagnosticsNeverPersistCredentials() throws Exception {
    var source = mock(DataSource.class);
    when(source.getConnection())
        .thenThrow(new SQLException("jdbc:secret password=hidden", "08001", 9));
    assertThatThrownBy(
            () ->
                PhysicalBindingTargetResolver.review(
                    inventory("catalog", "schema", "records"), sources(source)))
        .hasMessage("DNA_OWNERSHIP_TARGET_UNAVAILABLE")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).hasMessage("Native target review failed");
              assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("08001");
              assertThat(failure.getCause().getCause()).isNull();
            });
  }

  static PhysicalBindingInventory inventory(String catalog, String schema, String table) {
    return new PhysicalBindingInventory(
        1, List.of(binding("one", "source", catalog, schema, table, null)));
  }

  static PhysicalBindingInventory.Binding binding(
      String id, String source, String catalog, String schema, String table, String namespaceId) {
    return new PhysicalBindingInventory.Binding(
        id,
        id,
        "tenant",
        new PhysicalBindingInventory.Target(
            source, catalog, schema, table, PhysicalIsolationMode.SHARED_DATABASE),
        namespaceId,
        0,
        null,
        new PhysicalBindingInventory.Model("app", id, PhysicalModelStatus.ACTIVE, 1, 1, 0, null));
  }

  private static Map<PhysicalBindingTargetResolver.Source, DataSource> sources(DataSource source) {
    return Map.of(new PhysicalBindingTargetResolver.Source("source", "catalog"), source);
  }
}
