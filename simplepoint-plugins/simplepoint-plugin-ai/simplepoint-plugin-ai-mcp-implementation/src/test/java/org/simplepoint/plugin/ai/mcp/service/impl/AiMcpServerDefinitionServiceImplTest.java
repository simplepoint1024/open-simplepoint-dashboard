package org.simplepoint.plugin.ai.mcp.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpToolCallCommand;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpCapabilitySnapshotRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpOauthAuthorizationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpProviderConnectionRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpPublicationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpInvocationLedger;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpSnapshotStore;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpEndpoint;
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
  private AiMcpProviderConnectionRepository providerConnectionRepository;

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
        providerConnectionRepository,
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
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setAttributes(Map.of());
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        context
    );
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY
    );
  }

  @Test
  void pageScopeRejectsForeignSnapshotReadsBeforeLoadingCapabilities() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    context.setResourceDataScopes(Map.of("ai.mcp.servers.view",
        new org.simplepoint.core.ResourceDataScope("SELF", java.util.Set.of(), false)));
    org.simplepoint.core.ResourcePolicyContext.set("ai.mcp.servers.view");
    server.setCreatedBy("other");
    try {
      assertThatThrownBy(() -> service.listSnapshots("server-a", PageRequest.of(0, 20)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      org.mockito.Mockito.verifyNoInteractions(snapshotRepository, gatewayOperations, credentialCipher);
    } finally { org.simplepoint.core.ResourcePolicyContext.clear(); }
  }

  @Test
  void readOnlyBearerTokenCannotBeReplacedThroughTheEncryptedField() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    context.setFieldPermissions(Map.of(AiMcpServerDefinition.class.getName() + "#bearerToken", "VISIBLE"));
    var input = new AiMcpServerDefinition(); input.setId("server-a");
    input.setName("Test"); input.setCode("test"); input.setEndpointUrl("https://mcp.example.com/mcp");
    input.setAuthenticationType(McpAuthenticationType.BEARER); input.setBearerToken("replacement");
    assertThatThrownBy(() -> service.modifyById(input))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    org.mockito.Mockito.verifyNoInteractions(credentialCipher, gatewayOperations);
    verify(repository, org.mockito.Mockito.never()).save(any());
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
  void returnsSnapshotForMatchingDurableBackgroundScope() {
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

    var result = service.getSnapshotForScope(
        "server-a",
        "snapshot-a",
        AiResourceScope.SYSTEM,
        null
    );

    assertThat(result.tools()).extracting("name").containsExactly("echo");
    verify(scopeAccessPolicy).canUseResourceFromScope(
        AiResourceScope.SYSTEM,
        null,
        AiResourceScope.SYSTEM,
        null
    );
  }

  @Test
  void rejectsSnapshotOutsideDurableBackgroundScope() {
    when(scopeAccessPolicy.canUseResourceFromScope(
        AiResourceScope.SYSTEM,
        null,
        AiResourceScope.TENANT,
        "tenant-a"
    )).thenReturn(false);

    assertThatThrownBy(() -> service.getSnapshotForScope(
        "server-a",
        "snapshot-a",
        AiResourceScope.TENANT,
        "tenant-a"
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("background work scope");
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
  void managedDiscoveryAndDirectCallsShareStableManagementSession() {
    server.setDeploymentType(McpServerDeploymentType.MANAGED_OCI);
    server.setAuthenticationType(McpAuthenticationType.NONE);
    server.setEnabled(true);
    server.setStatus(McpServerStatus.READY);
    when(snapshotRepository.findActiveById("snapshot-a"))
        .thenReturn(Optional.of(snapshot));
    when(runtimeMcpEndpointService.resolve(
        "server-a",
        AiResourceScope.SYSTEM,
        null,
        "management:server-a"
    )).thenReturn(new RuntimeMcpEndpoint(
        "https://runtime.example.com/mcp",
        "workload-a",
        "lease-a",
        1L
    ));
    when(gatewayOperations.discover(any())).thenReturn(
        new McpGatewayDiscoveryResult(
            "2025-11-25",
            "fixture",
            "Fixture",
            "1.0.0",
            null,
            null,
            Map.of("tools", Map.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        )
    );
    when(invocationLedger.start(any(), any(), any(), any(), any()))
        .thenReturn("invocation-a");
    when(gatewayOperations.callTool(any())).thenReturn(
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "ok")),
            false,
            Map.of("message", "ok"),
            Map.of()
        )
    );

    service.discover("server-a");
    service.callTool(
        "server-a",
        new McpToolCallCommand("echo", Map.of("message", "hello"))
    );

    verify(runtimeMcpEndpointService, times(2)).resolve(
        "server-a",
        AiResourceScope.SYSTEM,
        null,
        "management:server-a"
    );
  }

  @Test
  void managedOauthInvocationCannotUseAnotherSubjectsConnection() {
    server.setDeploymentType(McpServerDeploymentType.MANAGED_OCI);
    server.setAuthenticationType(McpAuthenticationType.OAUTH2);
    server.setEnabled(true);
    server.setStatus(McpServerStatus.READY);
    when(snapshotRepository.findActiveById("snapshot-a"))
        .thenReturn(Optional.of(snapshot));
    when(invocationLedger.start(any(), any(), any(), any(), any()))
        .thenReturn("invocation-cross-user");
    when(providerConnectionRepository.findActiveByServerAndUser(
        "server-a", "user-a"
    )).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.callTool(
        "server-a",
        new McpToolCallCommand("echo", Map.of("message", "hello"))
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not authorized for subject");

    verify(invocationLedger).fail(
        org.mockito.ArgumentMatchers.eq("invocation-cross-user"),
        any(RuntimeException.class)
    );
    verify(runtimeMcpEndpointService, times(0)).resolve(
        any(), any(), any(), any()
    );
  }

  @Test
  void managedOauthAuthorizationRefusesAnExistingDifferentSubject() {
    server.setDeploymentType(McpServerDeploymentType.MANAGED_OCI);
    server.setAuthenticationType(McpAuthenticationType.OAUTH2);
    server.setEnabled(true);
    server.setOauthClientId("github-client");
    server.setOauthAuthorizationEndpoint(
        "https://github.com/login/oauth/authorize"
    );
    server.setOauthTokenEndpoint(
        "https://github.com/login/oauth/access_token"
    );
    AiMcpProviderConnection another = providerConnection("user-b");
    when(providerConnectionRepository.findActiveByServer("server-a"))
        .thenReturn(List.of(another));

    assertThatThrownBy(() -> service.startOauthAuthorization("server-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bound to another subject");
  }

  @Test
  void disconnectErasesSubjectTokensAndRollsManagedWorkloads() {
    server.setDeploymentType(McpServerDeploymentType.MANAGED_OCI);
    AiMcpProviderConnection connection = providerConnection("user-a");
    connection.setAccessTokenCiphertext("encrypted-access");
    connection.setRefreshTokenCiphertext("encrypted-refresh");
    connection.setTokenVersion(7L);
    when(providerConnectionRepository.findActiveByServerAndUser(
        "server-a", "user-a"
    )).thenReturn(Optional.of(connection));
    when(providerConnectionRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AiRuntimePool pool = new AiRuntimePool();
    pool.setId("pool-a");
    when(runtimePoolService.findByServer("server-a"))
        .thenReturn(Optional.of(pool));

    AiMcpProviderConnection disconnected = service.disconnectProvider(
        "server-a"
    );

    assertThat(disconnected.getAccessTokenCiphertext()).isNull();
    assertThat(disconnected.getRefreshTokenCiphertext()).isNull();
    assertThat(disconnected.getStatus()).isEqualTo(McpOauthStatus.CONFIGURED);
    assertThat(disconnected.getTokenVersion()).isEqualTo(8L);
    verify(runtimePoolService).redeploy("pool-a");
  }

  @Test
  void expiredManagedOauthTokenRefreshesBeforeWorkloadSelection() {
    server.setDeploymentType(McpServerDeploymentType.MANAGED_OCI);
    server.setAuthenticationType(McpAuthenticationType.OAUTH2);
    server.setEnabled(true);
    server.setStatus(McpServerStatus.READY);
    server.setOauthClientId("github-client");
    server.setCredentialCiphertext("encrypted-client-secret");
    server.setOauthTokenEndpoint(
        "https://github.com/login/oauth/access_token"
    );
    AiMcpProviderConnection connection = providerConnection("user-a");
    connection.setAccessTokenCiphertext("encrypted-old-access");
    connection.setRefreshTokenCiphertext("encrypted-refresh");
    connection.setAccessTokenExpiresAt(Instant.now().minusSeconds(1));
    connection.setTokenVersion(3L);
    when(providerConnectionRepository.findActiveByServerAndUser(
        "server-a", "user-a"
    )).thenReturn(Optional.of(connection));
    when(providerConnectionRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(credentialCipher.decrypt("encrypted-refresh"))
        .thenReturn("refresh-token");
    when(credentialCipher.decrypt("encrypted-client-secret"))
        .thenReturn("client-secret");
    when(credentialCipher.encrypt("new-access-token"))
        .thenReturn("encrypted-new-access");
    when(gatewayOperations.exchangeOauthToken(any())).thenReturn(
        new McpGatewayOauthTokenResult(
            "new-access-token", "Bearer", 3600, null, "repo read:user"
        )
    );
    AiRuntimePool pool = new AiRuntimePool();
    pool.setId("pool-a");
    when(runtimePoolService.findByServer("server-a"))
        .thenReturn(Optional.of(pool));
    when(snapshotRepository.findActiveById("snapshot-a"))
        .thenReturn(Optional.of(snapshot));
    when(invocationLedger.start(any(), any(), any(), any(), any()))
        .thenReturn("invocation-refresh");
    when(runtimeMcpEndpointService.resolve(
        "server-a",
        AiResourceScope.SYSTEM,
        null,
        "management:server-a"
    )).thenReturn(new RuntimeMcpEndpoint(
        "https://runtime.example.com/mcp",
        "workload-a",
        "lease-a",
        1L
    ));
    when(gatewayOperations.callTool(any())).thenReturn(
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "ok")),
            false,
            Map.of("message", "ok"),
            Map.of()
        )
    );

    service.callTool(
        "server-a",
        new McpToolCallCommand("echo", Map.of("message", "hello"))
    );

    assertThat(connection.getAccessTokenCiphertext())
        .isEqualTo("encrypted-new-access");
    assertThat(connection.getTokenVersion()).isEqualTo(4L);
    assertThat(connection.getStatus()).isEqualTo(McpOauthStatus.CONNECTED);
    verify(runtimePoolService).redeploy("pool-a");
    verify(runtimeMcpEndpointService).resolve(
        "server-a",
        AiResourceScope.SYSTEM,
        null,
        "management:server-a"
    );
  }

  private static AiMcpProviderConnection providerConnection(
      final String userId
  ) {
    AiMcpProviderConnection connection = new AiMcpProviderConnection();
    connection.setServerId("server-a");
    connection.setScopeType(AiResourceScope.SYSTEM);
    connection.setUserId(userId);
    connection.setStatus(McpOauthStatus.CONNECTED);
    return connection;
  }

}
