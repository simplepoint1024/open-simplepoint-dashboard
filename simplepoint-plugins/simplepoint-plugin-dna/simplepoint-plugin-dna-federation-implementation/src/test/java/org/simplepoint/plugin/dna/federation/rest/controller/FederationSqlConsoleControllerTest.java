package org.simplepoint.plugin.dna.federation.rest.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.springframework.http.ResponseEntity;

class FederationSqlConsoleControllerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void exportShouldForwardSqlConsoleRequestFields() {
    final RecordingSqlConsoleService service = new RecordingSqlConsoleService();
    final FederationSqlConsoleController controller = controller(service);

    final ResponseEntity<byte[]> response = controller.export(new FederationSqlConsoleController.ExportRequest(
        "cat1",
        "select * from t where id = ?",
        "public",
        List.of(7),
        25,
        "CSV"
    ));

    assertNotNull(service.lastRequest);
    assertEquals("cat1", service.lastRequest.catalogCode());
    assertEquals("select * from t where id = ?", service.lastRequest.sql());
    assertEquals("public", service.lastRequest.defaultSchema());
    assertArrayEquals(List.of(7).toArray(), service.lastRequest.parameters().toArray());
    assertEquals(25, service.lastRequest.maxRows());
    assertEquals("attachment; filename=export.csv", response.getHeaders().getFirst("Content-Disposition"));
    assertTrue(new String(response.getBody(), StandardCharsets.UTF_8).contains("id,name"));
  }

  @Test
  void exportShouldSerializeJsonWithStandardEscapingAndFallbackColumnNames() throws Exception {
    final RecordingSqlConsoleService service = new RecordingSqlConsoleService();
    service.columns = List.of(
        new FederationQueryModels.SqlColumn("name", "VARCHAR"),
        new FederationQueryModels.SqlColumn(null, "VARCHAR")
    );
    service.rows = List.of(Arrays.asList("Alice\b\n\"Bob", null));
    final FederationSqlConsoleController controller = controller(service);

    final ResponseEntity<byte[]> response = controller.export(new FederationSqlConsoleController.ExportRequest(
        "cat1",
        "select name, null",
        null,
        null,
        null,
        "JSON"
    ));

    JsonNode json = OBJECT_MAPPER.readTree(response.getBody());
    assertEquals("Alice\b\n\"Bob", json.get(0).get("name").asText());
    assertTrue(json.get(0).has("column_2"));
    assertTrue(json.get(0).get("column_2").isNull());
  }

  @Test
  void exportShouldReturnPlainTextBadRequestWhenServiceRejectsQuery() {
    final RecordingSqlConsoleService service = new RecordingSqlConsoleService();
    service.failure = new IllegalArgumentException("仅支持只读查询 SQL");
    final FederationSqlConsoleController controller = controller(service);

    final ResponseEntity<byte[]> response = controller.export(new FederationSqlConsoleController.ExportRequest(
        "cat1",
        "delete from t",
        null,
        null,
        null,
        "CSV"
    ));

    assertEquals(400, response.getStatusCode().value());
    assertEquals("text/plain", response.getHeaders().getContentType().toString());
    assertEquals("仅支持只读查询 SQL", new String(response.getBody(), StandardCharsets.UTF_8));
  }

  private static FederationSqlConsoleController controller(final RecordingSqlConsoleService service) {
    return new FederationSqlConsoleController(service, OBJECT_MAPPER);
  }

  private static final class RecordingSqlConsoleService implements FederationSqlConsoleService {

    private FederationQueryModels.SqlConsoleRequest lastRequest;

    private List<FederationQueryModels.SqlColumn> columns = List.of(
        new FederationQueryModels.SqlColumn("id", "INTEGER"),
        new FederationQueryModels.SqlColumn("name", "VARCHAR")
    );

    private List<List<Object>> rows = List.of(List.of(7, "Alice"));

    private RuntimeException failure;

    @Override
    public FederationQueryModels.SqlExplainResult explain(final FederationQueryModels.SqlConsoleRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FederationQueryModels.SqlQueryResult execute(final FederationQueryModels.SqlConsoleRequest request) {
      if (failure != null) {
        throw failure;
      }
      lastRequest = request;
      return new FederationQueryModels.SqlQueryResult(
          "cat1",
          "policy1",
          25,
          5_000,
          true,
          false,
          List.of("cat1"),
          columns,
          rows,
          false,
          rows.size(),
          3L,
          "plan",
          List.of("select id, name from t where id = ?"),
          "summary"
      );
    }

    @Override
    public FederationQueryModels.SqlUpdateResult executeUpdate(final FederationQueryModels.SqlConsoleRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FederationQueryModels.SqlUpdateResult executeDdl(final FederationQueryModels.SqlConsoleRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FederationQueryModels.SqlExecuteResult smartExecute(final FederationQueryModels.SqlConsoleRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
