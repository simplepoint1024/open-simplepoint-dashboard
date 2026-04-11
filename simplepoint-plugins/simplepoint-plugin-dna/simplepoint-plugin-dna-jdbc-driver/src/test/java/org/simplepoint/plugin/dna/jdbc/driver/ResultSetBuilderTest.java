package org.simplepoint.plugin.dna.jdbc.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultSetBuilderTest {

  @Test
  void trimsQueryRowsWhenMaxRowsConfigured() throws SQLException {
    DnaJdbcModels.QueryResult result = new DnaJdbcModels.QueryResult(
        List.of(new DnaJdbcModels.ColumnDef("name", "VARCHAR", Types.VARCHAR)),
        List.of(List.of("Alice"), List.of("Bob")),
        false,
        2L
    );

    try (ResultSet resultSet = ResultSetBuilder.fromQueryResult(result, 1)) {
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getString(1)).isEqualTo("Alice");
      assertThat(resultSet.next()).isFalse();
      assertThat(resultSet.getMetaData().getColumnType(1)).isEqualTo(Types.VARCHAR);
    }
  }

  @Test
  void infersJdbcTypesFromTypeNamesWhenCodeMissing() throws SQLException {
    DnaJdbcModels.TabularResult result = new DnaJdbcModels.TabularResult(
        List.of(new DnaJdbcModels.ColumnDef("count", "INTEGER", null)),
        List.of(List.of(42))
    );

    try (ResultSet resultSet = ResultSetBuilder.fromTabularResult(result)) {
      assertThat(resultSet.getMetaData().getColumnType(1)).isEqualTo(Types.INTEGER);
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getInt(1)).isEqualTo(42);
    }
  }

  @Test
  void handlesAbsoluteZeroAsBeforeFirst() throws SQLException {
    DnaJdbcModels.QueryResult result = new DnaJdbcModels.QueryResult(
        List.of(new DnaJdbcModels.ColumnDef("name", "VARCHAR", Types.VARCHAR)),
        List.of(List.of("Alice")),
        false,
        1L
    );

    try (ResultSet resultSet = ResultSetBuilder.fromQueryResult(result, 0)) {
      assertThat(resultSet.absolute(0)).isFalse();
      assertThat(resultSet.isBeforeFirst()).isTrue();
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getString(1)).isEqualTo("Alice");
    }
  }

  @Test
  void supportsAbsoluteForwardAndBackwardNavigation() throws SQLException {
    DnaJdbcModels.QueryResult result = new DnaJdbcModels.QueryResult(
        List.of(new DnaJdbcModels.ColumnDef("name", "VARCHAR", Types.VARCHAR)),
        List.of(List.of("Alice"), List.of("Bob")),
        false,
        2L
    );

    try (ResultSet resultSet = ResultSetBuilder.fromQueryResult(result, 0)) {
      assertThat(resultSet.absolute(2)).isTrue();
      assertThat(resultSet.getString(1)).isEqualTo("Bob");
      assertThat(resultSet.absolute(-2)).isTrue();
      assertThat(resultSet.getString(1)).isEqualTo("Alice");
    }
  }
}
