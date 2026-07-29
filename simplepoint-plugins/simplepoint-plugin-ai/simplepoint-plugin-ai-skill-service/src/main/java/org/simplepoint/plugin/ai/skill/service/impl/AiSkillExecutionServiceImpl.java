package org.simplepoint.plugin.ai.skill.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware submission and query service for durable Skill workflows.
 */
@Service
public class AiSkillExecutionServiceImpl implements AiSkillExecutionService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiSkillToolBindingRepository bindingRepository;

  private final AiSkillExecutionRepository executionRepository;

  private final AiSkillExecutionStepRepository stepRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillWorkflowTemplateResolver templateResolver;

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the durable Skill execution service.
   */
  public AiSkillExecutionServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository versionRepository,
      final AiSkillToolBindingRepository bindingRepository,
      final AiSkillExecutionRepository executionRepository,
      final AiSkillExecutionStepRepository stepRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillWorkflowTemplateResolver templateResolver,
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.skillRepository = skillRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.templateResolver = templateResolver;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillExecution start(
      final String skillId,
      final SkillExecutionStartRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Skill execution request must not be null"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkillForUpdate(skillId, scope);
    if (!Boolean.TRUE.equals(skill.getEnabled())
        || skill.getActiveVersionId() == null) {
      throw new IllegalStateException("Skill has no active published version");
    }
    AiSkillVersion version = versionRepository.findActiveById(
        skill.getActiveVersionId()
    ).filter(candidate -> skill.getId().equals(candidate.getSkillId()))
        .orElseThrow(() -> new IllegalStateException(
            "Active Skill version does not exist"
        ));
    if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
      throw new IllegalStateException("Active Skill version is not published");
    }
    String idempotencyKey = requireIdempotencyKey(request.idempotencyKey());
    String idempotencyHash = sha256(idempotencyKey);
    Map<String, Object> input = request.input() == null
        ? Map.of() : new LinkedHashMap<>(request.input());
    String inputJson = writeJson(input);
    assertPayloadSize(inputJson, "Skill execution input");
    schemaValidator.validate(
        readMap(version.getInputSchemaJson(), "Skill input Schema"),
        input,
        "Skill input"
    );
    Optional<AiSkillExecution> existing =
        executionRepository.findActiveByIdempotency(
            skill.getId(),
            scope.scopeType(),
            scope.tenantId(),
            idempotencyHash
        );
    if (existing.isPresent()) {
      AiSkillExecution execution = existing.orElseThrow();
      if (!sha256(inputJson).equals(execution.getInputHash())) {
        throw new IllegalArgumentException(
            "Idempotency key was already used with different input"
        );
      }
      return decorate(execution);
    }

    Map<String, Object> workflow = readMap(
        version.getWorkflowJson(),
        "Skill workflow"
    );
    List<Map<String, Object>> workflowSteps = readObjectList(
        workflow.get("steps"),
        "Skill workflow steps"
    );
    if (workflowSteps.isEmpty()) {
      throw new IllegalStateException(
          "Skill workflow must contain at least one executable step"
      );
    }
    Map<String, AiSkillToolBinding> bindings = new HashMap<>();
    bindingRepository.findAllActiveBySkillVersionId(version.getId())
        .forEach(binding -> bindings.put(binding.getToolAlias(), binding));

    AiSkillExecution execution = new AiSkillExecution();
    execution.setSkillId(skill.getId());
    execution.setSkillVersionId(version.getId());
    execution.setScopeType(scope.scopeType());
    execution.setTenantId(scope.tenantId());
    execution.setIdempotencyKeyHash(idempotencyHash);
    execution.setInputHash(sha256(inputJson));
    execution.setInputJson(inputJson);
    execution.setOutputTemplateJson(workflow.containsKey("output")
        ? writeJson(workflow.get("output")) : null);
    execution.setOutputSchemaJson(version.getOutputSchemaJson());
    execution.setStatus(SkillExecutionStatus.PENDING);
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    execution.setRequestedBy(currentUserId());
    execution = executionRepository.save(execution);

    List<String> availableSteps = new ArrayList<>();
    List<AiSkillExecutionStep> savedSteps = new ArrayList<>();
    for (int index = 0; index < workflowSteps.size(); index++) {
      Map<String, Object> source = workflowSteps.get(index);
      final String stepId = required(
          source.get("id"),
          "Workflow step ID",
          64
      );
      String stepType = required(source.get("type"), "Workflow step type", 32);
      if (!"tool".equals(stepType)) {
        throw new IllegalStateException(
            "Workflow executor does not support step type yet: " + stepType
        );
      }
      String alias = required(source.get("tool"), "Workflow Tool alias", 64);
      AiSkillToolBinding binding = bindings.get(alias);
      if (binding == null) {
        throw new IllegalStateException(
            "Workflow Tool binding does not exist: " + alias
        );
      }
      Object arguments = source.get("arguments");
      if (arguments != null && !(arguments instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(
            "Workflow Tool arguments must be an object"
        );
      }
      if (arguments != null) {
        templateResolver.validateTemplate(
            arguments,
            availableSteps,
            "Workflow step " + stepId + " arguments"
        );
      }
      AiSkillExecutionStep step = new AiSkillExecutionStep();
      step.setExecutionId(execution.getId());
      step.setStepId(stepId);
      step.setStepType(stepType);
      step.setStepOrder(index);
      step.setToolBindingId(binding.getId());
      step.setToolAlias(binding.getToolAlias());
      step.setMcpServerId(binding.getMcpServerId());
      step.setCapabilitySnapshotId(binding.getCapabilitySnapshotId());
      step.setToolName(binding.getToolName());
      step.setInputSchemaHash(binding.getInputSchemaHash());
      step.setArgumentsTemplateJson(
          arguments == null ? null : writeJson(arguments)
      );
      step.setStatus(SkillExecutionStepStatus.PENDING);
      step.setAttemptCount(0);
      savedSteps.add(stepRepository.save(step));
      availableSteps.add(stepId);
    }
    if (workflow.containsKey("output")) {
      templateResolver.validateTemplate(
          workflow.get("output"),
          availableSteps,
          "Workflow output"
      );
    }
    return decorate(execution, savedSteps);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiSkillExecution> findAll(
      final String skillId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findAllActiveBySkillAndScope(
        skill.getId(),
        scope.scopeType(),
        scope.tenantId(),
        pageable
    ).map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiSkillExecution> find(
      final String skillId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiSkillDefinition skill = requireSkill(skillId, scope);
    return executionRepository.findActiveById(
        required(executionId, "Skill execution ID", 64)
    ).filter(execution -> skill.getId().equals(execution.getSkillId()))
        .filter(execution -> execution.getScopeType() == scope.scopeType())
        .filter(execution -> java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        ))
        .map(this::decorate);
  }

  private AiSkillDefinition requireSkill(
      final String skillId,
      final ScopeAssignment scope
  ) {
    AiSkillDefinition skill = skillRepository.findActiveById(
        required(skillId, "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (skill.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(skill.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException("Skill does not exist");
    }
    return skill;
  }

  private AiSkillDefinition requireSkillForUpdate(
      final String skillId,
      final ScopeAssignment scope
  ) {
    AiSkillDefinition skill = skillRepository.findActiveByIdForUpdate(
        required(skillId, "Skill ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException("Skill does not exist"));
    if (skill.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(skill.getTenantId(), scope.tenantId())) {
      throw new IllegalArgumentException("Skill does not exist");
    }
    return skill;
  }

  private AiSkillExecution decorate(final AiSkillExecution execution) {
    return decorate(
        execution,
        stepRepository.findAllActiveByExecutionId(execution.getId())
    );
  }

  private AiSkillExecution decorate(
      final AiSkillExecution execution,
      final List<AiSkillExecutionStep> steps
  ) {
    execution.setInput(readMap(execution.getInputJson(), "Skill input"));
    execution.setOutput(execution.getOutputJson() == null
        ? null : readValue(execution.getOutputJson(), "Skill output"));
    steps.forEach(step -> {
      step.setInput(step.getInputJson() == null
          ? null : readMap(step.getInputJson(), "Skill step input"));
      step.setOutput(step.getOutputJson() == null
          ? null : readValue(step.getOutputJson(), "Skill step output"));
    });
    execution.setSteps(List.copyOf(steps));
    return execution;
  }

  private Map<String, Object> readMap(
      final String json,
      final String label
  ) {
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private Object readValue(final String json, final String label) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private List<Map<String, Object>> readObjectList(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(label + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalStateException(label + " item must be an object");
      }
      result.add(objectMapper.convertValue(map, MAP_TYPE));
    }
    return List.copyOf(result);
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Skill workflow value is not valid JSON",
          ex
      );
    }
  }

  private void assertPayloadSize(final String json, final String label) {
    int maximum = properties.getMaximumPayloadBytes() == null
        ? 256 * 1024 : properties.getMaximumPayloadBytes();
    if (maximum < 1024
        || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }

  private static String requireIdempotencyKey(final String value) {
    return required(value, "Skill execution idempotency key", 128);
  }

  private static String required(
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

  private static String currentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
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
}
