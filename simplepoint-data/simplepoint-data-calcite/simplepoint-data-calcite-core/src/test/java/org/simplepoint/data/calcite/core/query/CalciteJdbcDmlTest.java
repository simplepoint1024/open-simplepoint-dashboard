package org.simplepoint.data.calcite.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class CalciteJdbcDmlTest {


  @Test
  void jdbcAdapterCanInsertUpdateAndDeleteThroughCalcite() throws Exception {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:calcite-dml-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    source.setUser("sa");
    source.setPassword("");
    try (Connection physical = source.getConnection(); Statement statement = physical.createStatement()) {
      statement.execute("create table orders (id int primary key, amount int not null)");
      statement.execute("create table incoming (id int primary key, amount int not null)");
      statement.execute("insert into incoming (id, amount) values (2, 30)");
    }

    DefaultCalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    CalciteSchemaConfigurer schemaConfigurer = root -> {
      var catalog = root.add("demo", new AbstractSchema());
      catalog.add("orders_ds", JdbcSchema.create(catalog, "orders_ds", source, null, "PUBLIC"));
    };
    CalciteUpdateResult inserted = engine.executeUpdate(new CalciteUpdateRequest(
        "insert into orders_ds.orders (id, amount) values (?, ?)", "demo", 5_000, List.of(1, 10)), schemaConfigurer);
    assertEquals(1, inserted.affectedRows());
    assertFalse(inserted.pushedSqls().isEmpty());
    assertEquals(1, engine.executeUpdate(new CalciteUpdateRequest(
        "update orders_ds.orders set amount = 20 where id = 1", "demo", 5_000, null), schemaConfigurer).affectedRows());
    assertEquals(1, engine.executeUpdate(new CalciteUpdateRequest(
        "insert into orders_ds.orders (id, amount) select id, amount from orders_ds.incoming",
        "demo", 5_000, null), schemaConfigurer).affectedRows());
    assertEquals(1, engine.executeUpdate(new CalciteUpdateRequest(
        "delete from orders_ds.orders where id = 1", "demo", 5_000, null), schemaConfigurer).affectedRows());

    try (Connection physical = source.getConnection();
         Statement statement = physical.createStatement();
         ResultSet result = statement.executeQuery("select count(*) from orders")) {
      result.next();
      assertEquals(1, result.getInt(1));
    }
  }
}
