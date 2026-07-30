package org.simplepoint.plugin.ai.mcp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpOauthAuthorization;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnectionKind;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayEventType;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamException;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotSummary;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilityType;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthAuthorizationStart;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthCallbackCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpPromptGetCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpPublicationStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpResourceReadCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpToolCallCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpTransportType;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpCapabilitySnapshotRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpOauthAuthorizationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpPublicationRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpWorkflowExecutionService;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpInvocationLedger;
import org.simplepoint.plugin.ai.mcp.service.support.AiMcpSnapshotStore;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpEndpoint;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpEndpointService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP server control-plane service.
 */
@Service
public class AiMcpServerDefinitionServiceImpl
    extends BaseServiceImpl<AiMcpServerDefinitionRepository, AiMcpServerDefinition, String>
    implements AiMcpServerDefinitionService, AiMcpPublicationRuntimeService,
    AiMcpWorkflowExecutionService {

  private static final TypeReference<List<McpToolDescriptor>> TOOL_LIST_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<List<McpResourceDescriptor>> RESOURCE_LIST_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<List<McpResourceTemplateDescriptor>>
      RESOURCE_TEMPLATE_LIST_TYPE = new TypeReference<>() {
      };

  private static final TypeReference<List<McpPromptDescriptor>> PROMPT_LIST_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<Map<String, Object>> CAPABILITIES_MAP_TYPE =
      new TypeReference<>() {
      };

  private static final Duration OAUTH_STATE_LIFETIME = Duration.ofMinutes(10);

  private static final Duration OAUTH_EXPIRY_SKEW = Duration.ofSeconds(30);

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AiMcpServerDefinitionRepository repository;

  private final AiMcpCapabilitySnapshotRepository snapshotRepository;

  private final AiMcpOauthAuthorizationRepository oauthAuthorizationRepository;

  private final AiMcpPublicationRepository publicationRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiCredentialCipher credentialCipher;

  private final McpGatewayOperations gatewayOperations;

  private final AiMcpSnapshotStore snapshotStore;

  private final AiMcpInvocationLedger invocationLedger;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  private final AiRuntimeMcpEndpointService runtimeMcpEndpointService;

  private final AiRuntimePoolService runtimePoolService;

  /**
   * Creates the MCP server service.
   */
  public AiMcpServerDefinitionServiceImpl(
      final AiMcpServerDefinitionRepository repository,
      final DetailsProviderService detailsProviderService,
      final AiMcpCapabilitySnapshotRepository snapshotRepository,
      final AiMcpOauthAuthorizationRepository oauthAuthorizationRepository,
      final AiMcpPublicationRepository publicationRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiCredentialCipher credentialCipher,
      final McpGatewayOperations gatewayOperations,
      final AiMcpSnapshotStore snapshotStore,
      final AiMcpInvocationLedger invocationLedger,
      final ObjectMapper objectMapper,
      final AiRuntimeMcpEndpointService runtimeMcpEndpointService,
      final AiRuntimePoolService runtimePoolService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.snapshotRepository = snapshotRepository;
    this.oauthAuthorizationRepository = oauthAuthorizationRepository;
    this.publicationRepository = publicationRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.credentialCipher = credentialCipher;
    this.gatewayOperations = gatewayOperations;
    this.snapshotStore = snapshotStore;
    this.invocationLedger = invocationLedger;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    this.runtimeMcpEndpointService = runtimeMcpEndpointService;
    this.runtimePoolService = runtimePoolService;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public Optional<AiMcpServerDefinition> findActiveById(final String id) {
    Optional<AiMcpServerDefinition> server = repository.findActiveById(requireId(id));
    server.ifPresent(this::assertCanRead);
    return server.map(this::decorate);
  }

  @Override
  public <S extends AiMcpServerDefinition> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    normalized.put("scopeType", scope.scopeType().name());
    normalized.put("tenantId", scope.tenantId() == null ? "is:null" : scope.tenantId());
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    Page<S> page = super.limit(normalized, pageable);
    page.getContent().forEach(this::decorate);
    return page;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiMcpServerDefinition> S create(final S entity) {
    if (entity == null) {
      throw new IllegalArgumentException("MCP server must not be null");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    entity.setScopeType(scope.scopeType());
    entity.setTenantId(scope.tenantId());
    normalizeAndValidate(entity, null);
    String credential = encryptCredentialForCreate(entity);
    entity.setCredentialCiphertext(credential);
    entity.setBearerToken(null);
    entity.setEnabled(entity.getEnabled() == null ? Boolean.TRUE : entity.getEnabled());
    entity.setStatus(Boolean.TRUE.equals(entity.getEnabled())
        ? McpServerStatus.DRAFT : McpServerStatus.DISABLED);
    clearDiscoveryState(entity);
    clearOauthRuntimeState(entity);
    entity.setOauthStatus(entity.getAuthenticationType() == McpAuthenticationType.OAUTH2
        ? McpOauthStatus.CONFIGURED : McpOauthStatus.NOT_CONFIGURED);
    S saved = super.create(entity);
    return decorate(saved);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends AiMcpServerDefinition> AiMcpServerDefinition modifyById(final S entity) {
    AiMcpServerDefinition current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
    scopeAccessPolicy.assertCanManageOwnedResource(
        current.getScopeType(),
        current.getTenantId()
    );
    entity.setScopeType(current.getScopeType());
    entity.setTenantId(current.getTenantId());
    normalizeAndValidate(entity, current.getId());
    String credential = resolveUpdatedCredential(entity, current);
    entity.setCredentialCiphertext(credential);
    entity.setBearerToken(null);
    entity.setOauthClientSecret(null);
    if (oauthConfigurationChanged(current, entity, credential)) {
      clearOauthRuntimeState(entity);
      entity.setOauthStatus(entity.getAuthenticationType() == McpAuthenticationType.OAUTH2
          ? McpOauthStatus.CONFIGURED : McpOauthStatus.NOT_CONFIGURED);
    } else {
      copyOauthRuntimeState(current, entity);
    }
    entity.setProtocolVersion(current.getProtocolVersion());
    entity.setRemoteServerName(current.getRemoteServerName());
    entity.setRemoteServerVersion(current.getRemoteServerVersion());
    entity.setActiveSnapshotId(current.getActiveSnapshotId());
    entity.setLastDiscoveredAt(current.getLastDiscoveredAt());
    entity.setLastError(current.getLastError());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    entity.setStatus(Boolean.TRUE.equals(entity.getEnabled())
        ? staleStatus(current, entity) : McpServerStatus.DISABLED);
    AiMcpServerDefinition updated = (AiMcpServerDefinition) super.modifyById(entity);
    return decorate(updated);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    ids.forEach(id -> {
      AiMcpServerDefinition server = repository.findActiveById(requireId(id))
          .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
      scopeAccessPolicy.assertCanManageOwnedResource(
          server.getScopeType(),
          server.getTenantId()
      );
      if (!publicationRepository
          .findActiveByUpstreamServerId(server.getId()).isEmpty()) {
        throw new IllegalStateException(
            "Delete MCP publications before deleting their upstream server"
        );
      }
      runtimePoolService.findByServer(server.getId()).ifPresent(pool -> {
        throw new IllegalStateException(
            "Delete the managed Runtime pool before deleting its MCP server"
        );
      });
    });
    super.removeByIds(ids);
  }

  @Override
  public McpGatewayDiscoveryResult discover(final String id) {
    AiMcpServerDefinition server = requireUsableServer(id, false);
    try {
      McpGatewayDiscoveryResult discovery = executeGateway(
          server,
          null,
          gatewayOperations::discover
      );
      snapshotStore.saveDiscovery(server.getId(), discovery);
      return discovery;
    } catch (RuntimeException ex) {
      snapshotStore.saveFailure(server.getId(), ex.getMessage());
      throw ex;
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Page<McpCapabilitySnapshotSummary> listSnapshots(
      final String id,
      final Pageable pageable
  ) {
    AiMcpServerDefinition server = requireVisibleServer(id);
    return snapshotRepository.findAllActiveByServerId(
        server.getId(),
        pageable
    ).map(snapshot -> snapshotSummary(server, snapshot));
  }

  @Override
  @Transactional(readOnly = true)
  public McpCapabilitySnapshotDetails getSnapshot(
      final String id,
      final String snapshotId
  ) {
    AiMcpServerDefinition server = requireVisibleServer(id);
    AiMcpCapabilitySnapshot snapshot = snapshotRepository
        .findActiveByIdAndServerId(
            requireId(snapshotId),
            server.getId()
        )
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP capability snapshot does not exist"
        ));
    return new McpCapabilitySnapshotDetails(
        snapshot.getId(),
        snapshot.getServerId(),
        snapshot.getProtocolVersion(),
        snapshot.getRemoteServerName(),
        snapshot.getRemoteServerVersion(),
        snapshot.getSchemaHash(),
        snapshot.getDiscoveredAt(),
        snapshot.getId().equals(server.getActiveSnapshotId()),
        readCapabilities(snapshot),
        readTools(snapshot),
        readResources(snapshot),
        readResourceTemplates(snapshot),
        readPrompts(snapshot)
    );
  }

  @Override
  public List<McpToolDescriptor> listTools(final String id) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    return readTools(requireSnapshot(server));
  }

  @Override
  public List<McpResourceDescriptor> listResources(final String id) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    return readResources(requireSnapshot(server));
  }

  @Override
  public List<McpResourceTemplateDescriptor> listResourceTemplates(final String id) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    return readResourceTemplates(requireSnapshot(server));
  }

  @Override
  public McpGatewayResourceReadResult readResource(
      final String id,
      final McpResourceReadCommand command
  ) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    String uri = command == null
        ? null : normalizeResourceUri(command.uri());
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    boolean listed = readResources(snapshot).stream()
        .anyMatch(resource -> resource.uri().equals(uri));
    boolean templated = readResourceTemplates(snapshot).stream()
        .anyMatch(template -> matchesResourceTemplate(template.uriTemplate(), uri));
    if (!listed && !templated) {
      throw new IllegalArgumentException(
          "MCP resource is not part of the active snapshot: " + uri
      );
    }
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.RESOURCE,
        uri,
        Map.of("uri", uri)
    );
    try {
      McpGatewayResourceReadResult result = executeGateway(
          server,
          null,
          connection -> gatewayOperations.readResource(
              new McpGatewayResourceReadRequest(
                  connection,
                  uri,
                  Map.of(),
                  null
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public List<McpPromptDescriptor> listPrompts(final String id) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    return readPrompts(requireSnapshot(server));
  }

  @Override
  public McpGatewayPromptGetResult getPrompt(
      final String id,
      final McpPromptGetCommand command
  ) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    String name = command == null
        ? null : required(command.name(), "MCP prompt name must not be blank");
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    if (readPrompts(snapshot).stream().noneMatch(prompt -> prompt.name().equals(name))) {
      throw new IllegalArgumentException(
          "MCP prompt is not part of the active snapshot: " + name
      );
    }
    Map<String, Object> arguments = command.arguments() == null
        ? Map.of() : command.arguments();
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.PROMPT,
        name,
        arguments
    );
    try {
      McpGatewayPromptGetResult result = executeGateway(
          server,
          null,
          connection -> gatewayOperations.getPrompt(
              new McpGatewayPromptGetRequest(
                  connection,
                  name,
                  arguments,
                  Map.of(),
                  null
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayToolCallResult callTool(
      final String id,
      final McpToolCallCommand command
  ) {
    AiMcpServerDefinition server = requireUsableServer(id, true);
    if (command == null || command.toolName() == null || command.toolName().isBlank()) {
      throw new IllegalArgumentException("MCP tool name must not be blank");
    }
    String toolName = command.toolName().trim();
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    boolean discovered = readTools(snapshot).stream()
        .anyMatch(tool -> tool.name().equals(toolName));
    if (!discovered) {
      throw new IllegalArgumentException("MCP tool is not part of the active snapshot: " + toolName);
    }
    Map<String, Object> arguments = command.arguments() == null ? Map.of() : command.arguments();
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.TOOL,
        toolName,
        arguments
    );
    try {
      McpGatewayToolCallResult result = executeGateway(
          server,
          null,
          connection -> gatewayOperations.callTool(
              new McpGatewayToolCallRequest(
                  connection,
                  toolName,
                  arguments,
                  Map.of(),
                  null
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayToolCallResult callWorkflowTool(
      final McpWorkflowToolCallRequest request
  ) {
    WorkflowInvocation invocation = requireWorkflowInvocation(request);
    String toolName = required(
        request.toolName(),
        "MCP workflow Tool name must not be blank"
    );
    McpToolDescriptor tool = readTools(invocation.snapshot()).stream()
        .filter(candidate -> toolName.equals(candidate.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP workflow Tool is not part of the pinned snapshot: " + toolName
        ));
    String expectedSchemaHash = required(
        request.expectedInputSchemaHash(),
        "MCP workflow Tool input Schema hash must not be blank"
    );
    if (!expectedSchemaHash.equals(
        sha256(writeCanonicalJson(tool.inputSchema()))
    )) {
      throw new IllegalStateException(
          "Pinned MCP Tool input Schema hash does not match the Skill binding"
      );
    }
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : request.arguments();
    String invocationId = invocationLedger.start(
        invocation.server(),
        invocation.snapshot().getId(),
        McpCapabilityType.TOOL,
        toolName,
        arguments,
        request.subjectId(),
        "simplepoint-skill-workflow",
        invocation.executionId()
    );
    try {
      McpGatewayToolCallResult result = executeGateway(
          invocation.server(),
          invocation.executionId(),
          connection -> gatewayOperations.callWorkflowTool(
              new McpGatewayWorkflowToolCallRequest(
                  new McpGatewayToolCallRequest(
                      connection,
                      toolName,
                      arguments,
                      workflowMetadata(request, invocation),
                      invocation.operationId()
                  ),
                  invocation.capabilityToken()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayPromptGetResult getWorkflowPrompt(
      final McpWorkflowPromptGetRequest request
  ) {
    WorkflowInvocation invocation = requireWorkflowInvocation(request);
    String promptName = required(
        request.promptName(),
        "MCP workflow Prompt name must not be blank"
    );
    McpPromptDescriptor prompt = readPrompts(invocation.snapshot()).stream()
        .filter(candidate -> promptName.equals(candidate.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP workflow Prompt is not part of the pinned snapshot: "
                + promptName
        ));
    assertDescriptorHash(
        request.expectedDescriptorHash(),
        prompt,
        "Prompt"
    );
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : request.arguments();
    String invocationId = invocationLedger.start(
        invocation.server(),
        invocation.snapshot().getId(),
        McpCapabilityType.PROMPT,
        promptName,
        arguments,
        request.subjectId(),
        "simplepoint-skill-workflow",
        invocation.executionId()
    );
    try {
      McpGatewayPromptGetResult result = executeGateway(
          invocation.server(),
          invocation.executionId(),
          connection -> gatewayOperations.getWorkflowPrompt(
              new McpGatewayWorkflowPromptGetRequest(
                  new McpGatewayPromptGetRequest(
                      connection,
                      promptName,
                      arguments,
                      workflowMetadata(request, invocation),
                      invocation.operationId()
                  ),
                  invocation.capabilityToken()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayResourceReadResult readWorkflowResource(
      final McpWorkflowResourceReadRequest request
  ) {
    WorkflowInvocation invocation = requireWorkflowInvocation(request);
    String uri = normalizeResourceUri(request.uri());
    String selector = required(
        request.boundSelector(),
        "MCP workflow Resource selector must not be blank"
    );
    Object descriptor;
    if (request.resourceTemplate()) {
      McpResourceTemplateDescriptor template = readResourceTemplates(
          invocation.snapshot()
      ).stream()
          .filter(candidate -> selector.equals(candidate.uriTemplate()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP workflow Resource Template is not in the pinned snapshot"
          ));
      if (!matchesResourceTemplate(template.uriTemplate(), uri)) {
        throw new IllegalArgumentException(
            "MCP workflow Resource URI is outside the pinned template"
        );
      }
      descriptor = template;
    } else {
      McpResourceDescriptor resource = readResources(
          invocation.snapshot()
      ).stream()
          .filter(candidate -> selector.equals(candidate.uri()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP workflow Resource is not in the pinned snapshot"
          ));
      if (!selector.equals(uri)) {
        throw new IllegalArgumentException(
            "MCP workflow Resource URI does not match the pinned binding"
        );
      }
      descriptor = resource;
    }
    assertDescriptorHash(
        request.expectedDescriptorHash(),
        descriptor,
        "Resource"
    );
    Map<String, Object> payload = Map.of("uri", uri);
    String invocationId = invocationLedger.start(
        invocation.server(),
        invocation.snapshot().getId(),
        McpCapabilityType.RESOURCE,
        uri,
        payload,
        request.subjectId(),
        "simplepoint-skill-workflow",
        invocation.executionId()
    );
    try {
      McpGatewayResourceReadResult result = executeGateway(
          invocation.server(),
          invocation.executionId(),
          connection -> gatewayOperations.readWorkflowResource(
              new McpGatewayWorkflowResourceReadRequest(
                  new McpGatewayResourceReadRequest(
                      connection,
                      uri,
                      workflowMetadata(request, invocation),
                      invocation.operationId()
                  ),
                  invocation.capabilityToken()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  private WorkflowInvocation requireWorkflowInvocation(
      final McpWorkflowRequest request
  ) {
    if (request == null || request.invocationScope() == null) {
      throw new IllegalArgumentException(
          "MCP workflow request and scope must not be null"
      );
    }
    AiMcpServerDefinition server = repository.findActiveById(
        requireId(request.serverId())
    ).orElseThrow(() -> new IllegalArgumentException(
        "MCP workflow server does not exist"
    ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        server.getScopeType(),
        server.getTenantId(),
        request.invocationScope(),
        request.invocationTenantId()
    )) {
      throw new IllegalStateException(
          "MCP workflow server is outside the execution scope"
      );
    }
    if (!Boolean.TRUE.equals(server.getEnabled())
        || server.getStatus() != McpServerStatus.READY) {
      throw new IllegalStateException("MCP workflow server is not READY");
    }
    String snapshotId = required(
        request.snapshotId(),
        "MCP workflow capability snapshot must not be blank"
    );
    AiMcpCapabilitySnapshot snapshot = snapshotRepository
        .findActiveByIdAndServerId(snapshotId, server.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Pinned MCP capability snapshot does not exist"
        ));
    String executionId = required(
        request.executionId(),
        "Skill execution ID must not be blank"
    );
    String skillId = required(
        request.skillId(),
        "Skill ID must not be blank"
    );
    String skillVersionId = required(
        request.skillVersionId(),
        "Skill version ID must not be blank"
    );
    String stepId = required(
        request.stepId(),
        "Skill workflow step ID must not be blank"
    );
    String capabilityToken = required(
        request.capabilityToken(),
        "Skill capability token must not be blank"
    );
    return new WorkflowInvocation(
        server,
        snapshot,
        skillId,
        skillVersionId,
        executionId,
        stepId,
        executionId + ":" + stepId,
        capabilityToken
    );
  }

  private static Map<String, Object> workflowMetadata(
      final McpWorkflowRequest request,
      final WorkflowInvocation invocation
  ) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("simplepoint/scopeType", request.invocationScope().name());
    metadata.put("simplepoint/tenantId", request.invocationTenantId());
    metadata.put("simplepoint/skillId", invocation.skillId());
    metadata.put("simplepoint/skillVersionId", invocation.skillVersionId());
    metadata.put("simplepoint/skillExecutionId", invocation.executionId());
    metadata.put("simplepoint/skillStepId", invocation.stepId());
    metadata.put(
        "simplepoint/capabilitySnapshotId",
        invocation.snapshot().getId()
    );
    metadata.put("simplepoint/subjectId", request.subjectId());
    return metadata;
  }

  private void assertDescriptorHash(
      final String expected,
      final Object descriptor,
      final String type
  ) {
    String expectedHash = required(
        expected,
        "MCP workflow " + type + " descriptor hash must not be blank"
    );
    if (!expectedHash.equals(sha256(writeCanonicalJson(descriptor)))) {
      throw new IllegalStateException(
          "Pinned MCP " + type + " descriptor hash does not match "
              + "the Skill binding"
      );
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public McpOauthAuthorizationStart startOauthAuthorization(final String id) {
    AiMcpServerDefinition server = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
    scopeAccessPolicy.assertCanManageOwnedResource(
        server.getScopeType(),
        server.getTenantId()
    );
    if (!Boolean.TRUE.equals(server.getEnabled())) {
      throw new IllegalStateException("MCP server is disabled");
    }
    if (server.getAuthenticationType() != McpAuthenticationType.OAUTH2) {
      throw new IllegalStateException("MCP server is not configured for OAuth 2.1");
    }
    McpGatewayOauthDiscoveryResult discovery = gatewayOperations.discoverOauth(
        new McpGatewayOauthDiscoveryRequest(
            server.getEndpointUrl(),
            Boolean.TRUE.equals(server.getAllowPrivateNetwork())
        )
    );
    applyOauthDiscovery(server, discovery);
    ensureOauthClient(server, discovery);

    String state = randomUrlToken(32);
    String verifier = randomUrlToken(64);
    Instant expiresAt = Instant.now().plus(OAUTH_STATE_LIFETIME);
    AiMcpOauthAuthorization authorization = new AiMcpOauthAuthorization();
    authorization.setServerId(server.getId());
    authorization.setScopeType(server.getScopeType());
    authorization.setTenantId(server.getTenantId());
    authorization.setStateHash(sha256(state));
    authorization.setCodeVerifierCiphertext(credentialCipher.encrypt(verifier));
    authorization.setRedirectUri(server.getOauthRedirectUri());
    authorization.setExpiresAt(expiresAt);
    oauthAuthorizationRepository.save(authorization);

    server.setOauthStatus(McpOauthStatus.AUTHORIZATION_PENDING);
    server.setLastError(null);
    repository.save(server);
    return new McpOauthAuthorizationStart(
        authorizationUrl(server, state, pkceChallenge(verifier)),
        expiresAt
    );
  }

  @Override
  public AiMcpServerDefinition completeOauthAuthorization(
      final McpOauthCallbackCommand command
  ) {
    if (command == null) {
      throw new IllegalArgumentException("OAuth callback must not be null");
    }
    String state = required(command.state(), "OAuth state is missing");
    String stateHash = sha256(state);
    AiMcpOauthAuthorization authorization = oauthAuthorizationRepository
        .findActiveByStateHash(stateHash)
        .orElseThrow(() -> new IllegalArgumentException("OAuth state is invalid"));
    AiMcpServerDefinition server = repository.findActiveById(authorization.getServerId())
        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
    scopeAccessPolicy.assertCanManageOwnedResource(
        server.getScopeType(),
        server.getTenantId()
    );
    if (oauthAuthorizationRepository.consume(stateHash, Instant.now()) != 1) {
      throw new IllegalArgumentException("OAuth state is expired or has already been used");
    }
    if (command.error() != null && !command.error().isBlank()) {
      server.setOauthStatus(McpOauthStatus.ERROR);
      server.setLastError(truncateOauthError(command.error(), command.errorDescription()));
      return decorate(repository.save(server));
    }
    try {
      McpGatewayOauthTokenResult token = gatewayOperations.exchangeOauthToken(
          new McpGatewayOauthTokenRequest(
              server.getOauthTokenEndpoint(),
              "authorization_code",
              server.getOauthClientId(),
              credentialCipher.decrypt(server.getCredentialCiphertext()),
              server.getOauthTokenEndpointAuthMethod(),
              required(command.code(), "OAuth authorization code is missing"),
              credentialCipher.decrypt(authorization.getCodeVerifierCiphertext()),
              authorization.getRedirectUri(),
              null,
              server.getOauthResourceUri(),
              null,
              Boolean.TRUE.equals(server.getAllowPrivateNetwork())
          )
      );
      applyOauthToken(server, token, false);
      return decorate(repository.save(server));
    } catch (RuntimeException ex) {
      server.setOauthStatus(McpOauthStatus.ERROR);
      server.setLastError(truncateOauthError("token_exchange_failed", ex.getMessage()));
      repository.save(server);
      throw ex;
    }
  }

  @Override
  public McpPublicationManifest manifest(final String code) {
    AiMcpPublication publication = requirePublished(code);
    AiMcpServerDefinition server = requirePublicationServer(publication);
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    return new McpPublicationManifest(
        publication.getCode(),
        publication.getName(),
        publication.getDescription(),
        publication.getCanonicalResourceUri(),
        publication.getAuthorizationServerUri(),
        scopes(publication.getRequiredScopes()),
        publication.getRateLimitPerMinute(),
        server.getId(),
        snapshot.getId(),
        snapshot.getProtocolVersion(),
        readTools(snapshot),
        readResources(snapshot),
        readResourceTemplates(snapshot),
        readPrompts(snapshot)
    );
  }

  @Override
  public McpGatewayToolCallResult callPublishedTool(
      final McpPublicationToolCallRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("MCP publication tool call must not be null");
    }
    AiMcpPublication publication = requirePublished(request.publicationCode());
    AiMcpServerDefinition server = requirePublicationServer(publication);
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    String toolName = required(request.toolName(), "MCP tool name must not be blank");
    if (readTools(snapshot).stream().noneMatch(tool -> tool.name().equals(toolName))) {
      throw new IllegalArgumentException(
          "MCP tool is not part of the published snapshot: " + toolName
      );
    }
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : request.arguments();
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.TOOL,
        toolName,
        arguments,
        request.subject(),
        request.clientId(),
        request.sessionId()
    );
    try {
      McpGatewayToolCallResult result = executeGateway(
          server,
          request.sessionId(),
          connection -> gatewayOperations.callTool(
              new McpGatewayToolCallRequest(
                  connection,
                  toolName,
                  arguments,
                  progressMeta(request.operationId()),
                  request.operationId()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayResourceReadResult readPublishedResource(
      final McpPublicationResourceReadRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("MCP publication resource read must not be null");
    }
    AiMcpPublication publication = requirePublished(request.publicationCode());
    AiMcpServerDefinition server = requirePublicationServer(publication);
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    String uri = normalizeResourceUri(request.uri());
    boolean listed = readResources(snapshot).stream()
        .anyMatch(resource -> resource.uri().equals(uri));
    boolean templated = readResourceTemplates(snapshot).stream()
        .anyMatch(template -> matchesResourceTemplate(template.uriTemplate(), uri));
    if (!listed && !templated) {
      throw new IllegalArgumentException(
          "MCP resource is not part of the published snapshot: " + uri
      );
    }
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.RESOURCE,
        uri,
        Map.of("uri", uri),
        request.subject(),
        request.clientId(),
        request.sessionId()
    );
    try {
      McpGatewayResourceReadResult result = executeGateway(
          server,
          request.sessionId(),
          connection -> gatewayOperations.readResource(
              new McpGatewayResourceReadRequest(
                  connection,
                  uri,
                  progressMeta(request.operationId()),
                  request.operationId()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpGatewayPromptGetResult getPublishedPrompt(
      final McpPublicationPromptGetRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("MCP publication prompt get must not be null");
    }
    AiMcpPublication publication = requirePublished(request.publicationCode());
    AiMcpServerDefinition server = requirePublicationServer(publication);
    AiMcpCapabilitySnapshot snapshot = requireSnapshot(server);
    String promptName = required(
        request.promptName(),
        "MCP prompt name must not be blank"
    );
    if (readPrompts(snapshot).stream()
        .noneMatch(prompt -> prompt.name().equals(promptName))) {
      throw new IllegalArgumentException(
          "MCP prompt is not part of the published snapshot: " + promptName
      );
    }
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : request.arguments();
    String invocationId = invocationLedger.start(
        server,
        snapshot.getId(),
        McpCapabilityType.PROMPT,
        promptName,
        arguments,
        request.subject(),
        request.clientId(),
        request.sessionId()
    );
    try {
      McpGatewayPromptGetResult result = executeGateway(
          server,
          request.sessionId(),
          connection -> gatewayOperations.getPrompt(
              new McpGatewayPromptGetRequest(
                  connection,
                  promptName,
                  arguments,
                  progressMeta(request.operationId()),
                  request.operationId()
              )
          )
      );
      invocationLedger.succeed(invocationId, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.fail(invocationId, ex);
      throw ex;
    }
  }

  @Override
  public McpPublicationChangeEvent handleUpstreamEvent(
      final McpGatewayUpstreamEvent event
  ) {
    if (event == null || event.connectionId() == null
        || event.connectionId().isBlank()) {
      throw new IllegalArgumentException("MCP upstream event connection is missing");
    }
    Set<McpGatewayEventType> eventTypes = event.eventTypes() == null
        ? Set.of() : Set.copyOf(event.eventTypes());
    if (eventTypes.isEmpty()) {
      throw new IllegalArgumentException("MCP upstream event type is missing");
    }
    AiMcpServerDefinition server = repository
        .findActiveById(event.connectionId().trim())
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP upstream event server does not exist"
        ));
    if (!Boolean.TRUE.equals(server.getEnabled())) {
      throw new IllegalStateException("MCP upstream event server is disabled");
    }
    boolean listChanged = eventTypes.contains(McpGatewayEventType.TOOLS_LIST_CHANGED)
        || eventTypes.contains(McpGatewayEventType.RESOURCES_LIST_CHANGED)
        || eventTypes.contains(McpGatewayEventType.PROMPTS_LIST_CHANGED);
    if (listChanged) {
      if (event.discovery() == null) {
        throw new IllegalArgumentException(
            "MCP list change event requires a complete discovery result"
        );
      }
      snapshotStore.saveDiscovery(server.getId(), event.discovery());
    }
    List<String> publicationCodes = publicationRepository
        .findActiveByUpstreamServerId(server.getId())
        .stream()
        .filter(publication -> Boolean.TRUE.equals(publication.getEnabled()))
        .filter(publication ->
            publication.getStatus() == McpPublicationStatus.PUBLISHED)
        .map(AiMcpPublication::getCode)
        .sorted()
        .toList();
    List<String> resourceUris = event.resourceUris() == null
        ? List.of() : event.resourceUris().stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(uri -> !uri.isEmpty())
            .distinct()
            .limit(1000)
            .toList();
    return new McpPublicationChangeEvent(
        publicationCodes,
        eventTypes,
        resourceUris
    );
  }

  private AiMcpPublication requirePublished(final String code) {
    AiMcpPublication publication = publicationRepository.findActiveByCode(
        required(code, "MCP publication code must not be blank")
    ).orElseThrow(() -> new IllegalArgumentException(
        "MCP publication does not exist"
    ));
    if (!Boolean.TRUE.equals(publication.getEnabled())
        || publication.getStatus() != McpPublicationStatus.PUBLISHED) {
      throw new IllegalStateException("MCP publication is disabled");
    }
    return publication;
  }

  private AiMcpServerDefinition requirePublicationServer(
      final AiMcpPublication publication
  ) {
    AiMcpServerDefinition server = repository
        .findActiveById(publication.getUpstreamServerId())
        .orElseThrow(() -> new IllegalStateException(
            "MCP publication upstream server does not exist"
        ));
    if (server.getScopeType() != publication.getScopeType()
        || !java.util.Objects.equals(server.getTenantId(), publication.getTenantId())) {
      throw new IllegalStateException("MCP publication scope is invalid");
    }
    if (!Boolean.TRUE.equals(server.getEnabled())
        || server.getStatus() != McpServerStatus.READY) {
      throw new IllegalStateException("MCP publication upstream server is not READY");
    }
    return server;
  }

  private AiMcpServerDefinition requireUsableServer(
      final String id,
      final boolean requireReady
  ) {
    AiMcpServerDefinition server = requireVisibleServer(id);
    if (!Boolean.TRUE.equals(server.getEnabled())) {
      throw new IllegalStateException("MCP server is disabled");
    }
    if (requireReady && server.getStatus() != McpServerStatus.READY) {
      throw new IllegalStateException("MCP server has no active READY capability snapshot");
    }
    return server;
  }

  private AiMcpServerDefinition requireVisibleServer(final String id) {
    AiMcpServerDefinition server = repository.findActiveById(requireId(id))
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP server does not exist"
        ));
    assertCanRead(server);
    return server;
  }

  private AiMcpCapabilitySnapshot requireSnapshot(final AiMcpServerDefinition server) {
    if (server.getActiveSnapshotId() == null || server.getActiveSnapshotId().isBlank()) {
      throw new IllegalStateException("MCP server has no active capability snapshot");
    }
    AiMcpCapabilitySnapshot snapshot = snapshotRepository
        .findActiveById(server.getActiveSnapshotId())
        .orElseThrow(() -> new IllegalStateException("MCP capability snapshot does not exist"));
    if (!server.getId().equals(snapshot.getServerId())) {
      throw new IllegalStateException("MCP capability snapshot ownership is invalid");
    }
    return snapshot;
  }

  private List<McpToolDescriptor> readTools(final AiMcpCapabilitySnapshot snapshot) {
    try {
      return objectMapper.readValue(snapshot.getToolsJson(), TOOL_LIST_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("MCP capability snapshot is corrupted", ex);
    }
  }

  private Map<String, Object> readCapabilities(
      final AiMcpCapabilitySnapshot snapshot
  ) {
    String json = snapshot.getCapabilitiesJson();
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> capabilities =
          objectMapper.readValue(json, CAPABILITIES_MAP_TYPE);
      return capabilities == null
          ? Map.of() : new LinkedHashMap<>(capabilities);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "MCP capability snapshot is corrupted",
          ex
      );
    }
  }

  private McpCapabilitySnapshotSummary snapshotSummary(
      final AiMcpServerDefinition server,
      final AiMcpCapabilitySnapshot snapshot
  ) {
    return new McpCapabilitySnapshotSummary(
        snapshot.getId(),
        snapshot.getServerId(),
        snapshot.getProtocolVersion(),
        snapshot.getRemoteServerName(),
        snapshot.getRemoteServerVersion(),
        snapshot.getSchemaHash(),
        snapshot.getDiscoveredAt(),
        snapshot.getId().equals(server.getActiveSnapshotId()),
        readTools(snapshot).size(),
        readResources(snapshot).size(),
        readResourceTemplates(snapshot).size(),
        readPrompts(snapshot).size()
    );
  }

  private List<McpResourceDescriptor> readResources(
      final AiMcpCapabilitySnapshot snapshot
  ) {
    return readSnapshotList(
        snapshot.getResourcesJson(),
        RESOURCE_LIST_TYPE,
        "resources"
    );
  }

  private List<McpResourceTemplateDescriptor> readResourceTemplates(
      final AiMcpCapabilitySnapshot snapshot
  ) {
    return readSnapshotList(
        snapshot.getResourceTemplatesJson(),
        RESOURCE_TEMPLATE_LIST_TYPE,
        "resource templates"
    );
  }

  private List<McpPromptDescriptor> readPrompts(
      final AiMcpCapabilitySnapshot snapshot
  ) {
    return readSnapshotList(snapshot.getPromptsJson(), PROMPT_LIST_TYPE, "prompts");
  }

  private <T> List<T> readSnapshotList(
      final String json,
      final TypeReference<List<T>> type,
      final String capability
  ) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "MCP " + capability + " snapshot is corrupted",
          ex
      );
    }
  }

  private <T> T executeGateway(
      final AiMcpServerDefinition server,
      final String sessionId,
      final Function<McpGatewayConnection, T> operation
  ) {
    GatewayConnection selected = connection(server, sessionId);
    try {
      return operation.apply(selected.connection());
    } catch (McpGatewayUpstreamException ex) {
      if (ex.getStatusCode() == 502 && selected.runtimeEndpoint() != null) {
        try {
          runtimeMcpEndpointService.invalidate(
              server.getId(),
              server.getScopeType(),
              server.getTenantId(),
              sessionId,
              selected.runtimeEndpoint()
          );
        } catch (RuntimeException invalidationFailure) {
          ex.addSuppressed(invalidationFailure);
        }
      }
      throw ex;
    }
  }

  private GatewayConnection connection(
      final AiMcpServerDefinition server,
      final String sessionId
  ) {
    if (server.getDeploymentType() == McpServerDeploymentType.MANAGED_OCI) {
      RuntimeMcpEndpoint endpoint = runtimeMcpEndpointService.resolve(
          server.getId(),
          server.getScopeType(),
          server.getTenantId(),
          sessionId
      );
      return new GatewayConnection(
          new McpGatewayConnection(
              server.getId(),
              endpoint.endpointUrl(),
              null,
              true,
              McpGatewayConnectionKind.MANAGED_RUNTIME,
              endpoint.leaseId(),
              endpoint.fencingToken()
          ),
          endpoint
      );
    }
    String authorization = null;
    if (server.getAuthenticationType() == McpAuthenticationType.BEARER) {
      String token = credentialCipher.decrypt(server.getCredentialCiphertext());
      if (token == null || token.isBlank()) {
        throw new IllegalStateException("MCP Bearer credential is missing");
      }
      authorization = "Bearer " + token;
    } else if (server.getAuthenticationType() == McpAuthenticationType.OAUTH2) {
      authorization = oauthAuthorizationHeader(server);
    }
    return new GatewayConnection(
        new McpGatewayConnection(
            server.getId(),
            server.getEndpointUrl(),
            authorization,
            Boolean.TRUE.equals(server.getAllowPrivateNetwork())
        ),
        null
    );
  }

  private record GatewayConnection(
      McpGatewayConnection connection,
      RuntimeMcpEndpoint runtimeEndpoint
  ) {
  }

  private static Map<String, Object> progressMeta(final String operationId) {
    if (operationId == null || operationId.isBlank()) {
      return Map.of();
    }
    return Map.of("progressToken", operationId.trim());
  }

  private void normalizeAndValidate(
      final AiMcpServerDefinition entity,
      final String currentId
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("MCP server must not be null");
    }
    entity.setName(required(entity.getName(), "MCP server name must not be blank"));
    entity.setCode(normalizeCode(entity.getCode()));
    entity.setDeploymentType(entity.getDeploymentType() == null
        ? McpServerDeploymentType.REMOTE : entity.getDeploymentType());
    if (entity.getDeploymentType() == McpServerDeploymentType.MANAGED_OCI) {
      entity.setTransportType(entity.getTransportType() == null
          ? McpTransportType.STDIO : entity.getTransportType());
      if (entity.getTransportType() != McpTransportType.STDIO) {
        throw new IllegalArgumentException(
            "Managed OCI MCP servers must use stdio transport"
        );
      }
      entity.setEndpointUrl(null);
      entity.setAuthenticationType(McpAuthenticationType.NONE);
      entity.setAllowPrivateNetwork(false);
    } else {
      entity.setTransportType(entity.getTransportType() == null
          ? McpTransportType.STREAMABLE_HTTP : entity.getTransportType());
      if (entity.getTransportType() != McpTransportType.STREAMABLE_HTTP) {
        throw new IllegalArgumentException(
            "Remote MCP servers must use Streamable HTTP"
        );
      }
      entity.setEndpointUrl(validateEndpoint(entity.getEndpointUrl()));
      entity.setAuthenticationType(entity.getAuthenticationType() == null
          ? McpAuthenticationType.NONE : entity.getAuthenticationType());
    }
    normalizeOauthConfiguration(entity);
    if (entity.getScopeType() != AiResourceScope.SYSTEM
        && Boolean.TRUE.equals(entity.getAllowPrivateNetwork())) {
      throw new IllegalArgumentException("Tenant MCP servers cannot access private networks");
    }
    entity.setAllowPrivateNetwork(Boolean.TRUE.equals(entity.getAllowPrivateNetwork()));
    entity.setDescription(trimToNull(entity.getDescription()));
    repository.findActiveByCodeAndScope(
        entity.getCode(),
        entity.getScopeType(),
        entity.getTenantId()
    ).ifPresent(existing -> {
      if (!existing.getId().equals(currentId)) {
        throw new IllegalArgumentException("MCP server code already exists: " + entity.getCode());
      }
    });
  }

  private String encryptCredentialForCreate(final AiMcpServerDefinition entity) {
    if (entity.getAuthenticationType() == McpAuthenticationType.NONE) {
      return null;
    }
    if (entity.getAuthenticationType() == McpAuthenticationType.OAUTH2) {
      return credentialCipher.encrypt(trimToNull(entity.getOauthClientSecret()));
    }
    String bearerToken = trimToNull(entity.getBearerToken());
    if (bearerToken == null) {
      throw new IllegalArgumentException("Bearer token must be provided");
    }
    return credentialCipher.encrypt(bearerToken);
  }

  private String resolveUpdatedCredential(
      final AiMcpServerDefinition entity,
      final AiMcpServerDefinition current
  ) {
    if (entity.getAuthenticationType() == McpAuthenticationType.NONE) {
      return null;
    }
    if (entity.getAuthenticationType() == McpAuthenticationType.OAUTH2) {
      String clientSecret = trimToNull(entity.getOauthClientSecret());
      if (clientSecret != null) {
        return credentialCipher.encrypt(clientSecret);
      }
      if (current.getAuthenticationType() == McpAuthenticationType.OAUTH2) {
        return current.getCredentialCiphertext();
      }
      return null;
    }
    String token = trimToNull(entity.getBearerToken());
    if (token != null) {
      return credentialCipher.encrypt(token);
    }
    if (current.getAuthenticationType() == McpAuthenticationType.BEARER
        && current.getCredentialCiphertext() != null) {
      return current.getCredentialCiphertext();
    }
    throw new IllegalArgumentException("Bearer token must be provided");
  }

  private static McpServerStatus staleStatus(
      final AiMcpServerDefinition current,
      final AiMcpServerDefinition updated
  ) {
    boolean connectionChanged =
        current.getDeploymentType() != updated.getDeploymentType()
            || !java.util.Objects.equals(
                current.getEndpointUrl(),
                updated.getEndpointUrl()
            )
            || current.getTransportType() != updated.getTransportType()
            || current.getAuthenticationType() != updated.getAuthenticationType()
            || !java.util.Objects.equals(
                current.getCredentialCiphertext(),
                updated.getCredentialCiphertext()
            )
            || !java.util.Objects.equals(
                current.getAllowPrivateNetwork(),
                updated.getAllowPrivateNetwork()
            );
    return connectionChanged ? McpServerStatus.DRAFT : current.getStatus();
  }

  private static void clearDiscoveryState(final AiMcpServerDefinition entity) {
    entity.setProtocolVersion(null);
    entity.setRemoteServerName(null);
    entity.setRemoteServerVersion(null);
    entity.setActiveSnapshotId(null);
    entity.setLastDiscoveredAt(null);
    entity.setLastError(null);
  }

  private <S extends AiMcpServerDefinition> S decorate(final S entity) {
    entity.setHasCredential(
        entity.getCredentialCiphertext() != null && !entity.getCredentialCiphertext().isBlank()
    );
    entity.setBearerToken(null);
    entity.setOauthClientSecret(null);
    entity.setHasOauthToken(
        entity.getOauthAccessTokenCiphertext() != null
            && !entity.getOauthAccessTokenCiphertext().isBlank()
    );
    return entity;
  }

  private void normalizeOauthConfiguration(final AiMcpServerDefinition entity) {
    if (entity.getAuthenticationType() != McpAuthenticationType.OAUTH2) {
      entity.setOauthClientId(null);
      entity.setOauthClientSecret(null);
      entity.setOauthRedirectUri(null);
      entity.setOauthScopes(null);
      entity.setOauthTokenEndpointAuthMethod(null);
      return;
    }
    entity.setOauthClientId(trimToNull(entity.getOauthClientId()));
    entity.setOauthRedirectUri(validateOauthRedirectUri(entity.getOauthRedirectUri()));
    entity.setOauthScopes(normalizeScopes(entity.getOauthScopes()));
    String authMethod = trimToNull(entity.getOauthTokenEndpointAuthMethod());
    if (authMethod == null) {
      authMethod = trimToNull(entity.getOauthClientSecret()) == null
          ? "none" : "client_secret_post";
    }
    if (!Set.of("none", "client_secret_post", "client_secret_basic").contains(authMethod)) {
      throw new IllegalArgumentException(
          "OAuth token endpoint authentication method is not supported"
      );
    }
    entity.setOauthTokenEndpointAuthMethod(authMethod);
  }

  private void applyOauthDiscovery(
      final AiMcpServerDefinition server,
      final McpGatewayOauthDiscoveryResult discovery
  ) {
    server.setOauthResourceUri(discovery.resource());
    server.setOauthAuthorizationServer(discovery.authorizationServer());
    server.setOauthAuthorizationEndpoint(discovery.authorizationEndpoint());
    server.setOauthTokenEndpoint(discovery.tokenEndpoint());
    server.setOauthRegistrationEndpoint(discovery.registrationEndpoint());
    server.setOauthProtectedResourceMetadata(writeJson(
        discovery.protectedResourceMetadata()
    ));
    server.setOauthAuthorizationServerMetadata(writeJson(
        discovery.authorizationServerMetadata()
    ));
  }

  private void ensureOauthClient(
      final AiMcpServerDefinition server,
      final McpGatewayOauthDiscoveryResult discovery
  ) {
    if (server.getOauthClientId() != null && !server.getOauthClientId().isBlank()) {
      if (server.getOauthTokenEndpointAuthMethod() == null) {
        server.setOauthTokenEndpointAuthMethod(
            server.getCredentialCiphertext() == null ? "none" : "client_secret_post"
        );
      }
      return;
    }
    if (discovery.clientIdMetadataDocumentSupported()
        && discovery.clientIdMetadataDocumentUri() != null
        && discovery.clientIdMetadataRedirectUris().contains(
            server.getOauthRedirectUri()
        )) {
      server.setOauthClientId(discovery.clientIdMetadataDocumentUri());
      server.setOauthTokenEndpointAuthMethod("none");
      server.setCredentialCiphertext(null);
      return;
    }
    if (discovery.registrationEndpoint() == null) {
      throw new IllegalStateException(
          "OAuth client ID is missing and neither a compatible Client ID Metadata "
              + "Document nor dynamic client registration is available"
      );
    }
    McpGatewayOauthRegistrationResult registration =
        gatewayOperations.registerOauthClient(
            new McpGatewayOauthRegistrationRequest(
                discovery.registrationEndpoint(),
                List.of(server.getOauthRedirectUri()),
                "Open SimplePoint MCP Gateway",
                scopes(server.getOauthScopes()),
                Boolean.TRUE.equals(server.getAllowPrivateNetwork())
            )
        );
    server.setOauthClientId(registration.clientId());
    server.setCredentialCiphertext(credentialCipher.encrypt(registration.clientSecret()));
    server.setOauthTokenEndpointAuthMethod(registration.tokenEndpointAuthMethod());
  }

  private String oauthAuthorizationHeader(final AiMcpServerDefinition server) {
    if (server.getOauthAccessTokenCiphertext() == null) {
      throw new IllegalStateException("MCP OAuth authorization is required");
    }
    Instant expiresAt = server.getOauthAccessTokenExpiresAt();
    if (expiresAt != null && !expiresAt.isAfter(Instant.now().plus(OAUTH_EXPIRY_SKEW))) {
      refreshOauthToken(server);
    }
    String accessToken = credentialCipher.decrypt(server.getOauthAccessTokenCiphertext());
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException("MCP OAuth access token is missing");
    }
    return "Bearer " + accessToken;
  }

  private void refreshOauthToken(final AiMcpServerDefinition server) {
    String refreshToken = credentialCipher.decrypt(server.getOauthRefreshTokenCiphertext());
    if (refreshToken == null || refreshToken.isBlank()) {
      server.setOauthStatus(McpOauthStatus.EXPIRED);
      repository.save(server);
      throw new IllegalStateException("MCP OAuth authorization has expired");
    }
    try {
      McpGatewayOauthTokenResult token = gatewayOperations.exchangeOauthToken(
          new McpGatewayOauthTokenRequest(
              server.getOauthTokenEndpoint(),
              "refresh_token",
              server.getOauthClientId(),
              credentialCipher.decrypt(server.getCredentialCiphertext()),
              server.getOauthTokenEndpointAuthMethod(),
              null,
              null,
              null,
              refreshToken,
              server.getOauthResourceUri(),
              server.getOauthScopes(),
              Boolean.TRUE.equals(server.getAllowPrivateNetwork())
          )
      );
      applyOauthToken(server, token, true);
      repository.save(server);
    } catch (RuntimeException ex) {
      server.setOauthStatus(McpOauthStatus.ERROR);
      server.setLastError(truncateOauthError("token_refresh_failed", ex.getMessage()));
      repository.save(server);
      throw ex;
    }
  }

  private void applyOauthToken(
      final AiMcpServerDefinition server,
      final McpGatewayOauthTokenResult token,
      final boolean preserveRefreshToken
  ) {
    server.setOauthAccessTokenCiphertext(credentialCipher.encrypt(token.accessToken()));
    if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
      server.setOauthRefreshTokenCiphertext(
          credentialCipher.encrypt(token.refreshToken())
      );
    } else if (!preserveRefreshToken) {
      server.setOauthRefreshTokenCiphertext(null);
    }
    server.setOauthAccessTokenExpiresAt(token.expiresIn() > 0
        ? Instant.now().plusSeconds(token.expiresIn()) : null);
    if (token.scope() != null && !token.scope().isBlank()) {
      server.setOauthScopes(normalizeScopes(token.scope()));
    }
    server.setOauthAuthorizedAt(Instant.now());
    server.setOauthStatus(McpOauthStatus.CONNECTED);
    server.setLastError(null);
  }

  private static boolean oauthConfigurationChanged(
      final AiMcpServerDefinition current,
      final AiMcpServerDefinition updated,
      final String updatedCredential
  ) {
    return current.getAuthenticationType() != updated.getAuthenticationType()
        || !java.util.Objects.equals(current.getOauthClientId(), updated.getOauthClientId())
        || !java.util.Objects.equals(
            current.getOauthRedirectUri(),
            updated.getOauthRedirectUri()
        )
        || !java.util.Objects.equals(current.getOauthScopes(), updated.getOauthScopes())
        || !java.util.Objects.equals(
            current.getOauthTokenEndpointAuthMethod(),
            updated.getOauthTokenEndpointAuthMethod()
        )
        || !java.util.Objects.equals(
            current.getCredentialCiphertext(),
            updatedCredential
        );
  }

  private static void copyOauthRuntimeState(
      final AiMcpServerDefinition current,
      final AiMcpServerDefinition updated
  ) {
    updated.setOauthStatus(current.getOauthStatus());
    updated.setOauthAccessTokenCiphertext(current.getOauthAccessTokenCiphertext());
    updated.setOauthRefreshTokenCiphertext(current.getOauthRefreshTokenCiphertext());
    updated.setOauthResourceUri(current.getOauthResourceUri());
    updated.setOauthAuthorizationServer(current.getOauthAuthorizationServer());
    updated.setOauthAuthorizationEndpoint(current.getOauthAuthorizationEndpoint());
    updated.setOauthTokenEndpoint(current.getOauthTokenEndpoint());
    updated.setOauthRegistrationEndpoint(current.getOauthRegistrationEndpoint());
    updated.setOauthProtectedResourceMetadata(
        current.getOauthProtectedResourceMetadata()
    );
    updated.setOauthAuthorizationServerMetadata(
        current.getOauthAuthorizationServerMetadata()
    );
    updated.setOauthAccessTokenExpiresAt(current.getOauthAccessTokenExpiresAt());
    updated.setOauthAuthorizedAt(current.getOauthAuthorizedAt());
  }

  private static void clearOauthRuntimeState(final AiMcpServerDefinition entity) {
    entity.setOauthAccessTokenCiphertext(null);
    entity.setOauthRefreshTokenCiphertext(null);
    entity.setOauthResourceUri(null);
    entity.setOauthAuthorizationServer(null);
    entity.setOauthAuthorizationEndpoint(null);
    entity.setOauthTokenEndpoint(null);
    entity.setOauthRegistrationEndpoint(null);
    entity.setOauthProtectedResourceMetadata(null);
    entity.setOauthAuthorizationServerMetadata(null);
    entity.setOauthAccessTokenExpiresAt(null);
    entity.setOauthAuthorizedAt(null);
  }

  private String authorizationUrl(
      final AiMcpServerDefinition server,
      final String state,
      final String challenge
  ) {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("response_type", "code");
    parameters.put("client_id", server.getOauthClientId());
    parameters.put("redirect_uri", server.getOauthRedirectUri());
    parameters.put("state", state);
    parameters.put("code_challenge", challenge);
    parameters.put("code_challenge_method", "S256");
    parameters.put("resource", server.getOauthResourceUri());
    if (server.getOauthScopes() != null) {
      parameters.put("scope", server.getOauthScopes());
    }
    String query = parameters.entrySet().stream()
        .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
    String separator = server.getOauthAuthorizationEndpoint().contains("?") ? "&" : "?";
    return server.getOauthAuthorizationEndpoint() + separator + query;
  }

  private String writeJson(final Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize MCP OAuth metadata", ex);
    }
  }

  private String writeCanonicalJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Unable to serialize MCP capability Schema",
          ex
      );
    }
  }

  private static String validateOauthRedirectUri(final String value) {
    String normalized = required(value, "OAuth redirect URI must not be blank");
    try {
      URI uri = URI.create(normalized);
      String scheme = uri.getScheme() == null
          ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      boolean localhost = uri.getHost() != null
          && ("localhost".equalsIgnoreCase(uri.getHost())
          || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".localhost"));
      if ((!localhost && !"https".equals(scheme))
          || (localhost && !"https".equals(scheme) && !"http".equals(scheme))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("invalid");
      }
      return uri.toString();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "OAuth redirect URI must use HTTPS, except for localhost development",
          ex
      );
    }
  }

  private static String normalizeScopes(final String value) {
    List<String> scopes = scopes(value);
    return scopes.isEmpty() ? null : String.join(" ", scopes);
  }

  private static List<String> scopes(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(value.trim().split("\\s+"))
        .filter(scope -> scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+"))
        .distinct()
        .toList();
  }

  private static String randomUrlToken(final int bytes) {
    byte[] value = new byte[bytes];
    SECURE_RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String pkceChallenge(final String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String urlEncode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String truncateOauthError(
      final String error,
      final String description
  ) {
    String combined = required(error, "oauth_error")
        + (description == null || description.isBlank() ? "" : ": " + description);
    String normalized = combined.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
  }

  private void assertCanRead(final AiMcpServerDefinition server) {
    scopeAccessPolicy.assertCanReadManagedResource(
        server.getScopeType(),
        server.getTenantId()
    );
  }

  private static String validateEndpoint(final String value) {
    String normalized = required(value, "MCP endpoint must not be blank");
    try {
      URI uri = URI.create(normalized);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if ((!"http".equals(scheme) && !"https".equals(scheme))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("invalid");
      }
      return uri.toString();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "MCP endpoint must be an absolute http or https URI without credentials, query, or fragment",
          ex
      );
    }
  }

  private static String normalizeResourceUri(final String value) {
    String normalized = required(value, "MCP resource URI must not be blank");
    if (normalized.length() > 4096 || normalized.contains("\r")
        || normalized.contains("\n")) {
      throw new IllegalArgumentException("MCP resource URI is invalid");
    }
    try {
      URI.create(normalized);
      return normalized;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("MCP resource URI is invalid", ex);
    }
  }

  private static boolean matchesResourceTemplate(
      final String template,
      final String resourceUri
  ) {
    if (template == null || template.isBlank()) {
      return false;
    }
    StringBuilder expression = new StringBuilder("^");
    int cursor = 0;
    java.util.regex.Matcher matcher = Pattern.compile("\\{([^{}]+)}").matcher(template);
    while (matcher.find()) {
      expression.append(Pattern.quote(template.substring(cursor, matcher.start())));
      String variable = matcher.group(1);
      boolean reservedExpansion = variable.startsWith("+");
      String name = reservedExpansion ? variable.substring(1) : variable;
      if (!name.matches("[A-Za-z0-9_.-]+")) {
        return false;
      }
      expression.append(reservedExpansion ? ".+" : "[^/?#]+");
      cursor = matcher.end();
    }
    expression.append(Pattern.quote(template.substring(cursor))).append("$");
    return Pattern.compile(expression.toString()).matcher(resourceUri).matches();
  }

  private static String normalizeCode(final String value) {
    String code = required(value, "MCP server code must not be blank")
        .toLowerCase(Locale.ROOT);
    if (!code.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException(
          "MCP server code supports lowercase letters, digits, underscores, and hyphens"
      );
    }
    return code;
  }

  private static String required(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String requireId(final String id) {
    return required(id, "MCP server ID must not be blank");
  }

  private static String requireEntityId(final AiMcpServerDefinition entity) {
    if (entity == null) {
      throw new IllegalArgumentException("MCP server must not be null");
    }
    return requireId(entity.getId());
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }

  private record WorkflowInvocation(
      AiMcpServerDefinition server,
      AiMcpCapabilitySnapshot snapshot,
      String skillId,
      String skillVersionId,
      String executionId,
      String stepId,
      String operationId,
      String capabilityToken
  ) {
  }
}
