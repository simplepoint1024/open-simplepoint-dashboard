package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;

class JdbcForeignKeyInspectorTest {
  @Test
  void pairsCompositeColumnsBySequenceAcrossInterleavedMetadataAndPreservesIdentity() throws Exception {
    var metadata = metadata(List.of(row("fk_z", 2, "customer_id", "CustomerID"),
        row("fk_a", 1, "tenant_id", "TenantID"), row("fk_z", 1, "tenant_id", "TenantID"),
        row("fk_a", 2, "supplier_id", "SupplierID")));
    var result = JdbcForeignKeyInspector.inspect(metadata, "db", "public", "orders");
    assertThat(result).extracting(PhysicalForeignKeySnapshot::name).containsExactly("fk_a", "fk_z");
    var customer = result.get(1);
    assertThat(customer.columns()).containsExactly("tenant_id", "customer_id");
    assertThat(customer.targetColumns()).containsExactly("TenantID", "CustomerID");
    assertThat(customer.targetTable()).isEqualTo("Customers");
    assertThat(customer.deleteAction()).isEqualTo(Action.RESTRICT);
    assertThat(customer.deferrability()).isEqualTo(Deferrability.NOT_DEFERRABLE);
  }

  @Test
  void incompleteConflictingOrUnknownMetadataCannotProduceAFalseValidSnapshot() throws Exception {
    var first = row("fk", 1, "tenant_id", "tenant_id");
    var conflict = row("fk", 2, "customer_id", "id");
    conflict.put("DELETE_RULE", DatabaseMetaData.importedKeyCascade);
    var unknown = new HashMap<>(first); unknown.put("UPDATE_RULE", -1);
    var missingName = new HashMap<>(first); missingName.remove("FK_NAME");
    var missingSequence = new HashMap<>(first); missingSequence.remove("KEY_SEQ");
    var unknownDeferrability = new HashMap<>(first); unknownDeferrability.put("DEFERRABILITY", -1);
    for (var rows : List.of(List.of(first, first), List.of(first, conflict), List.of(unknown),
        List.of(missingName), List.of(missingSequence), List.of(unknownDeferrability),
        List.of(row("fk", 2, "customer_id", "id")))) {
      var metadata = metadata(rows);
      assertThatThrownBy(() -> JdbcForeignKeyInspector.inspect(metadata, "db", "public", "orders"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void foreignNamespaceRowsCannotContaminateTheRequestedTable() throws Exception {
    var other = row("fk", 1, "customer_id", "id"); other.put("FKTABLE_SCHEM", "other");
    assertThat(JdbcForeignKeyInspector.inspect(metadata(List.of(other)), "db", "public", "orders")).isEmpty();
  }

  @Test
  void incomingConstraintsWithTheSameNameRemainDistinctAcrossSourceTables() throws Exception {
    var first = row("same_fk", 1, "tenant_id", "tenant_id");
    var second = row("same_fk", 2, "customer_id", "id");
    var third = new HashMap<>(first); third.put("FKTABLE_NAME", "invoices");
    var fourth = new HashMap<>(second); fourth.put("FKTABLE_NAME", "invoices");
    var result = JdbcForeignKeyInspector.incoming(metadata(List.of(first, third, fourth, second)), "db", "public", "Customers");
    assertThat(result).extracting(JdbcForeignKeyInspector.ForeignKeyLink::sourceTable).containsExactly("invoices", "orders");
    assertThat(result).allSatisfy(link -> assertThat(link.constraint().columns()).containsExactly("tenant_id", "customer_id"));
  }

  @Test
  void identicalExportedColumnRowsAreCollapsedButConflictingMappingsFailClosed() throws Exception {
    var first = row("fk", 1, "tenant_id", "tenant_id");
    var second = row("fk", 2, "customer_id", "id");
    var result = JdbcForeignKeyInspector.incoming(metadata(List.of(first, first, second, second)), "db", "public", "Customers");
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().constraint().columns()).containsExactly("tenant_id", "customer_id");
    var conflict = new HashMap<>(second); conflict.put("PKCOLUMN_NAME", "other_id");
    assertThatThrownBy(() -> JdbcForeignKeyInspector.incoming(metadata(List.of(first, second, conflict)), "db", "public", "Customers"))
        .isInstanceOf(SQLException.class);
    var otherRule = new HashMap<>(second); otherRule.put("DELETE_RULE", DatabaseMetaData.importedKeyCascade);
    assertThatThrownBy(() -> JdbcForeignKeyInspector.incoming(metadata(List.of(first, second, otherRule)), "db", "public", "Customers"))
        .isInstanceOf(SQLException.class);
  }

  private static Map<String, Object> row(String name, int sequence, String source, String target) {
    var row = new HashMap<String, Object>();
    row.put("FKTABLE_CAT", "db"); row.put("FKTABLE_SCHEM", "public"); row.put("FKTABLE_NAME", "orders");
    row.put("FK_NAME", name); row.put("KEY_SEQ", sequence); row.put("FKCOLUMN_NAME", source);
    row.put("PKTABLE_CAT", "db"); row.put("PKTABLE_SCHEM", "public"); row.put("PKTABLE_NAME", "Customers");
    row.put("PKCOLUMN_NAME", target); row.put("UPDATE_RULE", DatabaseMetaData.importedKeyNoAction);
    row.put("DELETE_RULE", DatabaseMetaData.importedKeyRestrict);
    row.put("DEFERRABILITY", DatabaseMetaData.importedKeyNotDeferrable);
    return row;
  }

  private static DatabaseMetaData metadata(List<? extends Map<String, Object>> values) throws SQLException {
    var metadata = mock(DatabaseMetaData.class);
    var rows = mock(ResultSet.class); var position = new AtomicInteger(-1);
    var wasNull = new AtomicBoolean();
    when(metadata.getImportedKeys(any(), any(), any())).thenReturn(rows);
    when(metadata.getExportedKeys(any(), any(), any())).thenReturn(rows);
    when(rows.next()).thenAnswer(invocation -> position.incrementAndGet() < values.size());
    when(rows.getString(anyString())).thenAnswer(invocation -> values.get(position.get()).get(invocation.getArgument(0)));
    when(rows.getInt(anyString())).thenAnswer(invocation -> {
      Object value = values.get(position.get()).get(invocation.getArgument(0));
      wasNull.set(value == null);
      return value == null ? 0 : value;
    });
    when(rows.wasNull()).thenAnswer(invocation -> wasNull.get());
    return metadata;
  }
}
