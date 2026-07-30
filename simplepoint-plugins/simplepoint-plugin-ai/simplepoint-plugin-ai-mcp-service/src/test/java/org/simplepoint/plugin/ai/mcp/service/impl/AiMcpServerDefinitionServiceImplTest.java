package org.simplepoint.plugin.ai.mcp.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpCapabilitySnapshotRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpOauthAuthorizationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpPublicationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpInvocationLedger;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpSnapshotStore;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpEndpointService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AiMcpServerDefinitionServiceImplTest {

  @Mock
  private AiMcpServerDefinitionRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private AiMcpCapabilitySnapshotRepository snapshotRepository;

  @Mock
  private AiMcpOauthAuthorizationRepository oauthAuthorizationRepository;

  @Mock
  private AiMcpPublicationRepository publicationRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private AiCredentialCipher credentialCipher;

  @Mock
  private McpGatewayOperations gatewayOperations;

  @Mock
  private AiMcpSnapshotStore snapshotStore;

  @Mock
  private AiMcpInvocationLedger invocationLedger;

  @Mock
  private AiRuntimeMcpEndpointService runtimeMcpEndpointService;

  @Mock
  private AiRuntimePoolService runtimePoolService;

  private AiMcpServerDefinitionServiceImpl service;

  private AiMcpServerDefinition server;

  private AiMcpCapabilitySnapshot snapshot;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new AiMcpServerDefinitionServiceImpl(
        repository,
        detailsProviderService,
        snapshotRepository,
        oauthAuthorizationRepository,
        publicationRepository,
        scopeAccessPolicy,
        credentialCipher,
        gatewayOperations,
        snapshotStore,
        invocationLedger,
        objectMapper,
        runtimeMcpEndpointService,
        runtimePoolService
    );
    server = new AiMcpServerDefinition();
    server.setId("server-a");
    server.setScopeType(AiResourceScope.SYSTEM);
    server.setActiveSnapshotId("snapshot-a");

    snapshot = new AiMcpCapabilitySnapshot();
    snapshot.setId("snapshot-a");
    snapshot.setServerId("server-a");
    snapshot.setProtocolVersion("2025-11-25");
    snapshot.setRemoteServerName("fixture");
    snapshot.setRemoteServerVersion("1.0.0");
    snapshot.setCapabilitiesJson("{\"tools\":{\"listChanged\":true}}");
    snapshot.setToolsJson("""
        [{"name":"echo","inputSchema":{"type":"object"}}]
        """);
    snapshot.setResourcesJson("[]");
    snapshot.setResourceTemplatesJson("[]");
    snapshot.setPromptsJson("[]");
    snapshot.setSchemaHash("a".repeat(64));
    snapshot.setDiscoveredAt(Instant.parse("2026-07-29T00:00:00Z"));

    when(repository.findActiveById("server-a"))
        .thenReturn(Optional.of(server));
  }

  @Test
  void listsSafeSnapshotSummaries() {
    PageRequest pageable = PageRequest.of(0, 20);
    when(snapshotRepository.findAllActiveByServerId("server-a", pageable))
        .thenReturn(new PageImpl<>(List.of(snapshot), pageable, 1));

    var result = service.listSnapshots("server-a", pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent().getFirst().active()).isTrue();
    assertThat(result.getContent().getFirst().toolCount()).isEqualTo(1);
  }

  @Test
  void returnsDecodedSnapshotDetails() {
    when(snapshotRepository.findActiveByIdAndServerId(
        "snapshot-a",
        "server-a"
    )).thenReturn(Optional.of(snapshot));

    var result = service.getSnapshot("server-a", "snapshot-a");

    assertThat(result.active()).isTrue();
    assertThat(result.tools()).extracting("name").containsExactly("echo");
    assertThat(result.capabilities()).containsKey("tools");
  }

  @Test
  void rejectsRemovalWhileRuntimePoolExists() {
    AiRuntimePool pool = new AiRuntimePool();
    pool.setId("pool-a");
    when(runtimePoolService.findByServer("server-a"))
        .thenReturn(Optional.of(pool));

    assertThatThrownBy(() -> service.removeByIds(List.of("server-a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Runtime pool");
  }

  @Test
  void workflowCallUsesExactPinnedSnapshotAndSchema() {
    server.setEnabled(true);
    server.setStatus(McpServerStatus.READY);
    server.setEndpointUrl("https://mcp.example.com/mcp");
    server.setAuthenticationType(McpAuthenticationType.NONE);
    when(scopeAccessPolicy.canUseResourceFromScope(
        AiResourceScope.SYSTEM,
        null,
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(true);
    when(snapshotRepository.findActiveByIdAndServerId(
        "snapshot-a",
        "server-a"
    )).thenReturn(Optional.of(snapshot));
    when(invocationLedger.start(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
    )).thenReturn("invocation-a");
    when(gatewayOperations.callWorkflowTool(any())).thenReturn(
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "ok")),
            false,
            Map.of("message", "ok"),
            Map.of()
        )
    );

    McpGatewayToolCallResult result = service.callWorkflowTool(
        new McpWorkflowToolCallRequest(
            AiResourceScope.SYSTEM,
            null,
            "server-a",
            "snapshot-a",
            "echo",
            "a2c799262a3ce3c19ef5cdd983bf3d12"
                + "b43ab3c426227091b909dcb7054738c0",
            Map.of("message", "hello"),
            "skill-a",
            "version-a",
            "execution-a",
            "step-a",
            "user-a",
            "capability-a"
        )
    );

    assertThat(result.error()).isFalse();
    org.mockito.ArgumentCaptor<McpGatewayWorkflowToolCallRequest> request =
        org.mockito.ArgumentCaptor.forClass(
            McpGatewayWorkflowToolCallRequest.class
        );
    verify(gatewayOperations).callWorkflowTool(request.capture());
    assertThat(request.getValue().capabilityToken())
        .isEqualTo("capability-a");
    assertThat(request.getValue().call().connection().connectionId())
        .isEqualTo("server-a");
    assertThat(request.getValue().call().toolName()).isEqualTo("echo");
    assertThat(request.getValue().call().operationId())
        .isEqualTo("execution-a:step-a");
    assertThat(request.getValue().call().meta())
        .containsEntry("simplepoint/subjectId", "user-a");
    verify(invocationLedger).succeed("invocation-a", result);
  }

  @Test
  void workflowPromptUsesPinnedDescriptorAndCapabilityRoute()
      throws Exception {
    prepareWorkflowServer();
    McpPromptDescriptor prompt = new McpPromptDescriptor(
        "welcome",
        "Welcome",
        "Creates a welcome prompt",
        List.of(Map.of("name", "name", "required", false)),
        Map.of(),
        List.of()
    );
    snapshot.setPromptsJson(objectMapper.writeValueAsString(List.of(prompt)));
    when(snapshotRepository.findActiveByIdAndServerId(
        "snapshot-a",
        "server-a"
    )).thenReturn(Optional.of(snapshot));
    when(invocationLedger.start(
        any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn("invocation-prompt");
    McpGatewayPromptGetResult gatewayResult = new McpGatewayPromptGetResult(
        "Welcome",
        List.of(Map.of(
            "role", "user",
            "content", Map.of("type", "text", "text", "Hello SimplePoint")
        )),
        Map.of()
    );
    when(gatewayOperations.getWorkflowPrompt(any()))
        .thenReturn(gatewayResult);

    McpGatewayPromptGetResult result = service.getWorkflowPrompt(
        new McpWorkflowPromptGetRequest(
            AiResourceScope.SYSTEM,
            null,
            "server-a",
            "snapshot-a",
            "welcome",
            descriptorHash(prompt),
            Map.of("name", "SimplePoint"),
            "skill-a",
            "version-a",
            "execution-a",
            "prompt-step",
            "user-a",
            "capability-prompt"
        )
    );

    assertThat(result).isEqualTo(gatewayResult);
    org.mockito.ArgumentCaptor<McpGatewayWorkflowPromptGetRequest> request =
        org.mockito.ArgumentCaptor.forClass(
            McpGatewayWorkflowPromptGetRequest.class
        );
    verify(gatewayOperations).getWorkflowPrompt(request.capture());
    assertThat(request.getValue().capabilityToken())
        .isEqualTo("capability-prompt");
    assertThat(request.getValue().call().name()).isEqualTo("welcome");
    assertThat(request.getValue().call().arguments())
        .containsEntry("name", "SimplePoint");
    verify(invocationLedger).succeed("invocation-prompt", result);
  }

  @Test
  void workflowResourceResolvesOnlyInsidePinnedTemplate()
      throws Exception {
    prepareWorkflowServer();
    McpResourceTemplateDescriptor template =
        new McpResourceTemplateDescriptor(
            "document://{documentId}",
            "Document",
            null,
            "Reads a document",
            "text/plain",
            Map.of(),
            Map.of(),
            List.of()
        );
    snapshot.setResourceTemplatesJson(
        objectMapper.writeValueAsString(List.of(template))
    );
    when(snapshotRepository.findActiveByIdAndServerId(
        "snapshot-a",
        "server-a"
    )).thenReturn(Optional.of(snapshot));
    when(invocationLedger.start(
        any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn("invocation-resource");
    McpGatewayResourceReadResult gatewayResult =
        new McpGatewayResourceReadResult(
            List.of(Map.of(
                "uri", "document://42",
                "mimeType", "text/plain",
                "text", "Document 42"
            )),
            Map.of()
        );
    when(gatewayOperations.readWorkflowResource(any()))
        .thenReturn(gatewayResult);

    McpGatewayResourceReadResult result = service.readWorkflowResource(
        new McpWorkflowResourceReadRequest(
            AiResourceScope.SYSTEM,
            null,
            "server-a",
            "snapshot-a",
            "document://42",
            "document://{documentId}",
            true,
            descriptorHash(template),
            "skill-a",
            "version-a",
            "execution-a",
            "resource-step",
            "user-a",
            "capability-resource"
        )
    );

    assertThat(result).isEqualTo(gatewayResult);
    org.mockito.ArgumentCaptor<McpGatewayWorkflowResourceReadRequest> request =
        org.mockito.ArgumentCaptor.forClass(
            McpGatewayWorkflowResourceReadRequest.class
        );
    verify(gatewayOperations).readWorkflowResource(request.capture());
    assertThat(request.getValue().capabilityToken())
        .isEqualTo("capability-resource");
    assertThat(request.getValue().call().uri()).isEqualTo("document://42");
    verify(invocationLedger).succeed("invocation-resource", result);

    assertThatThrownBy(() -> service.readWorkflowResource(
        new McpWorkflowResourceReadRequest(
            AiResourceScope.SYSTEM,
            null,
            "server-a",
            "snapshot-a",
            "other://42",
            "document://{documentId}",
            true,
            descriptorHash(template),
            "skill-a",
            "version-a",
            "execution-b",
            "resource-step",
            "user-a",
            "capability-resource"
        )
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside the pinned template");
  }

  private void prepareWorkflowServer() {
    server.setEnabled(true);
    server.setStatus(McpServerStatus.READY);
    server.setEndpointUrl("https://mcp.example.com/mcp");
    server.setAuthenticationType(McpAuthenticationType.NONE);
    when(scopeAccessPolicy.canUseResourceFromScope(
        AiResourceScope.SYSTEM,
        null,
        AiResourceScope.SYSTEM,
        null
    )).thenReturn(true);
  }

  private String descriptorHash(final Object value) throws Exception {
    String canonical = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .writeValueAsString(value);
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8))
    );
  }
}
