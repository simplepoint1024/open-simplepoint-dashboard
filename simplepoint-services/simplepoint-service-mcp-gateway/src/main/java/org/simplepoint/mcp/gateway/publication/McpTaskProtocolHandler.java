package org.simplepoint.mcp.gateway.publication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.simplepoint.mcp.gateway.publication.McpPublicationControlPlaneClient.McpControlPlaneException;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskListRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;

/**
 * Thin transport-edge adapter for experimental MCP 2025-11-25 Tasks.
 *
 * <p>The official Java SDK remains responsible for lifecycle, sessions, and
 * all stable protocol features. This adapter only handles protocol structures
 * that SDK 2.0.0 does not yet expose.</p>
 */
public class McpTaskProtocolHandler {

  private static final String TASK_PROTOCOL_VERSION = "2025-11-25";

  private static final int DEFAULT_TOOL_PAGE_SIZE = 100;

  private static final int DEFAULT_TASK_PAGE_SIZE = 50;

  private final McpPublicationRegistry registry;

  private final McpPublicationControlPlaneClient controlPlaneClient;

  private final ObjectMapper objectMapper;

  private final ConcurrentHashMap<String, SessionBinding> sessions =
      new ConcurrentHashMap<>();

  /**
   * Creates the Task transport adapter.
   */
  public McpTaskProtocolHandler(
      final McpPublicationRegistry registry,
      final McpPublicationControlPlaneClient controlPlaneClient,
      final ObjectMapper objectMapper
  ) {
    this.registry = registry;
    this.controlPlaneClient = controlPlaneClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Adds the standard Task capability to a successful initialize response.
   */
  public void decorateInitialize(
      final String publicationCode,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final byte[] sdkResponse
  ) throws IOException {
    JsonNode root = objectMapper.readTree(sdkResponse);
    JsonNode result = root.path("result");
    String protocolVersion = result.path("protocolVersion").asText();
    if (TASK_PROTOCOL_VERSION.equals(protocolVersion)
        && result instanceof ObjectNode resultObject) {
      ObjectNode capabilities = resultObject.withObject("capabilities");
      capabilities.set("tasks", taskCapabilities());
      String sessionId = response.getHeader("Mcp-Session-Id");
      if (sessionId != null && !sessionId.isBlank()) {
        sessions.put(sessionId, new SessionBinding(
            publicationCode,
            subject(request),
            clientId(request),
            protocolVersion,
            Instant.now()
        ));
        evictSessions();
      }
    }
    response.getWriter().write(objectMapper.writeValueAsString(root));
    response.getWriter().flush();
  }

  /**
   * Handles Task methods and Task-aware Tool discovery/calls.
   */
  public boolean handle(
      final String publicationCode,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final JsonNode message
  ) throws IOException {
    if (message == null || !message.has("id")) {
      return false;
    }
    String method = message.path("method").asText();
    boolean taskToolCall = "tools/call".equals(method)
        && message.path("params").has("task");
    if (!taskToolCall
        && !"tools/list".equals(method)
        && !"tasks/get".equals(method)
        && !"tasks/list".equals(method)
        && !"tasks/result".equals(method)
        && !"tasks/cancel".equals(method)) {
      return false;
    }
    Object id = jsonRpcId(message.get("id"));
    try {
      SessionBinding session = requireSession(
          publicationCode,
          request.getHeader("Mcp-Session-Id")
      );
      Object result = switch (method) {
        case "tools/list" -> listTools(
            publicationCode,
            message.path("params")
        );
        case "tools/call" -> createTask(
            publicationCode,
            session,
            request.getHeader("Mcp-Session-Id"),
            message.path("params")
        );
        case "tasks/get" -> controlPlaneClient.getTask(
            taskRequest(publicationCode, session, message.path("params"))
        );
        case "tasks/list" -> controlPlaneClient.listTasks(
            new McpPublicationTaskListRequest(
                publicationCode,
                session.subject(),
                session.clientId(),
                text(message.path("params").get("cursor")),
                DEFAULT_TASK_PAGE_SIZE
            )
        );
        case "tasks/result" -> awaitResult(
            taskRequest(publicationCode, session, message.path("params"))
        );
        case "tasks/cancel" -> controlPlaneClient.cancelTask(
            taskRequest(publicationCode, session, message.path("params"))
        );
        default -> throw new IllegalArgumentException(
            "Unsupported MCP Task method"
        );
      };
      writeResult(response, id, result);
    } catch (IllegalArgumentException ex) {
      writeError(response, id, -32602, safeMessage(ex));
    } catch (McpControlPlaneException ex) {
      int code = ex.statusCode() >= 400 && ex.statusCode() < 500
          ? -32602 : -32603;
      writeError(response, id, code, safeMessage(ex));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      writeError(response, id, -32603, "MCP Task result wait was cancelled");
    } catch (RuntimeException ex) {
      writeError(response, id, -32603, safeMessage(ex));
    }
    return true;
  }

  /**
   * Removes the edge binding after the SDK deletes its session.
   */
  public void forgetSession(final String sessionId) {
    if (sessionId != null) {
      sessions.remove(sessionId);
    }
  }

  private ObjectNode listTools(
      final String publicationCode,
      final JsonNode parameters
  ) {
    McpPublicationManifest manifest = registry.manifest(publicationCode);
    int offset = decodeToolCursor(text(parameters.get("cursor")));
    int end = Math.min(
        manifest.tools().size(),
        offset + DEFAULT_TOOL_PAGE_SIZE
    );
    if (offset > manifest.tools().size()) {
      throw new IllegalArgumentException("MCP Tool cursor is invalid");
    }
    ArrayNode tools = objectMapper.createArrayNode();
    manifest.tools().subList(offset, end).forEach(tool -> {
      ObjectNode descriptor = objectMapper.valueToTree(tool);
      descriptor.set(
          "execution",
          objectMapper.createObjectNode().put("taskSupport", "optional")
      );
      tools.add(descriptor);
    });
    ObjectNode result = objectMapper.createObjectNode();
    result.set("tools", tools);
    if (end < manifest.tools().size()) {
      result.put("nextCursor", encodeToolCursor(end));
    }
    return result;
  }

  private ObjectNode createTask(
      final String publicationCode,
      final SessionBinding session,
      final String sessionId,
      final JsonNode parameters
  ) {
    String toolName = requiredText(parameters.get("name"), "MCP Tool name");
    JsonNode argumentsNode = parameters.get("arguments");
    Map<String, Object> arguments = argumentsNode == null
        || argumentsNode.isNull()
        ? Map.of()
        : objectMapper.convertValue(
            argumentsNode,
            new TypeReference<Map<String, Object>>() {}
        );
    long ttl = parameters.path("task").path("ttl").asLong(0L);
    McpTaskDescriptor task = controlPlaneClient.createTask(
        new McpPublicationTaskCreateRequest(
            publicationCode,
            toolName,
            arguments,
            session.subject(),
            session.clientId(),
            sessionId,
            ttl
        )
    );
    ObjectNode result = objectMapper.createObjectNode();
    result.set("task", objectMapper.valueToTree(task));
    return result;
  }

  private McpPublicationTaskRequest taskRequest(
      final String publicationCode,
      final SessionBinding session,
      final JsonNode parameters
  ) {
    return new McpPublicationTaskRequest(
        publicationCode,
        requiredText(parameters.get("taskId"), "MCP Task ID"),
        session.subject(),
        session.clientId()
    );
  }

  private Object awaitResult(
      final McpPublicationTaskRequest request
  ) throws InterruptedException {
    while (true) {
      McpTaskResult projection = controlPlaneClient.taskResult(request);
      String status = projection.task().status();
      if ("completed".equals(status)) {
        return relatedResult(projection.task().taskId(), projection.result());
      }
      if ("failed".equals(status)) {
        throw new TaskResultException(
            projection.errorMessage() == null
                ? "MCP Task execution failed"
                : projection.errorMessage()
        );
      }
      if ("cancelled".equals(status)) {
        throw new TaskResultException("MCP Task was cancelled");
      }
      Thread.sleep(Math.max(100L, projection.task().pollInterval()));
    }
  }

  private ObjectNode relatedResult(
      final String taskId,
      final McpGatewayToolCallResult result
  ) {
    if (result == null) {
      throw new TaskResultException("MCP Task result is unavailable");
    }
    ObjectNode response = objectMapper.createObjectNode();
    response.set("content", objectMapper.valueToTree(result.content()));
    response.put("isError", result.error());
    if (result.structuredContent() != null) {
      response.set(
          "structuredContent",
          objectMapper.valueToTree(result.structuredContent())
      );
    }
    ObjectNode meta = objectMapper.createObjectNode();
    if (result.meta() != null) {
      ObjectNode originalMeta = objectMapper.valueToTree(result.meta());
      meta.setAll(originalMeta);
    }
    meta.set(
        "io.modelcontextprotocol/related-task",
        objectMapper.createObjectNode().put("taskId", taskId)
    );
    response.set("_meta", meta);
    return response;
  }

  private SessionBinding requireSession(
      final String publicationCode,
      final String sessionId
  ) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("MCP Session ID is required");
    }
    SessionBinding binding = sessions.get(sessionId);
    if (binding == null
        || !publicationCode.equals(binding.publicationCode())
        || !TASK_PROTOCOL_VERSION.equals(binding.protocolVersion())) {
      throw new IllegalArgumentException(
          "MCP Task session is not initialized"
      );
    }
    SessionBinding touched = new SessionBinding(
        binding.publicationCode(),
        binding.subject(),
        binding.clientId(),
        binding.protocolVersion(),
        Instant.now()
    );
    sessions.replace(sessionId, binding, touched);
    return touched;
  }

  private ObjectNode taskCapabilities() {
    ObjectNode root = objectMapper.createObjectNode();
    root.set("list", objectMapper.createObjectNode());
    root.set("cancel", objectMapper.createObjectNode());
    ObjectNode requests = root.putObject("requests");
    requests.putObject("tools").set(
        "call",
        objectMapper.createObjectNode()
    );
    return root;
  }

  private void writeResult(
      final HttpServletResponse response,
      final Object id,
      final Object result
  ) throws IOException {
    ObjectNode message = objectMapper.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.set("id", objectMapper.valueToTree(id));
    message.set("result", objectMapper.valueToTree(result));
    writeSse(response, message);
  }

  private void writeError(
      final HttpServletResponse response,
      final Object id,
      final int code,
      final String message
  ) throws IOException {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("jsonrpc", "2.0");
    root.set("id", objectMapper.valueToTree(id));
    ObjectNode error = root.putObject("error");
    error.put("code", code);
    error.put("message", message);
    writeSse(response, root);
  }

  private void writeSse(
      final HttpServletResponse response,
      final JsonNode message
  ) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/event-stream");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-cache");
    response.getWriter().write(
        "event: message\ndata: "
            + objectMapper.writeValueAsString(message)
            + "\n\n"
    );
    response.getWriter().flush();
  }

  private void evictSessions() {
    if (sessions.size() <= 4096) {
      return;
    }
    Instant cutoff = Instant.now().minusSeconds(3600);
    sessions.entrySet().removeIf(entry ->
        entry.getValue().lastAccessedAt().isBefore(cutoff));
  }

  private static Object jsonRpcId(final JsonNode id) {
    if (id == null || id.isNull()) {
      throw new IllegalArgumentException("JSON-RPC ID is required");
    }
    if (id.isTextual()) {
      return id.asText();
    }
    if (id.isIntegralNumber()) {
      return id.asLong();
    }
    throw new IllegalArgumentException("JSON-RPC ID is invalid");
  }

  private static String requiredText(
      final JsonNode value,
      final String label
  ) {
    String result = text(value);
    if (result == null || result.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return result.trim();
  }

  private static String text(final JsonNode value) {
    return value == null || value.isNull() || !value.isTextual()
        ? null : value.asText();
  }

  private static String subject(final HttpServletRequest request) {
    Object value = request.getAttribute(
        McpPublicationRegistry.SUBJECT_CONTEXT_KEY
    );
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException("MCP OAuth subject is required");
    }
    return value.toString();
  }

  private static String clientId(final HttpServletRequest request) {
    Object client = request.getAttribute(
        McpPublicationRegistry.CLIENT_CONTEXT_KEY
    );
    if (client != null && !client.toString().isBlank()) {
      return client.toString();
    }
    return subject(request);
  }

  private static String encodeToolCursor(final int offset) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("tools:" + offset).getBytes(StandardCharsets.UTF_8)
    );
  }

  private static int decodeToolCursor(final String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      String decoded = new String(
          Base64.getUrlDecoder().decode(cursor),
          StandardCharsets.UTF_8
      );
      if (!decoded.startsWith("tools:")) {
        throw new IllegalArgumentException("invalid Tool cursor");
      }
      return Integer.parseInt(decoded.substring("tools:".length()));
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("MCP Tool cursor is invalid", ex);
    }
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank()
        ? "MCP Task protocol error" : message;
  }

  private record SessionBinding(
      String publicationCode,
      String subject,
      String clientId,
      String protocolVersion,
      Instant lastAccessedAt
  ) {
  }

  private static final class TaskResultException
      extends IllegalStateException {

    private TaskResultException(final String message) {
      super(message);
    }
  }
}
