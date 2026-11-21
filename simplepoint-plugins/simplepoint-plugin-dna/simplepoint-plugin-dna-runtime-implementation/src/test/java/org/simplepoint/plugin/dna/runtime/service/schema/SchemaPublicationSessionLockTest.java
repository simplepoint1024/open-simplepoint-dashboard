package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class SchemaPublicationSessionLockTest {
  @Test
  void discardsConnectionWhenAcquisitionReplyIsLost() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenThrow(new SQLException("lost response"));
    assertThatThrownBy(() -> SchemaPublicationSessionLock.acquire(connection, "postgresql", "a".repeat(64)))
        .isInstanceOf(SQLException.class);
    verify(connection).abort(any());
  }

  @Test
  void discardsConnectionWhenReleaseCannotBeConfirmed() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows).thenThrow(new SQLException("lost release response"));
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(true);
    var lock = SchemaPublicationSessionLock.acquire(connection, "mysql", "a".repeat(64));
    assertThatThrownBy(lock::close).isInstanceOf(SQLException.class);
    verify(connection).abort(any());
  }

  @Test
  void contentionDoesNotDiscardAnUnownedHealthyConnection() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(false);
    assertThatThrownBy(() -> SchemaPublicationSessionLock.acquire(connection, "postgresql", "a".repeat(64)))
        .isInstanceOf(DnaRuntimeException.class);
    verify(connection, never()).abort(any());
  }
}
