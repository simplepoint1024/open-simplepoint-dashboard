package org.simplepoint.plugin.ai.agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentUpsertRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionCreateRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentSkillBindingRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentService;
import org.simplepoint.plugin.ai.agent.service.support.AgentOperationAudit;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyAvailabilityCode;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyOption;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyOptionView;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyResolutionView;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current-scope registry for declarative, immutable Agent versions.
 */
@Service
public class AiAgentServiceImpl implements AiAgentService {

  private static final String API_VERSION = "simplepoint.io/v1alpha1";

  private static final String KIND = "Agent";

  private static final String MANIFEST_SCHEMA_VERSION = "1.0";

  private static final int MAXIMUM_MANIFEST_BYTES = 512 * 1024;

  private static final int MAXIMUM_SKILL_BINDINGS = 32;

  private static final int MAXIMUM_DEPENDENCY_PAGE_SIZE = 50;

  private static final int MAXIMUM_DEPENDENCY_QUERY_LENGTH = 128;

  private static final int MAXIMUM_DEPENDENCY_RESOLVE_IDS = 128;

  private static final Pattern CODE =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");

  private static final Pattern SEMANTIC_VERSION = Pattern.compile(
      "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
          + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
          + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
  );

  private static final Set<String> FORBIDDEN_EXECUTION_KEYS = Set.of(
      "script",
      "command",
      "image",
      "container",
      "entrypoint",
      "executable",
      "sourcecode"
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiAgentDefinitionRepository agentRepository;

  private final AiAgentVersionRepository versionRepository;

  private final AiAgentSkillBindingRepository bindingRepository;

  private final AiModelDefinitionRepository modelRepository;

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository skillVersionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the Agent registry service.
   */
  public AiAgentServiceImpl(
      final AiAgentDefinitionRepository agentRepository,
      final AiAgentVersionRepository versionRepository,
      final AiAgentSkillBindingRepository bindingRepository,
      final AiModelDefinitionRepository modelRepository,
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository skillVersionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final ObjectMapper objectMapper
  ) {
    this.agentRepository = agentRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.modelRepository = modelRepository;
    this.skillRepository = skillRepository;
    this.skillVersionRepository = skillVersionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiAgentDefinition> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return agentRepository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiAgentDefinition> find(final String id) {
    Optional<AiAgentDefinition> result =
        agentRepository.findActiveById(requireIdentifier(id, "Agent ID"));
    result.ifPresent(this::assertReadable);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentDefinition create(final AgentUpsertRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Agent request must not be null");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String code = requireCode(request.code());
    agentRepository.findActiveByCodeAndScope(
        code,
        scope.scopeType(),
        scope.tenantId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException("Agent code already exists");
    });
    AiAgentDefinition agent = new AiAgentDefinition();
    agent.setScopeType(scope.scopeType());
    agent.setTenantId(scope.tenantId());
    agent.setCode(code);
    agent.setName(requireText(request.name(), "Agent name", 128));
    agent.setDescription(optionalText(request.description(), 512));
    agent.setEnabled(request.enabled() == null || request.enabled());
    agent.setStatus(Boolean.TRUE.equals(agent.getEnabled())
        ? AgentStatus.DRAFT : AgentStatus.DISABLED);
    AiAgentDefinition saved = agentRepository.save(agent);
    AgentOperationAudit.success("CREATE", saved.getId(), null, saved.getCode());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentDefinition update(
      final String id,
      final AgentUpsertRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Agent request must not be null");
    }
    AiAgentDefinition agent = requireManagedAgent(id, true);
    if (request.code() != null
        && !request.code().isBlank()
        && !agent.getCode().equals(
            request.code().trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Agent code is immutable");
    }
    agent.setName(requireText(request.name(), "Agent name", 128));
    agent.setDescription(optionalText(request.description(), 512));
    if (request.enabled() != null) {
      agent.setEnabled(request.enabled());
    }
    if (!Boolean.TRUE.equals(agent.getEnabled())) {
      agent.setStatus(AgentStatus.DISABLED);
    } else if (agent.getActiveVersionId() != null) {
      agent.setStatus(AgentStatus.ACTIVE);
    } else {
      agent.setStatus(AgentStatus.DRAFT);
    }
    AiAgentDefinition saved = agentRepository.save(agent);
    AgentOperationAudit.success("UPDATE", saved.getId(), null, saved.getCode());
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final String id) {
    AiAgentDefinition agent = requireManagedAgent(id, true);
    if (versionRepository.countActiveByAgentId(agent.getId()) > 0) {
      throw new IllegalStateException(
          "Agent with immutable versions cannot be deleted"
      );
    }
    agent.setDeletedAt(Instant.now());
    agentRepository.save(agent);
    AgentOperationAudit.success("DELETE", agent.getId(), null, agent.getCode());
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiAgentVersion> findVersions(
      final String agentId,
      final Pageable pageable
  ) {
    AiAgentDefinition agent = requireManagedAgent(agentId, false);
    return versionRepository.findAllActiveByAgentId(
        agent.getId(),
        pageable
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiAgentVersion> findVersion(
      final String agentId,
      final String versionId
  ) {
    AiAgentDefinition agent = requireManagedAgent(agentId, false);
    return versionRepository.findActiveByIdAndAgentId(
        requireIdentifier(versionId, "Agent version ID"),
        agent.getId()
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiDependencyOption> findDependencyOptions(
      final String agentId,
      final AiDependencyKind kind,
      final String query,
      final int page,
      final int size
  ) {
    ScopeAssignment owner = requireDependencyOwnerScope(agentId);
    AiDependencyKind dependencyKind = requireAgentDependencyKind(kind);
    Pageable pageable = dependencyPage(page, size);
    String searchPattern = dependencySearchPattern(query);
    Page<AiDependencyOptionView> options = switch (dependencyKind) {
      case MODEL -> modelRepository.searchDependencyOptions(
          owner.scopeType() == AiResourceScope.TENANT,
          owner.tenantId(),
          searchPattern,
          pageable
      );
      case SKILL -> skillVersionRepository.searchDependencyOptions(
          owner.scopeType() == AiResourceScope.TENANT,
          owner.tenantId(),
          searchPattern,
          pageable
      );
      default -> throw new IllegalArgumentException(
          "Unsupported Agent dependency kind"
      );
    };
    return options.map(option -> toSelectableOption(
        dependencyKind,
        option
    ));
  }

  @Override
  @Transactional(readOnly = true)
  public List<AiDependencyOption> resolveDependencyOptions(
      final String agentId,
      final AiDependencyKind kind,
      final Collection<String> optionIds
  ) {
    ScopeAssignment owner = requireDependencyOwnerScope(agentId);
    AiDependencyKind dependencyKind = requireAgentDependencyKind(kind);
    List<String> normalizedIds = dependencyOptionIds(optionIds);
    List<AiDependencyResolutionView> options = switch (dependencyKind) {
      case MODEL -> modelRepository.resolveDependencyOptions(
          owner.scopeType() == AiResourceScope.TENANT,
          owner.tenantId(),
          normalizedIds
      );
      case SKILL -> skillVersionRepository.resolveDependencyOptions(
          owner.scopeType() == AiResourceScope.TENANT,
          owner.tenantId(),
          normalizedIds
      );
      default -> throw new IllegalArgumentException(
          "Unsupported Agent dependency kind"
      );
    };
    return options.stream()
        .map(option -> toResolvedOption(dependencyKind, option))
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentVersion createVersion(
      final String agentId,
      final AgentVersionCreateRequest request
  ) {
    AiAgentDefinition agent = requireManagedAgent(agentId, true);
    NormalizedVersion normalized = normalizeVersion(agent, request);
    versionRepository.findActiveByVersionAndAgentId(
        normalized.version(),
        agent.getId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException("Agent version already exists");
    });

    AiAgentVersion version = new AiAgentVersion();
    version.setAgentId(agent.getId());
    version.setScopeType(agent.getScopeType());
    version.setTenantId(agent.getTenantId());
    version.setVersion(normalized.version());
    version.setManifestSchemaVersion(MANIFEST_SCHEMA_VERSION);
    version.setContentHash(normalized.contentHash());
    version.setManifestJson(normalized.manifestJson());
    version.setPrimaryModelId(normalized.primaryModelId());
    version.setPublicAccess(normalized.publicAccess());
    version.setWorkflowReference(normalized.workflowReference());
    version.setStatus(AgentVersionStatus.DRAFT);
    AiAgentVersion saved = versionRepository.save(version);

    List<AiAgentSkillBinding> bindings = new ArrayList<>();
    for (int index = 0; index < normalized.skillBindings().size(); index++) {
      NormalizedSkillBinding source = normalized.skillBindings().get(index);
      AiAgentSkillBinding binding = new AiAgentSkillBinding();
      binding.setAgentVersionId(saved.getId());
      binding.setScopeType(saved.getScopeType());
      binding.setTenantId(saved.getTenantId());
      binding.setSkillId(source.skillId());
      binding.setSkillVersionId(source.skillVersionId());
      binding.setSkillAlias(source.alias());
      binding.setSkillCode(source.skillCode());
      binding.setSkillVersionName(source.skillVersionName());
      binding.setSkillContentHash(source.skillContentHash());
      binding.setBindingOrder(index);
      bindings.add(bindingRepository.save(binding));
    }
    AiAgentVersion result = decorate(saved, bindings);
    AgentOperationAudit.success(
        "CREATE_VERSION",
        agent.getId(),
        saved.getId(),
        saved.getVersion()
    );
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentVersion publishVersion(
      final String agentId,
      final String versionId
  ) {
    AiAgentDefinition agent = requireManagedAgent(agentId, true);
    if (!Boolean.TRUE.equals(agent.getEnabled())) {
      throw new IllegalStateException("Disabled Agent cannot publish a version");
    }
    AiAgentVersion version = requireVersionForUpdate(agent, versionId);
    if (version.getStatus() == AgentVersionStatus.DEPRECATED) {
      throw new IllegalStateException(
          "Deprecated Agent version cannot be published"
      );
    }
    revalidateDependencies(version);
    if (version.getStatus() == AgentVersionStatus.DRAFT) {
      version.setStatus(AgentVersionStatus.PUBLISHED);
      version.setPublishedAt(Instant.now());
      versionRepository.save(version);
    }
    agent.setActiveVersionId(version.getId());
    agent.setStatus(AgentStatus.ACTIVE);
    agentRepository.save(agent);
    AiAgentVersion result = decorate(version);
    AgentOperationAudit.success(
        "PUBLISH_VERSION",
        agent.getId(),
        version.getId(),
        version.getVersion()
    );
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentVersion deprecateVersion(
      final String agentId,
      final String versionId
  ) {
    AiAgentDefinition agent = requireManagedAgent(agentId, true);
    AiAgentVersion version = requireVersionForUpdate(agent, versionId);
    if (version.getStatus() == AgentVersionStatus.DRAFT) {
      throw new IllegalStateException("Draft Agent version cannot be deprecated");
    }
    if (version.getStatus() != AgentVersionStatus.DEPRECATED) {
      version.setStatus(AgentVersionStatus.DEPRECATED);
      version.setDeprecatedAt(Instant.now());
      versionRepository.save(version);
    }
    if (version.getId().equals(agent.getActiveVersionId())) {
      agent.setActiveVersionId(null);
      agent.setStatus(Boolean.TRUE.equals(agent.getEnabled())
          ? AgentStatus.DRAFT : AgentStatus.DISABLED);
      agentRepository.save(agent);
    }
    AiAgentVersion result = decorate(version);
    AgentOperationAudit.success(
        "DEPRECATE_VERSION",
        agent.getId(),
        version.getId(),
        version.getVersion()
    );
    return result;
  }

  private NormalizedVersion normalizeVersion(
      final AiAgentDefinition agent,
      final AgentVersionCreateRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Agent version request must not be null"
      );
    }
    final String version = requirePattern(
        request.version(),
        "Agent semantic version",
        SEMANTIC_VERSION
    );
    Map<String, Object> manifest = requireMap(
        request.manifest(),
        "Agent manifest"
    );
    assertNoExecutableCode(manifest);
    assertAllowedKeys(
        manifest,
        Set.of("apiVersion", "kind", "metadata", "spec"),
        "Agent manifest"
    );
    requireEquals(manifest.get("apiVersion"), API_VERSION, "Manifest apiVersion");
    requireEquals(manifest.get("kind"), KIND, "Manifest kind");

    Map<String, Object> metadata =
        requireMap(manifest.get("metadata"), "Manifest metadata");
    assertAllowedKeys(
        metadata,
        Set.of("name", "version", "title", "description", "labels"),
        "Manifest metadata"
    );
    requireEquals(metadata.get("name"), agent.getCode(),
        "Manifest metadata.name");
    requireEquals(metadata.get("version"), version,
        "Manifest metadata.version");

    Map<String, Object> spec = requireMap(manifest.get("spec"), "Manifest spec");
    assertAllowedKeys(
        spec,
        Set.of(
            "systemPrompt",
            "model",
            "skills",
            "behavior",
            "memory",
            "budgets",
            "approvals",
            "humanIntervention",
            "inputSchema",
            "outputSchema",
            "publicAccess",
            "workflowRef"
        ),
        "Manifest spec"
    );
    requireText(spec.get("systemPrompt"), "System prompt", 32768);
    final Map<String, Object> modelSelector =
        normalizeModelSelector(agent, spec.get("model"));
    final List<NormalizedSkillBinding> skillBindings =
        normalizeSkillBindings(agent, spec.get("skills"));
    normalizeBehavior(spec.get("behavior"));
    normalizeMemory(spec.get("memory"));
    normalizeBudgets(spec.get("budgets"));
    validateMemoryBudgetCompatibility(spec);
    normalizeApprovals(spec.get("approvals"));
    normalizeHumanIntervention(spec.get("humanIntervention"));

    Map<String, Object> inputSchema =
        requireObjectSchema(spec.get("inputSchema"), "Input schema");
    Map<String, Object> outputSchema =
        requireObjectSchema(spec.get("outputSchema"), "Output schema");
    schemaValidator.validateSchema(inputSchema, "Agent input schema");
    schemaValidator.validateSchema(outputSchema, "Agent output schema");

    boolean publicAccess = optionalBoolean(
        spec.get("publicAccess"),
        "Manifest spec.publicAccess",
        false
    );
    String workflowReference = optionalPattern(
        spec.get("workflowRef"),
        "Manifest spec.workflowRef",
        IDENTIFIER
    );
    String manifestJson = writeJson(manifest);
    if (manifestJson.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_MANIFEST_BYTES) {
      throw new IllegalArgumentException("Agent manifest is too large");
    }
    return new NormalizedVersion(
        version,
        manifest,
        manifestJson,
        String.valueOf(modelSelector.get("primaryModelId")),
        publicAccess,
        workflowReference,
        sha256(manifestJson),
        skillBindings
    );
  }

  private Map<String, Object> normalizeModelSelector(
      final AiAgentDefinition agent,
      final Object value
  ) {
    Map<String, Object> selector = requireMap(value, "Manifest spec.model");
    assertAllowedKeys(
        selector,
        Set.of("primaryModelId", "fallbackModelIds"),
        "Manifest spec.model"
    );
    String primaryModelId = requireIdentifier(
        selector.get("primaryModelId"),
        "Primary model ID"
    );
    List<String> fallbackModelIds = stringList(
        selector.get("fallbackModelIds"),
        "Fallback model IDs",
        8
    );
    Set<String> modelIds = new HashSet<>();
    modelIds.add(primaryModelId);
    for (String fallbackModelId : fallbackModelIds) {
      if (!modelIds.add(fallbackModelId)) {
        throw new IllegalArgumentException(
            "Agent model selector contains duplicate model IDs"
        );
      }
    }
    modelIds.forEach(modelId -> requireUsableModel(agent, modelId));
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("primaryModelId", primaryModelId);
    normalized.put("fallbackModelIds", fallbackModelIds);
    return normalized;
  }

  private List<NormalizedSkillBinding> normalizeSkillBindings(
      final AiAgentDefinition agent,
      final Object value
  ) {
    List<Map<String, Object>> values =
        value == null ? List.of() : mapList(value, "Manifest spec.skills");
    if (values.size() > MAXIMUM_SKILL_BINDINGS) {
      throw new IllegalArgumentException(
          "Agent version exceeds maximum Skill bindings"
      );
    }
    Set<String> aliases = new HashSet<>();
    Set<String> versionIds = new HashSet<>();
    List<NormalizedSkillBinding> bindings = new ArrayList<>();
    for (Map<String, Object> item : values) {
      assertAllowedKeys(
          item,
          Set.of("alias", "skillId", "versionId"),
          "Agent Skill binding"
      );
      String alias = requirePattern(
          item.get("alias"),
          "Skill alias",
          CODE
      );
      if (!aliases.add(alias)) {
        throw new IllegalArgumentException("Skill alias must be unique");
      }
      String skillId = requireIdentifier(item.get("skillId"), "Skill ID");
      String skillVersionId = requireIdentifier(
          item.get("versionId"),
          "Skill version ID"
      );
      if (!versionIds.add(skillVersionId)) {
        throw new IllegalArgumentException(
            "Agent cannot bind one Skill version more than once"
        );
      }
      AiSkillDefinition skill = requireUsableSkill(agent, skillId);
      AiSkillVersion skillVersion = requirePublishedSkillVersion(
          agent,
          skill,
          skillVersionId
      );
      bindings.add(new NormalizedSkillBinding(
          alias,
          skill.getId(),
          skillVersion.getId(),
          skill.getCode(),
          skillVersion.getVersion(),
          skillVersion.getContentHash()
      ));
    }
    return List.copyOf(bindings);
  }

  private void normalizeBehavior(final Object value) {
    Map<String, Object> behavior = value == null
        ? Map.of() : requireMap(value, "Manifest spec.behavior");
    assertAllowedKeys(
        behavior,
        Set.of("instructions", "responseStyle"),
        "Manifest spec.behavior"
    );
    if (behavior.get("instructions") != null) {
      List<String> instructions = textList(
          behavior.get("instructions"),
          "Behavior instructions",
          64,
          1024
      );
    }
    if (behavior.get("responseStyle") != null) {
      requireText(behavior.get("responseStyle"), "Response style", 128);
    }
  }

  private void normalizeMemory(final Object value) {
    Map<String, Object> memory =
        requireMap(value, "Manifest spec.memory");
    assertAllowedKeys(
        memory,
        Set.of(
            "shortTermEnabled",
            "longTermEnabled",
            "maximumMessages",
            "maximumSummaryCharacters",
            "longTermScope",
            "maximumLongTermEntries",
            "longTermRetrievalTopK",
            "longTermScoreThreshold",
            "maximumLongTermInjectionCharacters",
            "maximumLongTermRecordCharacters",
            "longTermRetentionDays"
        ),
        "Manifest spec.memory"
    );
    requireBoolean(memory.get("shortTermEnabled"), "shortTermEnabled");
    requireBoolean(memory.get("longTermEnabled"), "longTermEnabled");
    requireIntegerRange(
        memory.get("maximumMessages"),
        "maximumMessages",
        1,
        1000
    );
    if (memory.get("maximumSummaryCharacters") != null) {
      requireIntegerRange(
          memory.get("maximumSummaryCharacters"),
          "maximumSummaryCharacters",
          1024,
          32768
      );
    }
    if (memory.get("longTermScope") != null
        && !"SUBJECT".equals(String.valueOf(memory.get("longTermScope")))) {
      throw new IllegalArgumentException(
          "longTermScope currently supports only SUBJECT"
      );
    }
    optionalIntegerRange(
        memory,
        "maximumLongTermEntries",
        1,
        10000
    );
    optionalIntegerRange(memory, "longTermRetrievalTopK", 1, 20);
    if (memory.get("longTermScoreThreshold") != null) {
      requireDecimalRange(
          memory.get("longTermScoreThreshold"),
          "longTermScoreThreshold",
          0.0D,
          1.0D
      );
    }
    optionalIntegerRange(
        memory,
        "maximumLongTermInjectionCharacters",
        512,
        32768
    );
    optionalIntegerRange(
        memory,
        "maximumLongTermRecordCharacters",
        512,
        32768
    );
    optionalIntegerRange(memory, "longTermRetentionDays", 1, 3650);
  }

  private static void optionalIntegerRange(
      final Map<String, Object> values,
      final String field,
      final int minimum,
      final int maximum
  ) {
    if (values.get(field) != null) {
      requireIntegerRange(values.get(field), field, minimum, maximum);
    }
  }

  private void validateMemoryBudgetCompatibility(
      final Map<String, Object> spec
  ) {
    Map<String, Object> memory =
        requireMap(spec.get("memory"), "Manifest spec.memory");
    Map<String, Object> budgets =
        requireMap(spec.get("budgets"), "Manifest spec.budgets");
    int maximumMessages = requireIntegerRange(
        memory.get("maximumMessages"),
        "maximumMessages",
        1,
        1000
    );
    int maximumConcurrency = requireIntegerRange(
        budgets.get("maximumConcurrency"),
        "maximumConcurrency",
        1,
        32
    );
    if (maximumMessages < maximumConcurrency + 2) {
      throw new IllegalArgumentException(
          "maximumMessages must reserve the user, Skill calls, "
              + "and Skill results for maximumConcurrency"
      );
    }
  }

  private void normalizeBudgets(final Object value) {
    Map<String, Object> budgets =
        requireMap(value, "Manifest spec.budgets");
    assertAllowedKeys(
        budgets,
        Set.of(
            "maximumSteps",
            "maximumLoopDepth",
            "maximumConcurrency",
            "maximumInputTokens",
            "maximumOutputTokens",
            "maximumCost"
        ),
        "Manifest spec.budgets"
    );
    requireIntegerRange(budgets.get("maximumSteps"),
        "maximumSteps", 1, 256);
    requireIntegerRange(budgets.get("maximumLoopDepth"),
        "maximumLoopDepth", 0, 32);
    requireIntegerRange(budgets.get("maximumConcurrency"),
        "maximumConcurrency", 1, 32);
    requireIntegerRange(budgets.get("maximumInputTokens"),
        "maximumInputTokens", 1, 10_000_000);
    requireIntegerRange(budgets.get("maximumOutputTokens"),
        "maximumOutputTokens", 1, 1_000_000);
    requireDecimalRange(budgets.get("maximumCost"),
        "maximumCost", 0D, 1_000_000D);
  }

  private void normalizeApprovals(final Object value) {
    Map<String, Object> approvals =
        requireMap(value, "Manifest spec.approvals");
    assertAllowedKeys(
        approvals,
        Set.of("execution"),
        "Manifest spec.approvals"
    );
    Map<String, Object> execution = requireMap(
        approvals.get("execution"),
        "Manifest spec.approvals.execution"
    );
    assertAllowedKeys(
        execution,
        Set.of("required", "allowSelfApproval", "instructions"),
        "Manifest spec.approvals.execution"
    );
    requireBoolean(execution.get("required"), "Approval required");
    requireBoolean(
        execution.get("allowSelfApproval"),
        "Allow self approval"
    );
    if (execution.get("instructions") != null) {
      requireText(
          execution.get("instructions"),
          "Approval instructions",
          512
      );
    }
  }

  private void normalizeHumanIntervention(final Object value) {
    if (value == null) {
      return;
    }
    Map<String, Object> policy = requireMap(
        value,
        "Manifest spec.humanIntervention"
    );
    assertAllowedKeys(
        policy,
        Set.of(
            "enabled",
            "maximumRequests",
            "timeoutSeconds",
            "timeoutAction"
        ),
        "Manifest spec.humanIntervention"
    );
    requireBoolean(
        policy.get("enabled"),
        "Human intervention enabled"
    );
    requireIntegerRange(
        policy.get("maximumRequests"),
        "Human intervention maximumRequests",
        1,
        32
    );
    requireIntegerRange(
        policy.get("timeoutSeconds"),
        "Human intervention timeoutSeconds",
        60,
        604_800
    );
    String timeoutAction = requireText(
        policy.get("timeoutAction"),
        "Human intervention timeoutAction",
        16
    );
    if (!Set.of("FAIL", "CANCEL").contains(timeoutAction)) {
      throw new IllegalArgumentException(
          "Human intervention timeoutAction must be FAIL or CANCEL"
      );
    }
  }

  private AiModelDefinition requireUsableModel(
      final AiAgentDefinition agent,
      final String modelId
  ) {
    AiModelDefinition model = modelRepository.findActiveById(modelId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Agent model does not exist: " + modelId
        ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        model.getScopeType(),
        model.getTenantId(),
        agent.getScopeType(),
        agent.getTenantId()
    )) {
      throw new IllegalArgumentException(
          "Agent model is outside the Agent scope: " + modelId
      );
    }
    if (!Boolean.TRUE.equals(model.getEnabled())
        || !Boolean.TRUE.equals(model.getAvailable())) {
      throw new IllegalArgumentException(
          "Agent model is not enabled and available: " + modelId
      );
    }
    if (model.getModelType() != AiModelType.LLM
        && model.getModelType() != AiModelType.MULTIMODAL) {
      throw new IllegalArgumentException(
          "Agent model must be LLM or MULTIMODAL: " + modelId
      );
    }
    return model;
  }

  private AiSkillDefinition requireUsableSkill(
      final AiAgentDefinition agent,
      final String skillId
  ) {
    AiSkillDefinition skill = skillRepository.findActiveById(skillId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Agent Skill does not exist: " + skillId
        ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        skill.getScopeType(),
        skill.getTenantId(),
        agent.getScopeType(),
        agent.getTenantId()
    )) {
      throw new IllegalArgumentException(
          "Agent Skill is outside the Agent scope: " + skillId
      );
    }
    if (!Boolean.TRUE.equals(skill.getEnabled())) {
      throw new IllegalArgumentException(
          "Agent Skill is disabled: " + skillId
      );
    }
    return skill;
  }

  private AiSkillVersion requirePublishedSkillVersion(
      final AiAgentDefinition agent,
      final AiSkillDefinition skill,
      final String skillVersionId
  ) {
    AiSkillVersion version = skillVersionRepository
        .findActiveByIdAndSkillId(skillVersionId, skill.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Agent Skill version does not exist: " + skillVersionId
        ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        version.getScopeType(),
        version.getTenantId(),
        agent.getScopeType(),
        agent.getTenantId()
    ) || version.getStatus() != SkillVersionStatus.PUBLISHED) {
      throw new IllegalArgumentException(
          "Agent Skill version must be visible and published: "
              + skillVersionId
      );
    }
    return version;
  }

  private void revalidateDependencies(final AiAgentVersion version) {
    AiAgentDefinition owner = requireManagedAgent(version.getAgentId(), true);
    Map<String, Object> manifest = readMap(version.getManifestJson());
    Map<String, Object> spec =
        requireMap(manifest.get("spec"), "Stored Agent manifest spec");
    normalizeModelSelector(owner, spec.get("model"));
    for (AiAgentSkillBinding binding
        : bindingRepository.findAllActiveByAgentVersionId(version.getId())) {
      AiSkillDefinition skill = requireUsableSkill(
          owner,
          binding.getSkillId()
      );
      AiSkillVersion skillVersion = requirePublishedSkillVersion(
          owner,
          skill,
          binding.getSkillVersionId()
      );
      if (!binding.getSkillContentHash().equals(
          skillVersion.getContentHash())) {
        throw new IllegalStateException(
            "Pinned Skill version content hash has changed"
        );
      }
    }
  }

  private ScopeAssignment requireDependencyOwnerScope(final String id) {
    try {
      AiAgentDefinition owner = agentRepository.findActiveById(
          requireIdentifier(id, "Agent ID")
      ).orElseThrow(() -> new AccessDeniedException(
          "Dependency owner is not accessible"
      ));
      ScopeAssignment scope = normalizedOwnerScope(
          owner.getScopeType(),
          owner.getTenantId()
      );
      scopeAccessPolicy.assertCanReadManagedResource(
          scope.scopeType(),
          scope.tenantId()
      );
      return scope;
    } catch (AccessDeniedException | IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "Dependency owner is not accessible"
      );
    }
  }

  private static ScopeAssignment normalizedOwnerScope(
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    if (scopeType == null) {
      if (tenantId != null) {
        throw new AccessDeniedException(
            "Dependency owner is not accessible"
        );
      }
      return new ScopeAssignment(AiResourceScope.SYSTEM, null);
    }
    if (scopeType == AiResourceScope.SYSTEM) {
      if (tenantId != null) {
        throw new AccessDeniedException(
            "Dependency owner is not accessible"
        );
      }
      return new ScopeAssignment(AiResourceScope.SYSTEM, null);
    }
    if (tenantId == null
        || tenantId.isBlank()
        || !tenantId.equals(tenantId.trim())) {
      throw new AccessDeniedException(
          "Dependency owner is not accessible"
      );
    }
    return new ScopeAssignment(AiResourceScope.TENANT, tenantId);
  }

  private static AiDependencyKind requireAgentDependencyKind(
      final AiDependencyKind kind
  ) {
    if (kind != AiDependencyKind.MODEL
        && kind != AiDependencyKind.SKILL) {
      throw new IllegalArgumentException(
          "Agent dependency kind must be MODEL or SKILL"
      );
    }
    return kind;
  }

  private static Pageable dependencyPage(
      final int page,
      final int size
  ) {
    if (page < 0) {
      throw new IllegalArgumentException(
          "Dependency page must not be negative"
      );
    }
    if (size < 1 || size > MAXIMUM_DEPENDENCY_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "Dependency page size must be between 1 and 50"
      );
    }
    return PageRequest.of(page, size);
  }

  private static String dependencySearchPattern(final String query) {
    String normalized = query == null ? "" : query.trim();
    if (normalized.length() > MAXIMUM_DEPENDENCY_QUERY_LENGTH) {
      throw new IllegalArgumentException(
          "Dependency search query is too long"
      );
    }
    String escaped = normalized.toLowerCase(Locale.ROOT)
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_");
    return "%" + escaped + "%";
  }

  private static List<String> dependencyOptionIds(
      final Collection<String> optionIds
  ) {
    if (optionIds == null || optionIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Dependency option IDs must not be empty"
      );
    }
    if (optionIds.size() > MAXIMUM_DEPENDENCY_RESOLVE_IDS) {
      throw new IllegalArgumentException(
          "Dependency resolve supports at most 128 IDs"
      );
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    optionIds.forEach(optionId -> normalized.add(
        requireIdentifier(optionId, "Dependency option ID")
    ));
    return List.copyOf(normalized);
  }

  private static AiDependencyOption toSelectableOption(
      final AiDependencyKind kind,
      final AiDependencyOptionView option
  ) {
    return new AiDependencyOption(
        kind,
        option.getResourceId(),
        option.getResourceCode(),
        option.getResourceName(),
        option.getResourceVersionId(),
        option.getResourceVersion(),
        effectiveDependencyScope(option.getScopeType()),
        option.getPublishedAt(),
        true,
        null
    );
  }

  private static AiDependencyOption toResolvedOption(
      final AiDependencyKind kind,
      final AiDependencyResolutionView option
  ) {
    boolean selectable = option.getSelectable();
    AiDependencyAvailabilityCode availabilityCode =
        dependencyAvailabilityCode(
            selectable,
            option.getAvailabilityCode()
        );
    return new AiDependencyOption(
        kind,
        option.getResourceId(),
        option.getResourceCode(),
        option.getResourceName(),
        option.getResourceVersionId(),
        option.getResourceVersion(),
        effectiveDependencyScope(option.getScopeType()),
        option.getPublishedAt(),
        selectable,
        availabilityCode
    );
  }

  private static AiDependencyAvailabilityCode dependencyAvailabilityCode(
      final boolean selectable,
      final String value
  ) {
    if (selectable) {
      if (value != null) {
        throw new IllegalStateException(
            "Selectable dependency must not have an availability code"
        );
      }
      return null;
    }
    try {
      return AiDependencyAvailabilityCode.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IllegalStateException(
          "Unavailable dependency has an invalid availability code",
          ex
      );
    }
  }

  private static AiResourceScope effectiveDependencyScope(
      final AiResourceScope scopeType
  ) {
    return scopeType == null ? AiResourceScope.SYSTEM : scopeType;
  }

  private AiAgentDefinition requireManagedAgent(
      final String id,
      final boolean forUpdate
  ) {
    String normalized = requireIdentifier(id, "Agent ID");
    AiAgentDefinition agent = (forUpdate
        ? agentRepository.findActiveByIdForUpdate(normalized)
        : agentRepository.findActiveById(normalized))
        .orElseThrow(() -> new IllegalArgumentException(
            "Agent does not exist"
        ));
    if (forUpdate) {
      scopeAccessPolicy.assertCanManageOwnedResource(
          agent.getScopeType(),
          agent.getTenantId()
      );
    } else {
      assertReadable(agent);
    }
    return agent;
  }

  private AiAgentVersion requireVersionForUpdate(
      final AiAgentDefinition agent,
      final String versionId
  ) {
    return versionRepository.findActiveByIdAndAgentIdForUpdate(
        requireIdentifier(versionId, "Agent version ID"),
        agent.getId()
    ).orElseThrow(() -> new IllegalArgumentException(
        "Agent version does not exist"
    ));
  }

  private void assertReadable(final AiAgentDefinition agent) {
    scopeAccessPolicy.assertCanReadManagedResource(
        agent.getScopeType(),
        agent.getTenantId()
    );
  }

  private AiAgentVersion decorate(final AiAgentVersion version) {
    return decorate(
        version,
        bindingRepository.findAllActiveByAgentVersionId(version.getId())
    );
  }

  private AiAgentVersion decorate(
      final AiAgentVersion version,
      final List<AiAgentSkillBinding> bindings
  ) {
    Map<String, Object> manifest = readMap(version.getManifestJson());
    Map<String, Object> spec =
        requireMap(manifest.get("spec"), "Stored Agent manifest spec");
    version.setManifest(manifest);
    version.setModelSelector(readOptionalMap(spec.get("model")));
    version.setInputSchema(readOptionalMap(spec.get("inputSchema")));
    version.setOutputSchema(readOptionalMap(spec.get("outputSchema")));
    version.setMemoryPolicy(readOptionalMap(spec.get("memory")));
    version.setBudget(readOptionalMap(spec.get("budgets")));
    Map<String, Object> approvals =
        readOptionalMap(spec.get("approvals"));
    version.setApprovalPolicy(
        readOptionalMap(approvals.get("execution"))
    );
    version.setHumanInterventionPolicy(
        readOptionalMap(spec.get("humanIntervention"))
    );
    version.setSkillBindings(List.copyOf(bindings));
    return version;
  }

  private Map<String, Object> readMap(final String value) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored Agent manifest is invalid", ex);
    }
  }

  private Map<String, Object> readOptionalMap(final Object value) {
    return value == null ? Map.of() : requireMap(value, "Stored Agent object");
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Agent manifest is not serializable",
          ex
      );
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

  private Map<String, Object> requireObjectSchema(
      final Object value,
      final String label
  ) {
    Map<String, Object> schema = requireMap(value, label);
    requireEquals(schema.get("type"), "object", label + ".type");
    return schema;
  }

  private static List<String> stringList(
      final Object value,
      final String label,
      final int maximumSize
  ) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list) || list.size() > maximumSize) {
      throw new IllegalArgumentException(label + " must be a bounded array");
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      result.add(requireIdentifier(item, label + " item"));
    }
    return List.copyOf(result);
  }

  private static List<String> textList(
      final Object value,
      final String label,
      final int maximumSize,
      final int maximumItemLength
  ) {
    if (!(value instanceof List<?> list) || list.size() > maximumSize) {
      throw new IllegalArgumentException(label + " must be a bounded array");
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      result.add(requireText(item, label + " item", maximumItemLength));
    }
    return List.copyOf(result);
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
              "Agent manifest cannot contain executable field: " + key
          );
        }
        assertNoExecutableCode(nested);
      });
    } else if (value instanceof List<?> list) {
      list.forEach(AiAgentServiceImpl::assertNoExecutableCode);
    }
  }

  private static boolean requireBoolean(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Boolean result)) {
      throw new IllegalArgumentException(label + " must be boolean");
    }
    return result;
  }

  private static boolean optionalBoolean(
      final Object value,
      final String label,
      final boolean fallback
  ) {
    return value == null ? fallback : requireBoolean(value, label);
  }

  private static int requireIntegerRange(
      final Object value,
      final String label,
      final int minimum,
      final int maximum
  ) {
    if (!(value instanceof Number number)
        || number.doubleValue() != Math.rint(number.doubleValue())
        || number.longValue() < minimum
        || number.longValue() > maximum) {
      throw new IllegalArgumentException(
          label + " must be between " + minimum + " and " + maximum
      );
    }
    return number.intValue();
  }

  private static double requireDecimalRange(
      final Object value,
      final String label,
      final double minimum,
      final double maximum
  ) {
    if (!(value instanceof Number number)
        || !Double.isFinite(number.doubleValue())
        || number.doubleValue() < minimum
        || number.doubleValue() > maximum) {
      throw new IllegalArgumentException(
          label + " must be between " + minimum + " and " + maximum
      );
    }
    return number.doubleValue();
  }

  private static String requireCode(final Object value) {
    return requirePattern(value, "Agent code", CODE)
        .toLowerCase(Locale.ROOT);
  }

  private static String requireIdentifier(
      final Object value,
      final String label
  ) {
    return requirePattern(value, label, IDENTIFIER);
  }

  private static String optionalPattern(
      final Object value,
      final String label,
      final Pattern pattern
  ) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    return requirePattern(value, label, pattern);
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
      throw new IllegalArgumentException("Agent description is too long");
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

  private record NormalizedSkillBinding(
      String alias,
      String skillId,
      String skillVersionId,
      String skillCode,
      String skillVersionName,
      String skillContentHash
  ) {
  }

  private record NormalizedVersion(
      String version,
      Map<String, Object> manifest,
      String manifestJson,
      String primaryModelId,
      boolean publicAccess,
      String workflowReference,
      String contentHash,
      List<NormalizedSkillBinding> skillBindings
  ) {
  }
}
