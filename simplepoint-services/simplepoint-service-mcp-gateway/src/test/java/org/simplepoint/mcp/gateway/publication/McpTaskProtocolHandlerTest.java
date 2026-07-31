package org.simplepoint.mcp.gateway.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpTaskProtocolHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final McpPublicationRegistry registry =
      mock(McpPublicationRegistry.class);

  private final McpPublicationControlPlaneClient controlPlane =
      mock(McpPublicationControlPlaneClient.class);

  private final McpTaskProtocolHandler handler =
      new McpTaskProtocolHandler(registry, controlPlane, objectMapper);

  @BeforeEach
  void initializeTaskSession() throws Exception {
    MockHttpServletRequest request = authorizedRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setHeader("Mcp-Session-Id", "session-1");

    handler.decorateInitialize(
        "demo",
        request,
        response,
        """
        {
          "jsonrpc":"2.0",
          "id":1,
          "result":{
            "protocolVersion":"2025-11-25",
            "capabilities":{"tools":{"listChanged":true}},
            "serverInfo":{"name":"demo","version":"1"}
          }
        }
        """.getBytes(StandardCharsets.UTF_8)
    );

    JsonNode initialized = objectMapper.readTree(
        response.getContentAsByteArray()
    );
    assertThat(
        initialized.path("result").path("capabilities")
            .path("tasks").path("requests").path("tools")
            .has("call")
    ).isTrue();
  }

  @Test
  void advertisesOptionalTaskSupportInToolDiscovery() throws Exception {
    when(registry.manifest("demo")).thenReturn(manifest());
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean handled = handler.handle(
        "demo",
        sessionRequest(),
        response,
        objectMapper.readTree("""
            {
              "jsonrpc":"2.0",
              "id":2,
              "method":"tools/list",
              "params":{}
            }
            """)
    );

    assertThat(handled).isTrue();
    JsonNode result = sseMessage(response).path("result");
    assertThat(result.path("tools").get(0).path("name").asText())
        .isEqualTo("echo");
    assertThat(result.path("tools").get(0).path("execution")
        .path("taskSupport").asText()).isEqualTo("optional");
  }

  @Test
  void createsAuthorizationBoundToolTask() throws Exception {
    McpTaskDescriptor created = task("working");
    when(controlPlane.createTask(any())).thenReturn(created);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(
        "demo",
        sessionRequest(),
        response,
        objectMapper.readTree("""
            {
              "jsonrpc":"2.0",
              "id":"create-1",
              "method":"tools/call",
              "params":{
                "name":"echo",
                "arguments":{"message":"hello"},
                "task":{"ttl":60000}
              }
            }
            """)
    );

    assertThat(sseMessage(response).path("result").path("task")
        .path("taskId").asText()).isEqualTo("task-1");
    ArgumentCaptor<McpPublicationTaskCreateRequest> captor =
        ArgumentCaptor.forClass(McpPublicationTaskCreateRequest.class);
    verify(controlPlane).createTask(captor.capture());
    assertThat(captor.getValue().publicationCode()).isEqualTo("demo");
    assertThat(captor.getValue().subject()).isEqualTo("subject-1");
    assertThat(captor.getValue().clientId()).isEqualTo("client-1");
    assertThat(captor.getValue().sessionId()).isEqualTo("session-1");
    assertThat(captor.getValue().ttl()).isEqualTo(60000);
  }

  @Test
  void returnsExactToolResultWithRelatedTaskMetadata() throws Exception {
    when(controlPlane.taskResult(any())).thenReturn(new McpTaskResult(
        task("completed"),
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "done")),
            false,
            Map.of("value", "done"),
            Map.of("source", "test")
        ),
        null,
        null
    ));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(
        "demo",
        sessionRequest(),
        response,
        objectMapper.readTree("""
            {
              "jsonrpc":"2.0",
              "id":4,
              "method":"tasks/result",
              "params":{"taskId":"task-1"}
            }
            """)
    );

    JsonNode result = sseMessage(response).path("result");
    assertThat(result.path("content").get(0).path("text").asText())
        .isEqualTo("done");
    assertThat(result.path("_meta")
        .path("io.modelcontextprotocol/related-task")
        .path("taskId").asText()).isEqualTo("task-1");
  }

  private MockHttpServletRequest authorizedRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        McpPublicationRegistry.SUBJECT_CONTEXT_KEY,
        "subject-1"
    );
    request.setAttribute(
        McpPublicationRegistry.CLIENT_CONTEXT_KEY,
        "client-1"
    );
    return request;
  }

  private MockHttpServletRequest sessionRequest() {
    MockHttpServletRequest request = authorizedRequest();
    request.addHeader("Mcp-Session-Id", "session-1");
    return request;
  }

  private JsonNode sseMessage(
      final MockHttpServletResponse response
  ) throws Exception {
    String body = response.getContentAsString();
    String data = body.lines()
        .filter(line -> line.startsWith("data: "))
        .findFirst()
        .orElseThrow()
        .substring("data: ".length());
    return objectMapper.readTree(data);
  }

  private static McpTaskDescriptor task(final String status) {
    return new McpTaskDescriptor(
        "task-1",
        status,
        "status",
        "2026-01-01T00:00:00Z",
        "2026-01-01T00:00:01Z",
        60000,
        1000
    );
  }

  private static McpPublicationManifest manifest() {
    return new McpPublicationManifest(
        "demo",
        "Demo",
        "Demo publication",
        "https://example.test/mcp/demo",
        "https://auth.example.test",
        List.of("mcp:invoke"),
        60,
        "server-1",
        "snapshot-1",
        "2025-11-25",
        List.of(new McpToolDescriptor(
            "echo",
            "Echo",
            "Echoes input",
            Map.of("type", "object"),
            Map.of("type", "object"),
            Map.of(),
            List.of()
        )),
        List.of(),
        List.of(),
        List.of()
    );
  }
}
