package org.simplepoint.plugin.ai.skill.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPromptBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillResourceBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionApprovalPolicy;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBudget;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillUpsertRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.simplepoint.plugin.ai.skill.api.service.SkillArtifactVerifier;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowBindings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current-scope registry for declarative, digest-pinned Skill versions.
 */
@Service
@ConditionalOnProperty(
    prefix = "simplepoint.ai.skill.registry",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AiSkillServiceImpl implements AiSkillService {

  private static final String API_VERSION = "simplepoint.io/v1alpha1";

  private static final String KIND = "Skill";

  private static final String MANIFEST_SCHEMA_VERSION = "1.0";

  private static final int MAXIMUM_MANIFEST_BYTES = 512 * 1024;

  private static final int MAXIMUM_CAPABILITY_BINDINGS = 64;

  private static final int MAXIMUM_WORKFLOW_STEPS = 128;

  private static final Pattern CODE =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern TOOL_NAME =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$");

  private static final Pattern SEMANTIC_VERSION = Pattern.compile(
      "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
          + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
          + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
  );

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final Set<String> FORBIDDEN_EXECUTION_KEYS = Set.of(
      "script",
      "command",
      "run",
      "image",
      "container",
      "entrypoint",
      "executable",
      "sourcecode"
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiSkillToolBindingRepository bindingRepository;

  private final AiSkillPromptBindingRepository promptBindingRepository;

  private final AiSkillResourceBindingRepository resourceBindingRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiMcpServerDefinitionService mcpServerService;

  private final SkillArtifactVerifier artifactVerifier;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillWorkflowPlanCompiler workflowPlanCompiler;

  private final SkillBudgetPolicy budgetPolicy;

  private final SkillApprovalPolicy approvalPolicy;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the Skill registry service.
   */
  public AiSkillServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository versionRepository,
      final AiSkillToolBindingRepository bindingRepository,
      final AiSkillPromptBindingRepository promptBindingRepository,
      final AiSkillResourceBindingRepository resourceBindingRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiMcpServerDefinitionService mcpServerService,
      final SkillArtifactVerifier artifactVerifier,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillWorkflowPlanCompiler workflowPlanCompiler,
      final SkillBudgetPolicy budgetPolicy,
      final SkillApprovalPolicy approvalPolicy,
      final ObjectMapper objectMapper
  ) {
    this.skillRepository = skillRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.promptBindingRepository = promptBindingRepository;
    this.resourceBindingRepository = resourceBindingRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.mcpServerService = mcpServerService;
    this.artifactVerifier = artifactVerifier;
    this.schemaValidator = schemaValidator;
    this.workflowPlanCompiler = workflowPlanCompiler;
    this.budgetPolicy = budgetPolicy;
    this.approvalPolicy = approvalPolicy;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiSkillDefinition> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return skillRepository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiSkillDefinition> find(final String id) {
    Optional<AiSkillDefinition> result =
        skillRepository.findActiveById(requireIdentifier(id, "Skill ID"));
    result.ifPresent(this::assertReadable);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillDefinition create(final SkillUpsertRequest request) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String code = requireCode(request == null ? null : request.code());
    final String name = requireText(request.name(), "Skill name", 128);
    skillRepository.findActiveByCodeAndScope(
        code,
        scope.scopeType(),
        scope.tenantId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException("Skill code already exists");
    });
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setScopeType(scope.scopeType());
    skill.setTenantId(scope.tenantId());
    skill.setCode(code);
    skill.setName(name);
    skill.setDescription(optionalText(request.description(), 512));
    skill.setEnabled(request.enabled() == null || request.enabled());
    skill.setStatus(skill.getEnabled() ? SkillStatus.DRAFT : SkillStatus.DISABLED);
    return skillRepository.save(skill);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillDefinition update(
      final String id,
      final SkillUpsertRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Skill request must not be null");
    }
    AiSkillDefinition skill = requireManagedSkill(id, true);
    if (request.code() != null
        && !request.code().isBlank()
        && !skill.getCode().equals(request.code().trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Skill code is immutable");
    }
    skill.setName(requireText(request.name(), "Skill name", 128));
    skill.setDescription(optionalText(request.description(), 512));
    if (request.enabled() != null) {
      skill.setEnabled(request.enabled());
    }
    if (!Boolean.TRUE.equals(skill.getEnabled())) {
      skill.setStatus(SkillStatus.DISABLED);
    } else if (skill.getActiveVersionId() != null) {
      skill.setStatus(SkillStatus.ACTIVE);
    } else {
      skill.setStatus(SkillStatus.DRAFT);
    }
    return skillRepository.save(skill);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final String id) {
    AiSkillDefinition skill = requireManagedSkill(id, true);
    if (versionRepository.countActiveBySkillId(skill.getId()) > 0) {
      throw new IllegalStateException(
          "Skill with immutable versions cannot be deleted"
      );
    }
    skill.setDeletedAt(Instant.now());
    skillRepository.save(skill);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiSkillVersion> findVersions(
      final String skillId,
      final Pageable pageable
  ) {
    AiSkillDefinition skill = requireManagedSkill(skillId, false);
    return versionRepository.findAllActiveBySkillId(
        skill.getId(),
        pageable
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiSkillVersion> findVersion(
      final String skillId,
      final String versionId
  ) {
    AiSkillDefinition skill = requireManagedSkill(skillId, false);
    return versionRepository.findActiveByIdAndSkillId(
        requireIdentifier(versionId, "Skill version ID"),
        skill.getId()
    ).map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillVersion createVersion(
      final String skillId,
      final SkillVersionCreateRequest request
  ) {
    AiSkillDefinition skill = requireManagedSkill(skillId, true);
    return persistVersion(skill, request);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillVersion createManagedVersion(
      final String skillId,
      final AiResourceScope scopeType,
      final String tenantId,
      final SkillVersionCreateRequest request
  ) {
    return persistVersion(
        requireBackgroundSkill(skillId, scopeType, tenantId),
        request
    );
  }

  private AiSkillVersion persistVersion(
      final AiSkillDefinition skill,
      final SkillVersionCreateRequest request
  ) {
    NormalizedVersion normalized = normalizeVersion(skill, request);
    versionRepository.findActiveByVersionAndSkillId(
        normalized.version(),
        skill.getId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException("Skill version already exists");
    });

    AiSkillVersion version = new AiSkillVersion();
    version.setSkillId(skill.getId());
    version.setScopeType(skill.getScopeType());
    version.setTenantId(skill.getTenantId());
    version.setVersion(normalized.version());
    version.setArtifactReference(normalized.artifactReference());
    version.setArtifactDigest(normalized.artifactDigest());
    version.setArtifactMediaType(normalized.artifactMediaType());
    version.setArtifactConfigDigest(normalized.artifactConfigDigest());
    version.setArtifactContentDigest(normalized.artifactContentDigest());
    version.setArtifactSignatureRequired(normalized.signatureRequired());
    version.setArtifactSignatureVerified(normalized.signatureVerified());
    version.setArtifactVerificationPolicyHash(normalized.policyHash());
    version.setArtifactVerifiedAt(normalized.verifiedAt());
    version.setManifestSchemaVersion(MANIFEST_SCHEMA_VERSION);
    version.setContentHash(normalized.contentHash());
    version.setManifestJson(normalized.manifestJson());
    version.setInputSchemaJson(writeJson(normalized.inputSchema()));
    version.setOutputSchemaJson(writeJson(normalized.outputSchema()));
    version.setWorkflowJson(writeJson(normalized.workflow()));
    version.setBudgetJson(writeJson(normalized.budget()));
    version.setStatus(SkillVersionStatus.DRAFT);
    AiSkillVersion saved = versionRepository.save(version);

    List<AiSkillToolBinding> bindings = new ArrayList<>();
    for (int index = 0; index < normalized.toolBindings().size(); index++) {
      NormalizedToolBinding source = normalized.toolBindings().get(index);
      AiSkillToolBinding binding = new AiSkillToolBinding();
      binding.setSkillVersionId(saved.getId());
      binding.setScopeType(saved.getScopeType());
      binding.setTenantId(saved.getTenantId());
      binding.setMcpServerId(source.serverId());
      binding.setCapabilitySnapshotId(source.snapshotId());
      binding.setToolName(source.toolName());
      binding.setToolAlias(source.alias());
      binding.setInputSchemaHash(source.inputSchemaHash());
      binding.setOutputSchemaHash(source.outputSchemaHash());
      binding.setBindingOrder(index);
      bindings.add(bindingRepository.save(binding));
    }
    List<AiSkillPromptBinding> promptBindings = new ArrayList<>();
    for (int index = 0; index < normalized.promptBindings().size(); index++) {
      NormalizedPromptBinding source = normalized.promptBindings().get(index);
      AiSkillPromptBinding binding = new AiSkillPromptBinding();
      binding.setSkillVersionId(saved.getId());
      binding.setScopeType(saved.getScopeType());
      binding.setTenantId(saved.getTenantId());
      binding.setMcpServerId(source.serverId());
      binding.setCapabilitySnapshotId(source.snapshotId());
      binding.setPromptName(source.promptName());
      binding.setPromptAlias(source.alias());
      binding.setDescriptorHash(source.descriptorHash());
      binding.setBindingOrder(index);
      promptBindings.add(promptBindingRepository.save(binding));
    }
    List<AiSkillResourceBinding> resourceBindings = new ArrayList<>();
    for (int index = 0; index < normalized.resourceBindings().size(); index++) {
      NormalizedResourceBinding source =
          normalized.resourceBindings().get(index);
      AiSkillResourceBinding binding = new AiSkillResourceBinding();
      binding.setSkillVersionId(saved.getId());
      binding.setScopeType(saved.getScopeType());
      binding.setTenantId(saved.getTenantId());
      binding.setMcpServerId(source.serverId());
      binding.setCapabilitySnapshotId(source.snapshotId());
      binding.setResourceSelector(source.selector());
      binding.setResourceAlias(source.alias());
      binding.setResourceTemplate(source.resourceTemplate());
      binding.setDescriptorHash(source.descriptorHash());
      binding.setBindingOrder(index);
      resourceBindings.add(resourceBindingRepository.save(binding));
    }
    return decorate(
        saved,
        bindings,
        promptBindings,
        resourceBindings
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillVersion publishVersion(
      final String skillId,
      final String versionId
  ) {
    AiSkillDefinition skill = requireManagedSkill(skillId, true);
    return activateVersion(skill, versionId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillVersion publishManagedVersion(
      final String skillId,
      final AiResourceScope scopeType,
      final String tenantId,
      final String versionId,
      final boolean activate
  ) {
    return transitionPublishedVersion(
        requireBackgroundSkill(skillId, scopeType, tenantId),
        versionId,
        activate
    );
  }

  private AiSkillVersion activateVersion(
      final AiSkillDefinition skill,
      final String versionId
  ) {
    return transitionPublishedVersion(skill, versionId, true);
  }

  private AiSkillVersion transitionPublishedVersion(
      final AiSkillDefinition skill,
      final String versionId,
      final boolean activate
  ) {
    if (!Boolean.TRUE.equals(skill.getEnabled())) {
      throw new IllegalStateException("Disabled Skill cannot publish a version");
    }
    AiSkillVersion version = requireVersionForUpdate(skill, versionId);
    if (version.getStatus() == SkillVersionStatus.DEPRECATED) {
      throw new IllegalStateException("Deprecated Skill version cannot be published");
    }
    if (version.getArtifactVerifiedAt() == null
        || Boolean.TRUE.equals(version.getArtifactSignatureRequired())
        && !Boolean.TRUE.equals(version.getArtifactSignatureVerified())) {
      throw new IllegalStateException(
          "Skill Artifact has not passed supply-chain verification"
      );
    }
    if (version.getStatus() == SkillVersionStatus.DRAFT) {
      version.setStatus(SkillVersionStatus.PUBLISHED);
      version.setPublishedAt(Instant.now());
      versionRepository.save(version);
    }
    if (activate) {
      skill.setActiveVersionId(version.getId());
      skill.setStatus(SkillStatus.ACTIVE);
      skillRepository.save(skill);
    }
    return decorate(version);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillVersion deprecateVersion(
      final String skillId,
      final String versionId
  ) {
    AiSkillDefinition skill = requireManagedSkill(skillId, true);
    AiSkillVersion version = requireVersionForUpdate(skill, versionId);
    if (version.getStatus() == SkillVersionStatus.DRAFT) {
      throw new IllegalStateException("Draft Skill version cannot be deprecated");
    }
    if (version.getStatus() != SkillVersionStatus.DEPRECATED) {
      version.setStatus(SkillVersionStatus.DEPRECATED);
      version.setDeprecatedAt(Instant.now());
      versionRepository.save(version);
    }
    if (version.getId().equals(skill.getActiveVersionId())) {
      skill.setActiveVersionId(null);
      skill.setStatus(Boolean.TRUE.equals(skill.getEnabled())
          ? SkillStatus.DRAFT : SkillStatus.DISABLED);
      skillRepository.save(skill);
    }
    return decorate(version);
  }

  private NormalizedVersion normalizeVersion(
      final AiSkillDefinition skill,
      final SkillVersionCreateRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Skill version request must not be null");
    }
    final String version = requirePattern(
        request.version(),
        "Skill semantic version",
        SEMANTIC_VERSION
    );
    String artifactReference = requireText(
        request.artifactReference(),
        "OCI artifact reference",
        512
    );
    if (artifactReference.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("OCI artifact reference must not contain spaces");
    }
    final String artifactDigest = requirePattern(
        request.artifactDigest(),
        "OCI artifact digest",
        DIGEST
    );
    VerifiedSkillArtifact verified = artifactVerifier.verify(
        artifactReference,
        artifactDigest
    );
    Map<String, Object> requestedManifest = requireMap(
        request.manifest(),
        "Skill manifest"
    );
    Map<String, Object> manifest = requireMap(
        verified.manifest(),
        "OCI Skill Manifest"
    );
    if (!writeJson(requestedManifest).equals(writeJson(manifest))) {
      throw new IllegalArgumentException(
          "Submitted Skill manifest does not match the OCI Artifact content"
      );
    }
    assertAllowedKeys(
        manifest,
        Set.of("apiVersion", "kind", "metadata", "spec"),
        "Skill manifest"
    );
    requireEquals(manifest.get("apiVersion"), API_VERSION, "Manifest apiVersion");
    requireEquals(manifest.get("kind"), KIND, "Manifest kind");
    Map<String, Object> metadata = requireMap(
        manifest.get("metadata"),
        "Manifest metadata"
    );
    assertAllowedKeys(
        metadata,
        Set.of("name", "version", "title", "description", "labels"),
        "Manifest metadata"
    );
    requireEquals(metadata.get("name"), skill.getCode(), "Manifest metadata.name");
    requireEquals(metadata.get("version"), version, "Manifest metadata.version");

    Map<String, Object> spec = requireMap(manifest.get("spec"), "Manifest spec");
    assertAllowedKeys(
        spec,
        Set.of(
            "inputSchema",
            "outputSchema",
            "workflow",
            "tools",
            "prompts",
            "resources",
            "budgets",
            "approvals"
        ),
        "Manifest spec"
    );
    assertNoExecutableCode(spec);
    final Map<String, Object> inputSchema =
        requireObjectSchema(spec.get("inputSchema"), "Skill inputSchema");
    final Map<String, Object> outputSchema =
        requireObjectSchema(spec.get("outputSchema"), "Skill outputSchema");
    schemaValidator.validateSchema(inputSchema, "Skill inputSchema");
    schemaValidator.validateSchema(outputSchema, "Skill outputSchema");
    Map<String, Object> workflow =
        requireMap(spec.get("workflow"), "Skill workflow");
    assertAllowedKeys(
        workflow,
        Set.of("steps", "output"),
        "Skill workflow"
    );
    List<Map<String, Object>> tools = mapList(spec.get("tools"), "Skill tools");
    List<Map<String, Object>> prompts = optionalMapList(
        spec.get("prompts"),
        "Skill prompts"
    );
    List<Map<String, Object>> resources = optionalMapList(
        spec.get("resources"),
        "Skill resources"
    );
    List<Map<String, Object>> steps =
        mapList(workflow.get("steps"), "Skill workflow steps");
    if (tools.size() > MAXIMUM_CAPABILITY_BINDINGS
        || prompts.size() > MAXIMUM_CAPABILITY_BINDINGS
        || resources.size() > MAXIMUM_CAPABILITY_BINDINGS) {
      throw new IllegalArgumentException(
          "Skill has too many MCP capability bindings"
      );
    }
    if (steps.size() > MAXIMUM_WORKFLOW_STEPS) {
      throw new IllegalArgumentException("Skill workflow has too many steps");
    }
    List<NormalizedToolBinding> bindings = normalizeBindings(
        tools,
        skill.getScopeType(),
        skill.getTenantId()
    );
    List<NormalizedPromptBinding> promptBindings =
        normalizePromptBindings(
            prompts,
            skill.getScopeType(),
            skill.getTenantId()
        );
    List<NormalizedResourceBinding> resourceBindings =
        normalizeResourceBindings(
            resources,
            skill.getScopeType(),
            skill.getTenantId()
        );
    Set<String> bindingAliases = new HashSet<>();
    bindings.forEach(binding -> bindingAliases.add(binding.alias()));
    Set<String> promptAliases = new HashSet<>();
    promptBindings.forEach(binding -> promptAliases.add(binding.alias()));
    Set<String> resourceAliases = new HashSet<>();
    resourceBindings.forEach(binding -> resourceAliases.add(binding.alias()));
    SkillWorkflowPlanCompiler.WorkflowPlan plan =
        workflowPlanCompiler.compile(
            workflow,
            new WorkflowBindings(
                bindingAliases,
                promptAliases,
                resourceAliases
            )
        );
    SkillExecutionBudget budget = budgetPolicy.normalize(
        spec.get("budgets"),
        plan.maximumToolCalls()
    );
    SkillExecutionApprovalPolicy approvals = approvalPolicy.normalize(
        spec.get("approvals")
    );
    if (bindings.stream().anyMatch(NormalizedToolBinding::requiresApproval)
        && !approvals.required()) {
      throw new IllegalArgumentException(
          "A non-read-only MCP Tool requires execution approval"
      );
    }

    String manifestJson = writeJson(manifest);
    if (manifestJson.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_MANIFEST_BYTES) {
      throw new IllegalArgumentException("Skill manifest is too large");
    }
    return new NormalizedVersion(
        version,
        artifactReference,
        verified.digest(),
        verified.mediaType(),
        verified.configDigest(),
        verified.contentDigest(),
        verified.signatureRequired(),
        verified.signatureVerified(),
        verified.policyHash(),
        verified.verifiedAt(),
        manifest,
        manifestJson,
        inputSchema,
        outputSchema,
        workflow,
        budget,
        sha256(manifestJson),
        bindings,
        promptBindings,
        resourceBindings
    );
  }

  private List<NormalizedToolBinding> normalizeBindings(
      final List<Map<String, Object>> tools,
      final AiResourceScope invocationScope,
      final String invocationTenantId
  ) {
    List<NormalizedToolBinding> bindings = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    for (Map<String, Object> source : tools) {
      assertAllowedKeys(
          source,
          Set.of("alias", "serverId", "snapshotId", "name"),
          "Skill Tool binding"
      );
      String alias = requirePattern(source.get("alias"), "Tool alias", CODE);
      String serverId = requirePattern(
          source.get("serverId"),
          "MCP server ID",
          IDENTIFIER
      );
      String snapshotId = requirePattern(
          source.get("snapshotId"),
          "MCP capability snapshot ID",
          IDENTIFIER
      );
      String toolName = requirePattern(source.get("name"), "MCP Tool name", TOOL_NAME);
      if (!aliases.add(alias)) {
        throw new IllegalArgumentException("Duplicate Skill Tool alias: " + alias);
      }
      McpCapabilitySnapshotDetails snapshot =
          mcpServerService.getSnapshotForScope(
              serverId,
              snapshotId,
              invocationScope,
              invocationTenantId
          );
      McpToolDescriptor tool = snapshot.tools().stream()
          .filter(candidate -> toolName.equals(candidate.name()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP Tool does not exist in pinned snapshot: " + toolName
          ));
      bindings.add(new NormalizedToolBinding(
          alias,
          serverId,
          snapshotId,
          toolName,
          sha256(writeJson(tool.inputSchema())),
          tool.outputSchema() == null
              ? null : sha256(writeJson(tool.outputSchema())),
          requiresApproval(tool)
      ));
    }
    return List.copyOf(bindings);
  }

  private List<NormalizedPromptBinding> normalizePromptBindings(
      final List<Map<String, Object>> prompts,
      final AiResourceScope invocationScope,
      final String invocationTenantId
  ) {
    List<NormalizedPromptBinding> bindings = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    for (Map<String, Object> source : prompts) {
      assertAllowedKeys(
          source,
          Set.of("alias", "serverId", "snapshotId", "name"),
          "Skill Prompt binding"
      );
      String alias = requirePattern(source.get("alias"), "Prompt alias", CODE);
      String serverId = requirePattern(
          source.get("serverId"),
          "MCP server ID",
          IDENTIFIER
      );
      String snapshotId = requirePattern(
          source.get("snapshotId"),
          "MCP capability snapshot ID",
          IDENTIFIER
      );
      String promptName = requirePattern(
          source.get("name"),
          "MCP Prompt name",
          TOOL_NAME
      );
      if (!aliases.add(alias)) {
        throw new IllegalArgumentException(
            "Duplicate Skill Prompt alias: " + alias
        );
      }
      McpCapabilitySnapshotDetails snapshot =
          mcpServerService.getSnapshotForScope(
              serverId,
              snapshotId,
              invocationScope,
              invocationTenantId
          );
      McpPromptDescriptor prompt = snapshot.prompts().stream()
          .filter(candidate -> promptName.equals(candidate.name()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP Prompt does not exist in pinned snapshot: " + promptName
          ));
      bindings.add(new NormalizedPromptBinding(
          alias,
          serverId,
          snapshotId,
          promptName,
          sha256(writeJson(prompt))
      ));
    }
    return List.copyOf(bindings);
  }

  private static boolean requiresApproval(final McpToolDescriptor tool) {
    Map<String, Object> annotations = tool.annotations() == null
        ? Map.of() : tool.annotations();
    boolean readOnly = Boolean.TRUE.equals(annotations.get("readOnlyHint"));
    return !readOnly
        && !Boolean.FALSE.equals(annotations.get("destructiveHint"));
  }

  private List<NormalizedResourceBinding> normalizeResourceBindings(
      final List<Map<String, Object>> resources,
      final AiResourceScope invocationScope,
      final String invocationTenantId
  ) {
    List<NormalizedResourceBinding> bindings = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    for (Map<String, Object> source : resources) {
      assertAllowedKeys(
          source,
          Set.of("alias", "serverId", "snapshotId", "uri", "uriTemplate"),
          "Skill Resource binding"
      );
      String alias = requirePattern(source.get("alias"), "Resource alias", CODE);
      String serverId = requirePattern(
          source.get("serverId"),
          "MCP server ID",
          IDENTIFIER
      );
      String snapshotId = requirePattern(
          source.get("snapshotId"),
          "MCP capability snapshot ID",
          IDENTIFIER
      );
      Object uriValue = source.get("uri");
      Object templateValue = source.get("uriTemplate");
      if ((uriValue == null) == (templateValue == null)) {
        throw new IllegalArgumentException(
            "Skill Resource binding must define exactly one uri or uriTemplate"
        );
      }
      boolean resourceTemplate = templateValue != null;
      String selector = requireText(
          resourceTemplate ? templateValue : uriValue,
          resourceTemplate ? "MCP Resource URI Template" : "MCP Resource URI",
          1024
      );
      if (!aliases.add(alias)) {
        throw new IllegalArgumentException(
            "Duplicate Skill Resource alias: " + alias
        );
      }
      McpCapabilitySnapshotDetails snapshot =
          mcpServerService.getSnapshotForScope(
              serverId,
              snapshotId,
              invocationScope,
              invocationTenantId
          );
      Object descriptor;
      if (resourceTemplate) {
        descriptor = snapshot.resourceTemplates().stream()
            .filter(candidate -> selector.equals(candidate.uriTemplate()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "MCP Resource Template does not exist in pinned snapshot: "
                    + selector
            ));
      } else {
        descriptor = snapshot.resources().stream()
            .filter(candidate -> selector.equals(candidate.uri()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "MCP Resource does not exist in pinned snapshot: " + selector
            ));
      }
      bindings.add(new NormalizedResourceBinding(
          alias,
          serverId,
          snapshotId,
          selector,
          resourceTemplate,
          sha256(writeJson(descriptor))
      ));
    }
    return List.copyOf(bindings);
  }

  private AiSkillDefinition requireManagedSkill(
      final String id,
      final boolean forUpdate
  ) {
    String normalized = requireIdentifier(id, "Skill ID");
    AiSkillDefinition skill = (forUpdate
        ? skillRepository.findActiveByIdForUpdate(normalized)
        : skillRepository.findActiveById(normalized))
        .orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (forUpdate) {
      scopeAccessPolicy.assertCanManageOwnedResource(
          skill.getScopeType(),
          skill.getTenantId()
      );
    } else {
      assertReadable(skill);
    }
    return skill;
  }

  private AiSkillDefinition requireBackgroundSkill(
      final String id,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    AiSkillDefinition skill = skillRepository.findActiveByIdForUpdate(
        requireIdentifier(id, "Skill ID")
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (scopeType == null
        || skill.getScopeType() != scopeType
        || !Objects.equals(skill.getTenantId(), tenantId)) {
      throw new IllegalArgumentException(
          "Skill does not belong to the publication task scope"
      );
    }
    return skill;
  }

  private AiSkillVersion requireVersionForUpdate(
      final AiSkillDefinition skill,
      final String versionId
  ) {
    return versionRepository.findActiveByIdAndSkillIdForUpdate(
        requireIdentifier(versionId, "Skill version ID"),
        skill.getId()
    ).orElseThrow(() -> new IllegalArgumentException(
        "Skill version does not exist"
    ));
  }

  private void assertReadable(final AiSkillDefinition skill) {
    scopeAccessPolicy.assertCanReadManagedResource(
        skill.getScopeType(),
        skill.getTenantId()
    );
  }

  private AiSkillVersion decorate(final AiSkillVersion version) {
    return decorate(
        version,
        bindingRepository.findAllActiveBySkillVersionId(version.getId()),
        promptBindingRepository.findAllActiveBySkillVersionId(version.getId()),
        resourceBindingRepository.findAllActiveBySkillVersionId(version.getId())
    );
  }

  private AiSkillVersion decorate(
      final AiSkillVersion version,
      final List<AiSkillToolBinding> bindings,
      final List<AiSkillPromptBinding> promptBindings,
      final List<AiSkillResourceBinding> resourceBindings
  ) {
    version.setManifest(readMap(version.getManifestJson()));
    version.setInputSchema(readMap(version.getInputSchemaJson()));
    version.setOutputSchema(readMap(version.getOutputSchemaJson()));
    version.setWorkflow(readMap(version.getWorkflowJson()));
    int maximumToolCalls = workflowPlanCompiler.compile(
        version.getWorkflow(),
        new WorkflowBindings(
            bindings.stream()
                .map(AiSkillToolBinding::getToolAlias)
                .collect(java.util.stream.Collectors.toSet()),
            promptBindings.stream()
                .map(AiSkillPromptBinding::getPromptAlias)
                .collect(java.util.stream.Collectors.toSet()),
            resourceBindings.stream()
                .map(AiSkillResourceBinding::getResourceAlias)
                .collect(java.util.stream.Collectors.toSet())
        )
    ).maximumToolCalls();
    version.setBudget(budgetPolicy.read(
        version.getBudgetJson(),
        maximumToolCalls
    ));
    version.setApprovalPolicy(approvalPolicy.readManifest(
        version.getManifestJson()
    ));
    version.setToolBindings(List.copyOf(bindings));
    version.setPromptBindings(List.copyOf(promptBindings));
    version.setResourceBindings(List.copyOf(resourceBindings));
    return version;
  }

  private Map<String, Object> readMap(final String value) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored Skill manifest is invalid", ex);
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Skill manifest is not serializable", ex);
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

  private Map<String, Object> requireMap(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    return objectMapper.convertValue(value, MAP_TYPE);
  }

  private List<Map<String, Object>> mapList(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      result.add(requireMap(item, label + " item"));
    }
    return List.copyOf(result);
  }

  private List<Map<String, Object>> optionalMapList(
      final Object value,
      final String label
  ) {
    return value == null ? List.of() : mapList(value, label);
  }

  private Map<String, Object> requireObjectSchema(
      final Object value,
      final String label
  ) {
    Map<String, Object> schema = requireMap(value, label);
    requireEquals(schema.get("type"), "object", label + ".type");
    return schema;
  }

  private static void assertAllowedKeys(
      final Map<String, Object> value,
      final Set<String> allowed,
      final String label
  ) {
    value.keySet().stream()
        .filter(key -> !allowed.contains(key))
        .findFirst()
        .ifPresent(key -> {
          throw new IllegalArgumentException(
              label + " contains unsupported field: " + key
          );
        });
  }

  private static void assertNoExecutableCode(final Object value) {
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, nested) -> {
        String normalized = String.valueOf(key)
            .replace("_", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
        if (FORBIDDEN_EXECUTION_KEYS.contains(normalized)) {
          throw new IllegalArgumentException(
              "Skill manifest cannot contain executable field: " + key
          );
        }
        assertNoExecutableCode(nested);
      });
    } else if (value instanceof List<?> list) {
      list.forEach(AiSkillServiceImpl::assertNoExecutableCode);
    }
  }

  private static String requireCode(final Object value) {
    String code = requirePattern(value, "Skill code", CODE);
    return code.toLowerCase(Locale.ROOT);
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
      throw new IllegalArgumentException(label + " has an invalid format");
    }
    return normalized;
  }

  private static String requireText(
      final Object value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null ? null : String.valueOf(value).trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
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
      throw new IllegalArgumentException("Skill description is too long");
    }
    return normalized;
  }

  private static void requireEquals(
      final Object actual,
      final String expected,
      final String label
  ) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(label + " must be " + expected);
    }
  }

  private record NormalizedToolBinding(
      String alias,
      String serverId,
      String snapshotId,
      String toolName,
      String inputSchemaHash,
      String outputSchemaHash,
      boolean requiresApproval
  ) {
  }

  private record NormalizedPromptBinding(
      String alias,
      String serverId,
      String snapshotId,
      String promptName,
      String descriptorHash
  ) {
  }

  private record NormalizedResourceBinding(
      String alias,
      String serverId,
      String snapshotId,
      String selector,
      boolean resourceTemplate,
      String descriptorHash
  ) {
  }

  private record NormalizedVersion(
      String version,
      String artifactReference,
      String artifactDigest,
      String artifactMediaType,
      String artifactConfigDigest,
      String artifactContentDigest,
      boolean signatureRequired,
      boolean signatureVerified,
      String policyHash,
      Instant verifiedAt,
      Map<String, Object> manifest,
      String manifestJson,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema,
      Map<String, Object> workflow,
      SkillExecutionBudget budget,
      String contentHash,
      List<NormalizedToolBinding> toolBindings,
      List<NormalizedPromptBinding> promptBindings,
      List<NormalizedResourceBinding> resourceBindings
  ) {
  }
}
