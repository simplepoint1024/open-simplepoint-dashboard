package org.simplepoint.plugin.ai.workflow.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDependencyBinding;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowDependencyType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowUpsertRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionCreateRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDefinitionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDependencyBindingRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowVersionRepository;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowService;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler.CompiledWorkflow;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowManifestCompiler.DependencyReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current-scope registry for declarative immutable Agent Workflows.
 */
@Service
public class AiWorkflowServiceImpl implements AiWorkflowService {

  private static final String API_VERSION = "simplepoint.io/v1alpha1";

  private static final String KIND = "AgentWorkflow";

  private static final String MANIFEST_SCHEMA_VERSION = "1.0";

  private static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;

  private static final Pattern CODE =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");

  private static final Pattern SEMANTIC_VERSION = Pattern.compile(
      "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
          + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
          + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiWorkflowDefinitionRepository workflowRepository;

  private final AiWorkflowVersionRepository versionRepository;

  private final AiWorkflowDependencyBindingRepository bindingRepository;

  private final AiAgentDefinitionRepository agentRepository;

  private final AiAgentVersionRepository agentVersionRepository;

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository skillVersionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final WorkflowManifestCompiler manifestCompiler;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the Workflow registry.
   */
  public AiWorkflowServiceImpl(
      final AiWorkflowDefinitionRepository workflowRepository,
      final AiWorkflowVersionRepository versionRepository,
      final AiWorkflowDependencyBindingRepository bindingRepository,
      final AiAgentDefinitionRepository agentRepository,
      final AiAgentVersionRepository agentVersionRepository,
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository skillVersionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final WorkflowManifestCompiler manifestCompiler,
      final ObjectMapper objectMapper
  ) {
    this.workflowRepository = workflowRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.agentRepository = agentRepository;
    this.agentVersionRepository = agentVersionRepository;
    this.skillRepository = skillRepository;
    this.skillVersionRepository = skillVersionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.manifestCompiler = manifestCompiler;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiWorkflowDefinition> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return workflowRepository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiWorkflowDefinition> find(final String id) {
    Optional<AiWorkflowDefinition> result =
        workflowRepository.findActiveById(
            requireIdentifier(id, "Workflow ID")
        );
    result.ifPresent(this::assertReadable);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowDefinition create(
      final WorkflowUpsertRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Workflow request must not be null"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String code = requireCode(request.code());
    workflowRepository.findActiveByCodeAndScope(
        code,
        scope.scopeType(),
        scope.tenantId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException(
          "Workflow code already exists"
      );
    });
    AiWorkflowDefinition workflow = new AiWorkflowDefinition();
    workflow.setScopeType(scope.scopeType());
    workflow.setTenantId(scope.tenantId());
    workflow.setCode(code);
    workflow.setName(requireText(request.name(), "Workflow name", 128));
    workflow.setDescription(optionalText(request.description(), 512));
    workflow.setEnabled(request.enabled() == null || request.enabled());
    workflow.setStatus(Boolean.TRUE.equals(workflow.getEnabled())
        ? WorkflowStatus.DRAFT : WorkflowStatus.DISABLED);
    return workflowRepository.save(workflow);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowDefinition update(
      final String id,
      final WorkflowUpsertRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Workflow request must not be null"
      );
    }
    AiWorkflowDefinition workflow = requireManagedWorkflow(id, true);
    if (request.code() != null
        && !request.code().isBlank()
        && !workflow.getCode().equals(
            request.code().trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Workflow code is immutable");
    }
    workflow.setName(
        requireText(request.name(), "Workflow name", 128)
    );
    workflow.setDescription(optionalText(request.description(), 512));
    if (request.enabled() != null) {
      workflow.setEnabled(request.enabled());
    }
    workflow.setStatus(!Boolean.TRUE.equals(workflow.getEnabled())
        ? WorkflowStatus.DISABLED
        : workflow.getActiveVersionId() == null
            ? WorkflowStatus.DRAFT : WorkflowStatus.ACTIVE);
    return workflowRepository.save(workflow);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final String id) {
    AiWorkflowDefinition workflow = requireManagedWorkflow(id, true);
    if (versionRepository.countActiveByWorkflowId(workflow.getId()) > 0) {
      throw new IllegalStateException(
          "Workflow with immutable versions cannot be deleted"
      );
    }
    workflow.setDeletedAt(Instant.now());
    workflowRepository.save(workflow);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiWorkflowVersion> findVersions(
      final String workflowId,
      final Pageable pageable
  ) {
    AiWorkflowDefinition workflow =
        requireManagedWorkflow(workflowId, false);
    return versionRepository.findAllActiveByWorkflowId(
        workflow.getId(),
        pageable
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiWorkflowVersion> findVersion(
      final String workflowId,
      final String versionId
  ) {
    AiWorkflowDefinition workflow =
        requireManagedWorkflow(workflowId, false);
    return versionRepository.findActiveByIdAndWorkflowId(
        requireIdentifier(versionId, "Workflow version ID"),
        workflow.getId()
    ).map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowVersion createVersion(
      final String workflowId,
      final WorkflowVersionCreateRequest request
  ) {
    AiWorkflowDefinition workflow =
        requireManagedWorkflow(workflowId, true);
    NormalizedVersion normalized = normalizeVersion(workflow, request);
    versionRepository.findActiveByVersionAndWorkflowId(
        normalized.version(),
        workflow.getId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException(
          "Workflow version already exists"
      );
    });
    AiWorkflowVersion version = new AiWorkflowVersion();
    version.setWorkflowId(workflow.getId());
    version.setScopeType(workflow.getScopeType());
    version.setTenantId(workflow.getTenantId());
    version.setVersion(normalized.version());
    version.setManifestSchemaVersion(MANIFEST_SCHEMA_VERSION);
    version.setManifestJson(normalized.manifestJson());
    version.setContentHash(normalized.contentHash());
    version.setStatus(WorkflowVersionStatus.DRAFT);
    AiWorkflowVersion saved = versionRepository.save(version);
    List<AiWorkflowDependencyBinding> bindings = new ArrayList<>();
    for (int index = 0; index < normalized.dependencies().size(); index++) {
      NormalizedDependency dependency =
          normalized.dependencies().get(index);
      AiWorkflowDependencyBinding binding =
          new AiWorkflowDependencyBinding();
      binding.setWorkflowVersionId(saved.getId());
      binding.setScopeType(saved.getScopeType());
      binding.setTenantId(saved.getTenantId());
      binding.setNodeId(dependency.nodeId());
      binding.setDependencyType(dependency.type());
      binding.setResourceId(dependency.resourceId());
      binding.setResourceVersionId(dependency.resourceVersionId());
      binding.setResourceCode(dependency.resourceCode());
      binding.setResourceVersionName(
          dependency.resourceVersionName()
      );
      binding.setResourceContentHash(dependency.resourceContentHash());
      binding.setBindingOrder(index);
      bindings.add(bindingRepository.save(binding));
    }
    return decorate(saved, bindings);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowVersion publishVersion(
      final String workflowId,
      final String versionId
  ) {
    AiWorkflowDefinition workflow =
        requireManagedWorkflow(workflowId, true);
    if (!Boolean.TRUE.equals(workflow.getEnabled())) {
      throw new IllegalStateException(
          "Disabled Workflow cannot publish a version"
      );
    }
    AiWorkflowVersion version =
        requireVersionForUpdate(workflow, versionId);
    if (version.getStatus() == WorkflowVersionStatus.DEPRECATED) {
      throw new IllegalStateException(
          "Deprecated Workflow version cannot be published"
      );
    }
    revalidateDependencies(workflow, version);
    if (version.getStatus() == WorkflowVersionStatus.DRAFT) {
      version.setStatus(WorkflowVersionStatus.PUBLISHED);
      version.setPublishedAt(Instant.now());
      versionRepository.save(version);
    }
    workflow.setActiveVersionId(version.getId());
    workflow.setStatus(WorkflowStatus.ACTIVE);
    workflowRepository.save(workflow);
    return decorate(version);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiWorkflowVersion deprecateVersion(
      final String workflowId,
      final String versionId
  ) {
    AiWorkflowDefinition workflow =
        requireManagedWorkflow(workflowId, true);
    AiWorkflowVersion version =
        requireVersionForUpdate(workflow, versionId);
    if (version.getStatus() == WorkflowVersionStatus.DRAFT) {
      throw new IllegalStateException(
          "Draft Workflow version cannot be deprecated"
      );
    }
    if (version.getStatus() != WorkflowVersionStatus.DEPRECATED) {
      version.setStatus(WorkflowVersionStatus.DEPRECATED);
      version.setDeprecatedAt(Instant.now());
      versionRepository.save(version);
    }
    if (version.getId().equals(workflow.getActiveVersionId())) {
      workflow.setActiveVersionId(null);
      workflow.setStatus(Boolean.TRUE.equals(workflow.getEnabled())
          ? WorkflowStatus.DRAFT : WorkflowStatus.DISABLED);
      workflowRepository.save(workflow);
    }
    return decorate(version);
  }

  private NormalizedVersion normalizeVersion(
      final AiWorkflowDefinition workflow,
      final WorkflowVersionCreateRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Workflow version request must not be null"
      );
    }
    final String version = requirePattern(
        request.version(),
        "Workflow semantic version",
        SEMANTIC_VERSION
    );
    Map<String, Object> manifest =
        requireMap(request.manifest(), "Workflow manifest");
    requireEquals(
        manifest.get("apiVersion"),
        API_VERSION,
        "Workflow apiVersion"
    );
    requireEquals(manifest.get("kind"), KIND, "Workflow kind");
    Map<String, Object> metadata = requireMap(
        manifest.get("metadata"),
        "Workflow metadata"
    );
    requireEquals(
        metadata.get("name"),
        workflow.getCode(),
        "Workflow metadata.name"
    );
    requireEquals(
        metadata.get("version"),
        version,
        "Workflow metadata.version"
    );
    CompiledWorkflow compiled = manifestCompiler.compile(manifest);
    Map<String, Object> spec = requireMap(
        manifest.get("spec"),
        "Workflow spec"
    );
    Map<String, Object> inputSchema =
        requireObjectSchema(spec.get("inputSchema"), "Input schema");
    Map<String, Object> outputSchema =
        requireObjectSchema(spec.get("outputSchema"), "Output schema");
    schemaValidator.validateSchema(
        inputSchema,
        "Workflow input schema"
    );
    schemaValidator.validateSchema(
        outputSchema,
        "Workflow output schema"
    );
    List<NormalizedDependency> dependencies = compiled.dependencies()
        .stream()
        .map(reference -> resolveDependency(workflow, reference))
        .toList();
    String manifestJson = writeJson(manifest);
    if (manifestJson.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_MANIFEST_BYTES) {
      throw new IllegalArgumentException(
          "Workflow manifest is too large"
      );
    }
    return new NormalizedVersion(
        version,
        manifestJson,
        sha256(manifestJson),
        dependencies
    );
  }

  private NormalizedDependency resolveDependency(
      final AiWorkflowDefinition workflow,
      final DependencyReference reference
  ) {
    if (reference.type() == WorkflowDependencyType.AGENT) {
      return resolveAgentDependency(workflow, reference);
    }
    return resolveSkillDependency(workflow, reference);
  }

  private NormalizedDependency resolveAgentDependency(
      final AiWorkflowDefinition workflow,
      final DependencyReference reference
  ) {
    AiAgentDefinition agent = agentRepository
        .findActiveById(reference.resourceId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Workflow Agent does not exist: " + reference.resourceId()
        ));
    assertDependencyScope(
        workflow,
        agent.getScopeType(),
        agent.getTenantId(),
        "Agent"
    );
    if (!Boolean.TRUE.equals(agent.getEnabled())) {
      throw new IllegalArgumentException(
          "Workflow Agent is disabled: " + agent.getId()
      );
    }
    AiAgentVersion version = agentVersionRepository
        .findActiveByIdAndAgentId(
            reference.resourceVersionId(),
            agent.getId()
        ).orElseThrow(() -> new IllegalArgumentException(
            "Workflow Agent version does not exist: "
                + reference.resourceVersionId()
        ));
    if (version.getStatus() != AgentVersionStatus.PUBLISHED) {
      throw new IllegalArgumentException(
          "Workflow Agent version must be published: "
              + version.getId()
      );
    }
    assertDependencyScope(
        workflow,
        version.getScopeType(),
        version.getTenantId(),
        "Agent version"
    );
    return new NormalizedDependency(
        reference.nodeId(),
        reference.type(),
        agent.getId(),
        version.getId(),
        agent.getCode(),
        version.getVersion(),
        version.getContentHash()
    );
  }

  private NormalizedDependency resolveSkillDependency(
      final AiWorkflowDefinition workflow,
      final DependencyReference reference
  ) {
    AiSkillDefinition skill = skillRepository
        .findActiveById(reference.resourceId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Workflow Skill does not exist: " + reference.resourceId()
        ));
    assertDependencyScope(
        workflow,
        skill.getScopeType(),
        skill.getTenantId(),
        "Skill"
    );
    if (!Boolean.TRUE.equals(skill.getEnabled())) {
      throw new IllegalArgumentException(
          "Workflow Skill is disabled: " + skill.getId()
      );
    }
    AiSkillVersion version = skillVersionRepository
        .findActiveByIdAndSkillId(
            reference.resourceVersionId(),
            skill.getId()
        ).orElseThrow(() -> new IllegalArgumentException(
            "Workflow Skill version does not exist: "
                + reference.resourceVersionId()
        ));
    if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
      throw new IllegalArgumentException(
          "Workflow Skill version must be published: "
              + version.getId()
      );
    }
    assertDependencyScope(
        workflow,
        version.getScopeType(),
        version.getTenantId(),
        "Skill version"
    );
    return new NormalizedDependency(
        reference.nodeId(),
        reference.type(),
        skill.getId(),
        version.getId(),
        skill.getCode(),
        version.getVersion(),
        version.getContentHash()
    );
  }

  private void assertDependencyScope(
      final AiWorkflowDefinition workflow,
      final org.simplepoint.plugin.ai.core.api.model.AiResourceScope scope,
      final String tenantId,
      final String label
  ) {
    if (!scopeAccessPolicy.canUseResourceFromScope(
        scope,
        tenantId,
        workflow.getScopeType(),
        workflow.getTenantId()
    )) {
      throw new IllegalArgumentException(
          "Workflow " + label + " is outside the Workflow scope"
      );
    }
  }

  private void revalidateDependencies(
      final AiWorkflowDefinition workflow,
      final AiWorkflowVersion version
  ) {
    for (AiWorkflowDependencyBinding binding
        : bindingRepository.findAllActiveByWorkflowVersionId(
            version.getId()
        )) {
      NormalizedDependency current = resolveDependency(
          workflow,
          new DependencyReference(
              binding.getNodeId(),
              binding.getDependencyType(),
              binding.getResourceId(),
              binding.getResourceVersionId()
          )
      );
      if (!binding.getResourceContentHash().equals(
          current.resourceContentHash())) {
        throw new IllegalStateException(
            "Pinned Workflow dependency content hash has changed"
        );
      }
    }
  }

  private AiWorkflowDefinition requireManagedWorkflow(
      final String id,
      final boolean forUpdate
  ) {
    String normalized = requireIdentifier(id, "Workflow ID");
    AiWorkflowDefinition workflow = (forUpdate
        ? workflowRepository.findActiveByIdForUpdate(normalized)
        : workflowRepository.findActiveById(normalized))
        .orElseThrow(() -> new IllegalArgumentException(
            "Workflow does not exist"
        ));
    if (forUpdate) {
      scopeAccessPolicy.assertCanManageOwnedResource(
          workflow.getScopeType(),
          workflow.getTenantId()
      );
    } else {
      assertReadable(workflow);
    }
    return workflow;
  }

  private AiWorkflowVersion requireVersionForUpdate(
      final AiWorkflowDefinition workflow,
      final String versionId
  ) {
    return versionRepository.findActiveByIdAndWorkflowIdForUpdate(
        requireIdentifier(versionId, "Workflow version ID"),
        workflow.getId()
    ).orElseThrow(() -> new IllegalArgumentException(
        "Workflow version does not exist"
    ));
  }

  private void assertReadable(final AiWorkflowDefinition workflow) {
    scopeAccessPolicy.assertCanReadManagedResource(
        workflow.getScopeType(),
        workflow.getTenantId()
    );
  }

  private AiWorkflowVersion decorate(final AiWorkflowVersion version) {
    return decorate(
        version,
        bindingRepository.findAllActiveByWorkflowVersionId(
            version.getId()
        )
    );
  }

  private AiWorkflowVersion decorate(
      final AiWorkflowVersion version,
      final List<AiWorkflowDependencyBinding> bindings
  ) {
    version.setManifest(readMap(version.getManifestJson()));
    version.setDependencies(List.copyOf(bindings));
    return version;
  }

  private Map<String, Object> readMap(final String value) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Stored Workflow manifest is invalid",
          ex
      );
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Workflow manifest is not serializable",
          ex
      );
    }
  }

  private Map<String, Object> requireMap(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    return objectMapper.convertValue(value, MAP_TYPE);
  }

  private Map<String, Object> requireObjectSchema(
      final Object value,
      final String label
  ) {
    Map<String, Object> schema = requireMap(value, label);
    requireEquals(schema.get("type"), "object", label + ".type");
    return schema;
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

  private static String requireCode(final Object value) {
    return requirePattern(value, "Workflow code", CODE)
        .toLowerCase(Locale.ROOT);
  }

  private static String requireIdentifier(
      final Object value,
      final String label
  ) {
    return requirePattern(value, label, IDENTIFIER);
  }

  private static String requirePattern(
      final Object value,
      final String label,
      final Pattern pattern
  ) {
    String normalized = requireText(value, label, 512);
    if (!pattern.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          label + " has an invalid format"
      );
    }
    return normalized;
  }

  private static String requireText(
      final Object value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null
        ? null : String.valueOf(value).trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(
          label + " must not be blank"
      );
    }
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }

  private static String optionalText(
      final String value,
      final int maximumLength
  ) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(
          "Workflow description is too long"
      );
    }
    return normalized;
  }

  private static void requireEquals(
      final Object actual,
      final String expected,
      final String label
  ) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(
          label + " must be " + expected
      );
    }
  }

  private record NormalizedDependency(
      String nodeId,
      WorkflowDependencyType type,
      String resourceId,
      String resourceVersionId,
      String resourceCode,
      String resourceVersionName,
      String resourceContentHash
  ) {
  }

  private record NormalizedVersion(
      String version,
      String manifestJson,
      String contentHash,
      List<NormalizedDependency> dependencies
  ) {
  }
}
