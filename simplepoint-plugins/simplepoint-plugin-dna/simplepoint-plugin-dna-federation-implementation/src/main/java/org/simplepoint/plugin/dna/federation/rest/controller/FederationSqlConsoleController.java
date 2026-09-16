package org.simplepoint.plugin.dna.federation.rest.controller;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Federation SQL console endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.SQL_CONSOLE, DnaFederationPaths.PLATFORM_SQL_CONSOLE})
@Tag(name = "联邦 SQL 控制台", description = "用于执行和分析联邦 SQL（支持 SELECT / DML / DDL / FLUSH CACHE）")
public class FederationSqlConsoleController {

  private final FederationSqlConsoleService service;

  private final ObjectMapper objectMapper;

  /**
   * Creates a federation SQL console controller.
   *
   * @param service SQL console service
   * @param objectMapper JSON serializer
   */
  public FederationSqlConsoleController(
      final FederationSqlConsoleService service,
      final ObjectMapper objectMapper
  ) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  /**
   * Explains a federation SQL query.
   *
   * @param request SQL console request
   * @return explain response
   */
  @PostMapping("/explain")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.explain')")
  @Operation(summary = "查看执行计划", description = "生成联邦只读 SQL 的 Calcite 执行计划和下推摘要")
  public Response<?> explain(@RequestBody final FederationQueryModels.SqlConsoleRequest request) {
    try {
      return Response.okay(service.explain(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Executes a read-only federation SQL query.
   *
   * @param request SQL console request
   * @return query response
   */
  @PostMapping("/query")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.execute')")
  @Operation(summary = "执行只读 SQL", description = "执行联邦只读 SQL 并返回结果、执行计划和下推摘要")
  public Response<?> query(@RequestBody final FederationQueryModels.SqlConsoleRequest request) {
    try {
      return Response.okay(service.execute(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Unified smart-execute endpoint. Automatically detects the SQL statement type
   * (SELECT / DML / DDL / FLUSH CACHE) and dispatches to the appropriate service
   * method.
   *
   * @param request SQL console request
   * @return unified execution result with a type discriminator
   */
  @PostMapping("/execute")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.execute')")
  @Operation(
      summary = "智能执行 SQL",
      description = "自动识别 SQL 类型并执行: SELECT → 联邦查询, DML → 直接下推, DDL → 直接下推, FLUSH CACHE → 清理缓存"
  )
  public Response<?> execute(@RequestBody final FederationQueryModels.SqlConsoleRequest request) {
    try {
      return Response.okay(service.smartExecute(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Exports query results in the requested format.
   *
   * @param request export request with SQL console request fields and format (CSV/JSON)
   * @return byte array download
   */
  @PostMapping("/export")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.execute')")
  @Operation(summary = "导出查询结果", description = "执行查询并以 CSV 或 JSON 格式导出结果")
  public ResponseEntity<byte[]> export(
      @RequestBody final ExportRequest request
  ) {
    try {
      FederationQueryModels.SqlQueryResult result = service.execute(
          new FederationQueryModels.SqlConsoleRequest(
              request.catalogCode(),
              request.sql(),
              request.defaultSchema(),
              request.parameters(),
              request.maxRows()
          )
      );
      String format = request.format() == null ? "CSV" : request.format().toUpperCase();
      return switch (format) {
        case "JSON" -> buildJsonExport(result);
        default -> buildCsvExport(result);
      };
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequestBytes(ex.getMessage());
    } catch (IOException ex) {
      throw new IllegalStateException("导出失败: " + ex.getMessage(), ex);
    }
  }

  private ResponseEntity<byte[]> buildCsvExport(
      final FederationQueryModels.SqlQueryResult result
  ) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Writer writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
      writer.write('\uFEFF');
      List<FederationQueryModels.SqlColumn> columns = result.columns();
      for (int i = 0; i < columns.size(); i++) {
        if (i > 0) {
          writer.write(',');
        }
        writeCsvField(writer, exportColumnName(columns, i));
      }
      writer.write('\n');
      for (List<Object> row : result.rows()) {
        for (int i = 0; i < row.size(); i++) {
          if (i > 0) {
            writer.write(',');
          }
          writeCsvField(writer, row.get(i) == null ? "" : String.valueOf(row.get(i)));
        }
        writer.write('\n');
      }
      writer.flush();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export.csv")
        .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
        .body(baos.toByteArray());
  }

  private ResponseEntity<byte[]> buildJsonExport(
      final FederationQueryModels.SqlQueryResult result
  ) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (JsonGenerator generator = objectMapper.getFactory().createGenerator(baos)) {
      List<FederationQueryModels.SqlColumn> columns = result.columns();
      generator.writeStartArray();
      for (List<Object> row : result.rows()) {
        generator.writeStartObject();
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
          generator.writeFieldName(exportColumnName(columns, i));
          generator.writeObject(row.get(i));
        }
        generator.writeEndObject();
      }
      generator.writeEndArray();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export.json")
        .contentType(MediaType.APPLICATION_JSON)
        .body(baos.toByteArray());
  }

  private static String exportColumnName(
      final List<FederationQueryModels.SqlColumn> columns,
      final int index
  ) {
    if (columns == null || index < 0 || index >= columns.size()) {
      return "column_" + (index + 1);
    }
    String name = trimToNull(columns.get(index).name());
    return name == null ? "column_" + (index + 1) : name;
  }

  private static void writeCsvField(final Writer writer, final String value) throws IOException {
    String normalized = value == null ? "" : value;
    if (normalized.indexOf(',') >= 0
        || normalized.indexOf('"') >= 0
        || normalized.indexOf('\n') >= 0
        || normalized.indexOf('\r') >= 0) {
      writer.write('"');
      writer.write(normalized.replace("\"", "\"\""));
      writer.write('"');
    } else {
      writer.write(normalized);
    }
  }

  /**
   * Export request body.
   *
   * @param catalogCode   datasource catalog code
   * @param sql           SQL to export
   * @param defaultSchema optional default schema for unqualified identifiers
   * @param parameters    optional bind parameters for server-side prepared execution
   * @param maxRows       optional per-export row limit
   * @param format        export format (CSV/JSON)
   */
  public record ExportRequest(
      String catalogCode,
      String sql,
      String defaultSchema,
      List<Object> parameters,
      Integer maxRows,
      String format
  ) {
  }

  private static Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }

  private static ResponseEntity<byte[]> badRequestBytes(final String message) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.TEXT_PLAIN)
        .body((trimToNull(message) == null ? "请求参数错误" : message).getBytes(StandardCharsets.UTF_8));
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
