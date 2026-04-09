package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class MysqlJdbcDatabaseDialectTest {

  @Test
  void shouldUseJdbcIdentifierQuoteStringForQualifiedNamesAndConstraints() {
    MysqlJdbcDatabaseDialect dialect = new MysqlJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "mysql",
        "com.mysql.cj.jdbc.Driver",
        "MySQL",
        "8.0",
        "appsuite",
        null,
        "`",
        Map.of()
    );

    String qualifiedName = dialect.qualifyName("appsuite", null, "example_users", context);
    String sql = dialect.buildCreateTableSql(
        qualifiedName,
        List.of(new JdbcMetadataModels.ColumnDefinition("id", "BIGINT", null, null, false, null, null, null)),
        List.of(new JdbcMetadataModels.ConstraintDefinition(
            "pk_example_users",
            JdbcMetadataModels.ConstraintType.PRIMARY_KEY,
            List.of("id"),
            null,
            null
        )),
        context
    );

    assertEquals("`appsuite`.`example_users`", qualifiedName);
    assertTrue(sql.contains("CONSTRAINT `pk_example_users` PRIMARY KEY (`id`)"));
  }
}
