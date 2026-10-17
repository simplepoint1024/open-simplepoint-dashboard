package org.simplepoint.data.calcite.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.sql.type.SqlTypeName;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class DefaultCalciteQueryEngineTest {

  private final CalciteQueryEngine engine = new DefaultCalciteQueryEngine();

  @Test
  void trustedSessionTypesPreservePhysicalPrecisionWithoutChangingOtherSessions() throws Exception {
    JdbcDataSource source = createDataSource("temporal");
    initialize(source, "CREATE TABLE moments (at_time TIME(6)); INSERT INTO moments VALUES (TIME '12:34:56.123456');");
    CalciteSchemaConfigurer ordinary = root -> root.add("source", JdbcSchema.create(root, "source", source, null, "PUBLIC"));
    CalciteSchemaConfigurer precise = new CalciteSchemaConfigurer() {
      @Override
      public void configure(final SchemaPlus root) {
        ordinary.configure(root);
      }

      @Override
      public Class<? extends RelDataTypeSystem> typeSystem() {
        return MicrosecondTypeSystem.class;
      }

      @Override
      public ZoneId timeZone() {
        return ZoneId.of("UTC");
      }
    };
    var query = new CalciteQueryRequest("SELECT COUNT(*) FROM source.moments WHERE at_time = CAST(? AS TIME(6))",
        null, 1, 5000, List.of("12:34:56.123456"));
    assertEquals(0L, engine.execute(query, ordinary).rows().getFirst().getFirst());
    assertEquals(1L, engine.execute(query, precise).rows().getFirst().getFirst());
    assertEquals(0L, engine.execute(query, ordinary).rows().getFirst().getFirst());
  }

  /** Public constructor is required by Calcite's trusted type-system SPI. */
  public static final class MicrosecondTypeSystem extends RelDataTypeSystemImpl {
    @Override
    public int getMaxPrecision(final SqlTypeName type) {
      return type == SqlTypeName.TIME ? 6 : super.getMaxPrecision(type);
    }
  }

  @Test
  void executeShouldJoinAcrossSchemasAndReportPlatformJoin() throws Exception {
    JdbcDataSource ordersDataSource = createDataSource("orders");
    JdbcDataSource customersDataSource = createDataSource("customers");
    initialize(ordersDataSource, """
        create table orders (
          id int primary key,
          customer_id int not null,
          amount decimal(10,2) not null
        );
        insert into orders(id, customer_id, amount) values (1, 10, 20.50), (2, 11, 30.00);
        """);
    initialize(customersDataSource, """
        create table customers (
          id int primary key,
          name varchar(64) not null
        );
        insert into customers(id, name) values (10, 'Alice'), (11, 'Bob');
        """);

    CalciteQueryResult result = engine.execute(
        new CalciteQueryRequest(
            """
                select o.id, c.name
                from orders_ds.orders o
                join customers_ds.customers c on o.customer_id = c.id
                where o.amount >= 20
                order by o.id
                """,
            "demo",
            100,
            5_000
        ),
        rootSchema -> registerCatalog(rootSchema, ordersDataSource, customersDataSource)
    );

    assertEquals(2, result.returnedRows());
    assertFalse(result.truncated());
    assertEquals(2, result.columns().size());
    assertEquals("id", result.columns().get(0).name().toLowerCase());
    assertEquals("Alice", result.rows().get(0).get(1));
    assertTrue(result.analysis().platformJoin());
    assertFalse(result.analysis().planText().isBlank());
    assertFalse(result.analysis().pushedSqls().isEmpty());
    assertTrue(result.analysis().pushedSqls().stream().anyMatch(sql ->
        sql.toLowerCase().contains("orders")
            || sql.toLowerCase().contains("customers")
    ));
  }

  @Test
  void executeShouldHonorMaxRows() throws Exception {
    JdbcDataSource ordersDataSource = createDataSource("orders-limit");
    initialize(ordersDataSource, """
        create table orders (
          id int primary key,
          customer_id int not null
        );
        insert into orders(id, customer_id) values (1, 10), (2, 11), (3, 12);
        """);

    CalciteQueryResult result = engine.execute(
        new CalciteQueryRequest(
            "select id from orders_ds.orders order by id",
            "demo",
            2,
            5_000
        ),
        rootSchema -> {
          SchemaPlus catalog = rootSchema.add("demo", new AbstractSchema());
          catalog.add("orders_ds", JdbcSchema.create(catalog, "orders_ds", ordersDataSource, null, "PUBLIC"));
        }
    );

    assertEquals(2, result.returnedRows());
    assertTrue(result.truncated());
  }

  @Test
  void executeShouldExplainParameterizedQuery() throws Exception {
    JdbcDataSource ordersDataSource = createDataSource("orders-params");
    initialize(ordersDataSource, """
        create table orders (
          id int primary key,
          amount decimal(10,2) not null
        );
        insert into orders(id, amount) values (1, 10.00), (2, 20.00), (3, 30.00);
        """);

    CalciteQueryRequest request = new CalciteQueryRequest(
        "select id from orders_ds.orders where id > ? order by id",
        "demo",
        100,
        5_000,
        List.of(1)
    );

    CalciteQueryAnalysis analysis = engine.explain(
        request,
        rootSchema -> {
          SchemaPlus catalog = rootSchema.add("demo", new AbstractSchema());
          catalog.add("orders_ds", JdbcSchema.create(catalog, "orders_ds", ordersDataSource, null, "PUBLIC"));
        }
    );
    CalciteQueryResult result = engine.execute(
        request,
        rootSchema -> {
          SchemaPlus catalog = rootSchema.add("demo", new AbstractSchema());
          catalog.add("orders_ds", JdbcSchema.create(catalog, "orders_ds", ordersDataSource, null, "PUBLIC"));
        },
        analysis
    );

    assertFalse(analysis.planText().isBlank());
    assertEquals(2, result.returnedRows());
    assertEquals(2, result.rows().get(0).get(0));
  }

  @Test
  void explainShouldRejectNonQueryStatements() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> engine.explain(
        new CalciteQueryRequest("delete from orders", "demo", 100, 5_000),
        rootSchema -> {
        }
    ));

    assertTrue(exception.getMessage().contains("只读查询") || exception.getMessage().contains("SQL 解析失败"));
  }

  private static JdbcDataSource createDataSource(final String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + name + "-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static void initialize(final DataSource dataSource, final String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      for (String fragment : sql.split(";")) {
        String normalized = fragment.trim();
        if (!normalized.isEmpty()) {
          statement.execute(normalized);
        }
      }
    }
  }

  private static void registerCatalog(
      final SchemaPlus rootSchema,
      final DataSource ordersDataSource,
      final DataSource customersDataSource
  ) {
    SchemaPlus catalog = rootSchema.add("demo", new AbstractSchema());
    catalog.add("orders_ds", JdbcSchema.create(catalog, "orders_ds", ordersDataSource, null, "PUBLIC"));
    catalog.add("customers_ds", JdbcSchema.create(catalog, "customers_ds", customersDataSource, null, "PUBLIC"));
  }
}
