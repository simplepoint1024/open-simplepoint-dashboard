package org.simplepoint.plugin.ai.skill.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Resolves and pins Draft Manifest MCP bindings for one debug execution. */
@Component
public class SkillDraftCapabilityResolver {

  private final ObjectProvider<AiMcpServerDefinitionService> mcpServerService;

  private final ObjectMapper canonicalMapper;

  /** Creates the immutable capability resolver. */
  public SkillDraftCapabilityResolver(
      final ObjectProvider<AiMcpServerDefinitionService> mcpServerService,
      final ObjectMapper objectMapper
  ) {
    this.mcpServerService = mcpServerService;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /** Resolves all aliases against their exact persisted snapshots. */
  public ResolvedBindings resolve(final Map<String, Object> spec) {
    Map<String, ToolBinding> tools = new LinkedHashMap<>();
    for (Map<String, Object> source : list(spec.get("tools"), "Skill tools")) {
      String alias = required(source.get("alias"), "Tool alias");
      McpCapabilitySnapshotDetails snapshot = snapshot(source);
      String name = required(source.get("name"), "MCP Tool name");
      McpToolDescriptor descriptor = snapshot.tools().stream()
          .filter(candidate -> name.equals(candidate.name()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP Tool does not exist in pinned snapshot: " + name
          ));
      if (tools.putIfAbsent(alias, new ToolBinding(
          alias,
          required(source.get("serverId"), "MCP server ID"),
          required(source.get("snapshotId"), "MCP snapshot ID"),
          name,
          sha256(json(descriptor.inputSchema())),
          descriptor.outputSchema(),
          requiresApproval(descriptor)
      )) != null) {
        throw new IllegalArgumentException("Duplicate Skill Tool alias: " + alias);
      }
    }

    Map<String, PromptBinding> prompts = new LinkedHashMap<>();
    for (Map<String, Object> source : optionalList(
        spec.get("prompts"),
        "Skill prompts"
    )) {
      String alias = required(source.get("alias"), "Prompt alias");
      McpCapabilitySnapshotDetails snapshot = snapshot(source);
      String name = required(source.get("name"), "MCP Prompt name");
      McpPromptDescriptor descriptor = snapshot.prompts().stream()
          .filter(candidate -> name.equals(candidate.name()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP Prompt does not exist in pinned snapshot: " + name
          ));
      if (prompts.putIfAbsent(alias, new PromptBinding(
          alias,
          required(source.get("serverId"), "MCP server ID"),
          required(source.get("snapshotId"), "MCP snapshot ID"),
          name,
          sha256(json(descriptor))
      )) != null) {
        throw new IllegalArgumentException(
            "Duplicate Skill Prompt alias: " + alias
        );
      }
    }

    Map<String, ResourceBinding> resources = new LinkedHashMap<>();
    for (Map<String, Object> source : optionalList(
        spec.get("resources"),
        "Skill resources"
    )) {
      String alias = required(source.get("alias"), "Resource alias");
      McpCapabilitySnapshotDetails snapshot = snapshot(source);
      boolean template = source.get("uriTemplate") != null;
      String selector = required(
          template ? source.get("uriTemplate") : source.get("uri"),
          template ? "MCP Resource URI Template" : "MCP Resource URI"
      );
      Object descriptor = template
          ? snapshot.resourceTemplates().stream()
              .filter(candidate -> selector.equals(candidate.uriTemplate()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException(
                  "MCP Resource Template does not exist in pinned snapshot: "
                      + selector
              ))
          : snapshot.resources().stream()
              .filter(candidate -> selector.equals(candidate.uri()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException(
                  "MCP Resource does not exist in pinned snapshot: "
                      + selector
              ));
      if (resources.putIfAbsent(alias, new ResourceBinding(
          alias,
          required(source.get("serverId"), "MCP server ID"),
          required(source.get("snapshotId"), "MCP snapshot ID"),
          selector,
          template,
          sha256(json(descriptor))
      )) != null) {
        throw new IllegalArgumentException(
            "Duplicate Skill Resource alias: " + alias
        );
      }
    }
    return new ResolvedBindings(
        Map.copyOf(tools),
        Map.copyOf(prompts),
        Map.copyOf(resources)
    );
  }

  private McpCapabilitySnapshotDetails snapshot(
      final Map<String, Object> source
  ) {
    AiMcpServerDefinitionService service = mcpServerService.getIfAvailable();
    if (service == null) {
      throw new IllegalStateException(
          "Draft MCP capability resolution is unavailable in this runtime"
      );
    }
    return service.getSnapshot(
        required(source.get("serverId"), "MCP server ID"),
        required(source.get("snapshotId"), "MCP snapshot ID")
    );
  }

  private static boolean requiresApproval(final McpToolDescriptor tool) {
    Map<String, Object> annotations = tool.annotations() == null
        ? Map.of() : tool.annotations();
    boolean readOnly = Boolean.TRUE.equals(annotations.get("readOnlyHint"));
    return !readOnly
        && !Boolean.FALSE.equals(annotations.get("destructiveHint"));
  }

  private String json(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Pinned MCP descriptor cannot be serialized",
          exception
      );
    }
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String required(final Object value, final String label) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return text.trim();
  }

  private static List<Map<String, Object>> optionalList(
      final Object value,
      final String label
  ) {
    return value == null ? List.of() : list(value, label);
  }

  private static List<Map<String, Object>> list(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> source)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    return source.stream().map(item -> {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException(label + " item must be an object");
      }
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, nested) -> result.put(String.valueOf(key), nested));
      return result;
    }).toList();
  }

  /** Exact binding set persisted into execution step checkpoints. */
  public record ResolvedBindings(
      Map<String, ToolBinding> tools,
      Map<String, PromptBinding> prompts,
      Map<String, ResourceBinding> resources
  ) {
  }

  /** Resolved Tool descriptor identity. */
  public record ToolBinding(
      String alias,
      String serverId,
      String snapshotId,
      String name,
      String schemaHash,
      Map<String, Object> outputSchema,
      boolean requiresApproval
  ) {
  }

  /** Resolved Prompt descriptor identity. */
  public record PromptBinding(
      String alias,
      String serverId,
      String snapshotId,
      String name,
      String schemaHash
  ) {
  }

  /** Resolved Resource descriptor identity. */
  public record ResourceBinding(
      String alias,
      String serverId,
      String snapshotId,
      String selector,
      boolean template,
      String schemaHash
  ) {
  }
}
