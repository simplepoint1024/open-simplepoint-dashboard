package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.JDBCType;
import java.sql.Types;
import org.apache.calcite.avatica.util.ByteString;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

class RuntimeJdbcConnectionTest {
  @Test
  void borrowedResultAndMetadataReferencesCannotCloseOrCommitThePhysicalTransaction() throws Exception {
    Connection physical = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet rows = mock(ResultSet.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(physical.createStatement()).thenReturn(statement);
    when(statement.executeQuery("read")).thenReturn(rows);
    when(physical.getMetaData()).thenReturn(metadata);
    Connection borrowed = RuntimeJdbcConnection.borrow(physical);

    Connection fromRows = borrowed.createStatement().executeQuery("read").getStatement().getConnection();
    assertThat(fromRows).isSameAs(borrowed);
    assertThat(borrowed.getMetaData().getConnection()).isSameAs(borrowed);
    assertThat(fromRows.unwrap(Connection.class)).isSameAs(borrowed);
    fromRows.close();
    borrowed.getMetaData().getConnection().close();
    verify(physical, never()).close();
    assertThatThrownBy(fromRows::commit).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> fromRows.setAutoCommit(true)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> fromRows.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> fromRows.setReadOnly(true)).isInstanceOf(SQLException.class);
    verify(physical, never()).commit();
  }

  @Test
  void bindsCalciteBinaryAsBytesWithoutChangingJdbcOverloadsOrTransactionOwnership() throws Exception {
    Connection physical = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(physical.prepareStatement("write")).thenReturn(statement);
    Connection borrowed = RuntimeJdbcConnection.borrow(physical);
    var prepared = borrowed.prepareStatement("write");
    byte[] bytes = {(byte) 0xfb, (byte) 0xff};
    var value = new ByteString(bytes);
    prepared.setObject(1, value);
    prepared.setObject(2, value, Types.VARBINARY);
    prepared.setObject(3, value, JDBCType.VARBINARY, 2);
    prepared.setObject(4, "text");
    prepared.setObject(5, null);
    verify(statement).setObject(1, bytes);
    verify(statement).setObject(2, bytes, Types.VARBINARY);
    verify(statement).setObject(3, bytes, JDBCType.VARBINARY, 2);
    verify(statement).setObject(4, "text");
    verify(statement).setObject(5, null);
    assertThat(prepared.getConnection()).isSameAs(borrowed);
    assertThatThrownBy(() -> prepared.getConnection().commit()).isInstanceOf(SQLException.class);
    verify(physical, never()).commit();
  }

  @Test
  void queryResultBackReferenceRestoresSessionStateBeforeClosingExactlyOnce() throws Exception {
    Connection physical = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet rows = mock(ResultSet.class);
    RuntimeSchemaDialect dialect = mock(RuntimeSchemaDialect.class);
    when(physical.createStatement()).thenReturn(statement);
    when(statement.executeQuery("read")).thenReturn(rows);
    Connection connection = RuntimeJdbcConnection.query(physical, dialect);

    connection.createStatement().executeQuery("read").getStatement().getConnection().close();
    connection.close();
    var order = inOrder(dialect, physical);
    order.verify(dialect).resetRecordQueryConnection(physical);
    order.verify(physical).close();
    assertThat(connection.isClosed()).isTrue();
  }
}
