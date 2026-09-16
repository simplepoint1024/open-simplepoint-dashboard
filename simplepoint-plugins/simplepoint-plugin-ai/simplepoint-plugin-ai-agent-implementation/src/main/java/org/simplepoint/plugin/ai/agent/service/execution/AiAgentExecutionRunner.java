package org.simplepoint.plugin.ai.agent.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationActorRole;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentSkillBindingRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentMemoryService;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryContext;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ModelCheckpoint;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillFailureCheckpoint;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillLaunchCheckpoint;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillResultCheckpoint;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiInvocationRecordRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.springframework.stereotype.Service;

/**
 * Executes the fenced Agent reasoning loop without direct MCP Tool access.
 */
@Service
public class AiAgentExecutionRunner {

  private static final String TENANT_ID_ATTRIBUTE = "X-Tenant-Id";

  private static final TypeReference<List<Message>> MESSAGE_LIST =
      new TypeReference<>() {
      };

  private static final TypeReference<List<PendingSkillCall>> CALL_LIST =
      new TypeReference<>() {
      };

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiAgentExecutionCoordinator coordinator;

  private final AiAgentExecutionRepository executionRepository;

  private final AiAgentVersionRepository versionRepository;

  private final AiAgentSkillBindingRepository bindingRepository;

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillVersionRepository skillVersionRepository;

  private final AiSkillExecutionRepository skillExecutionRepository;

  private final AiGenerationService generationService;

  private final AiInvocationRecordRepository invocationRepository;

  private final AiAgentMemoryService memoryService;

  private final SkillJsonSchemaValidator schemaValidator;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the Agent execution runner.
   */
  public AiAgentExecutionRunner(
      final AiAgentExecutionCoordinator coordinator,
      final AiAgentExecutionRepository executionRepository,
      final AiAgentVersionRepository versionRepository,
      final AiAgentSkillBindingRepository bindingRepository,
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillVersionRepository skillVersionRepository,
      final AiSkillExecutionRepository skillExecutionRepository,
      final AiGenerationService generationService,
      final AiInvocationRecordRepository invocationRepository,
      final AiAgentMemoryService memoryService,
      final SkillJsonSchemaValidator schemaValidator,
      final ObjectMapper objectMapper
  ) {
    this.coordinator = coordinator;
    this.executionRepository = executionRepository;
    this.versionRepository = versionRepository;
    this.bindingRepository = bindingRepository;
    this.skillRepository = skillRepository;
    this.skillVersionRepository = skillVersionRepository;
    this.skillExecutionRepository = skillExecutionRepository;
    this.generationService = generationService;
    this.invocationRepository = invocationRepository;
    this.memoryService = memoryService;
    this.schemaValidator = schemaValidator;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Runs until completion or the next durable child Skill wait.
   */
  public void execute(final ExecutionTask task) {
    try {
      for (int transition = 0; transition < 64; transition++) {
        AiAgentExecution execution = requireExecution(task.executionId());
        if (execution.getCurrentSkillExecutionId() != null) {
          if (!resumeSkill(task, execution)) {
            return;
          }
          continue;
        }
        List<PendingSkillCall> pending = pendingCalls(execution);
        if (!pending.isEmpty()) {
          if (!startNextSkill(task, execution, pending.getFirst())) {
            return;
          }
          continue;
        }
        if (!invokeModel(task, execution)) {
          return;
        }
      }
      coordinator.fail(
          task,
          "AGENT_RUNTIME_TRANSITION_LIMIT",
          "Agent runtime transition safety limit was exhausted"
      );
    } catch (RuntimeException ex) {
      safelyFail(task, "AGENT_RUNTIME_FAILED", "AGENT_RUNTIME_FAILED");
    }
  }

  private boolean invokeModel(
      final ExecutionTask task,
      final AiAgentExecution execution
  ) {
    RuntimeDefinition runtime = runtimeDefinition(execution);
    AiAgentMemoryContext longTermMemory = longTermMemory(task, execution);
    if (longTermMemory.instructions() != null) {
      runtime = new RuntimeDefinition(
          runtime.instructions() + longTermMemory.instructions(),
          runtime.fallbackModelIds(),
          runtime.skills(),
          runtime.outputSchema()
      );
    }
    List<Message> conversation = messages(execution.getConversationJson());
    List<ToolDefinition> tools = runtime.skills().stream()
        .map(RuntimeSkill::tool)
        .toList();
    GenerationRequest request = new GenerationRequest(
        execution.getPrimaryModelId(),
        runtime.instructions(),
        conversation,
        Math.max(
            1,
            execution.getMaximumOutputTokens()
                - execution.getConsumedOutputTokens()
        ),
        null,
        null,
        tools,
        null
    );
    List<String> models = new ArrayList<>();
    models.add(execution.getPrimaryModelId());
    models.addAll(runtime.fallbackModelIds());
    for (String modelId : models) {
      GenerationRequest candidate = new GenerationRequest(
          modelId,
          request.instructions(),
          request.messages(),
          request.maxOutputTokens(),
          request.temperature(),
          request.topP(),
          request.tools(),
          request.responseFormat()
      );
      String requestJson = writeJson(candidate);
      String traceId = coordinator.beginModel(
          task,
          modelId,
          requestJson,
          sha256(requestJson)
      );
      if (traceId == null) {
        return false;
      }
      try {
        GenerationResult result = withExecutionContext(
            execution,
            () -> generationService.generate(candidate)
        );
        return checkpointModel(
            task,
            execution,
            runtime,
            conversation,
            modelId,
            traceId,
            result
        );
      } catch (RuntimeException ex) {
        if (!coordinator.failModelAttempt(
            task,
            traceId,
            "MODEL_INVOCATION_FAILED",
            "MODEL_INVOCATION_FAILED"
        )) {
          return false;
        }
      }
    }
    coordinator.fail(
        task,
        "AGENT_MODEL_FALLBACK_EXHAUSTED",
        "AGENT_MODEL_FALLBACK_EXHAUSTED"
    );
    return false;
  }

  private boolean checkpointModel(
      final ExecutionTask task,
      final AiAgentExecution execution,
      final RuntimeDefinition runtime,
      final List<Message> conversation,
      final String modelId,
      final String traceId,
      final GenerationResult result
  ) {
    List<ContentBlock> output = result.output() == null
        ? List.of() : List.copyOf(result.output());
    List<Message> updatedConversation = new ArrayList<>(conversation);
    List<ContentBlock> checkpointContent = checkpointContent(output);
    if (!checkpointContent.isEmpty()) {
      updatedConversation.add(new Message(
          MessageRole.ASSISTANT,
          checkpointContent
      ));
    }
    List<PendingSkillCall> calls = toolCalls(
        output,
        runtime,
        execution.getMaximumConcurrency()
    );
    AgentConversationMemory.Compaction memory = compactMemory(
        execution,
        updatedConversation
    );
    String responseJson = writeJson(output);
    BigDecimal cost = invocationRepository.findById(result.invocationId())
        .map(AiInvocationRecord::getTotalCost)
        .orElse(BigDecimal.ZERO);
    boolean withinBudget = coordinator.completeModel(
        task,
        new ModelCheckpoint(
            traceId,
            modelId,
            result.invocationId(),
            sha256(responseJson),
            responseJson,
            writeJson(memory.messages()),
            calls.isEmpty() ? null : writeJson(calls),
            result.usage() == null ? 0 : tokens(result.usage().inputTokens()),
            result.usage() == null ? 0 : tokens(result.usage().outputTokens()),
            cost,
            memory.compactedMessages(),
            memory.summary() == null ? null : sha256(memory.summary())
        )
    );
    if (!withinBudget) {
      return false;
    }
    if (!calls.isEmpty()) {
      return true;
    }
    Object outputValue = finalOutput(output, modelId, result.stopReason());
    schemaValidator.validate(
        runtime.outputSchema(),
        outputValue,
        "Agent output"
    );
    String outputJson = writeJson(outputValue);
    AiAgentMemoryWrite longTermWrite = withExecutionContext(
        execution,
        () -> memoryService.prepareWrite(execution, outputJson)
    );
    coordinator.succeed(task, outputJson, longTermWrite);
    return false;
  }

  private AiAgentMemoryContext longTermMemory(
      final ExecutionTask task,
      final AiAgentExecution execution
  ) {
    AiAgentMemoryContext context = withExecutionContext(
        execution,
        () -> memoryService.context(execution)
    );
    if (Boolean.TRUE.equals(execution.getLongTermMemoryEnabled())) {
      coordinator.checkpointLongTermMemory(task, context);
    }
    return context;
  }

  private boolean startNextSkill(
      final ExecutionTask task,
      final AiAgentExecution execution,
      final PendingSkillCall call
  ) {
    RuntimeDefinition runtime = runtimeDefinition(execution);
    RuntimeSkill skill = runtime.skills().stream()
        .filter(candidate -> candidate.binding().getId().equals(
            call.skillBindingId()
        ))
        .filter(candidate -> candidate.binding().getSkillAlias().equals(
            call.alias()
        ))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Model selected an unbound Skill"
        ));
    Map<String, Object> input = readMap(
        call.argumentsJson(),
        "Agent Skill arguments"
    );
    try {
      schemaValidator.validate(
          skill.inputContract().schema(),
          input,
          "Agent Skill arguments"
      );
      skill.inputContract().validate(input);
    } catch (IllegalArgumentException ex) {
      return checkpointInvalidSkillArguments(
          task,
          execution,
          skill,
          call,
          input,
          "AGENT_SKILL_ARGUMENTS_INVALID"
      );
    }
    String inputJson = writeJson(input);
    coordinator.launchSkill(
        task,
        new SkillLaunchCheckpoint(
            skill.binding().getId(),
            skill.binding().getSkillId(),
            skill.binding().getSkillVersionId(),
            skill.binding().getSkillContentHash(),
            call.alias(),
            call.toolCallId(),
            sha256(inputJson),
            inputJson,
            input
        )
    );
    return false;
  }

  private boolean checkpointInvalidSkillArguments(
      final ExecutionTask task,
      final AiAgentExecution execution,
      final RuntimeSkill skill,
      final PendingSkillCall call,
      final Map<String, Object> input,
      final String errorMessage
  ) {
    final String inputJson = writeJson(input);
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("error", true);
    error.put("code", "AGENT_SKILL_ARGUMENTS_INVALID");
    error.put("message", errorMessage);
    error.put("retryable", true);
    String outputJson = writeJson(error);
    List<Message> conversation = messages(execution.getConversationJson());
    conversation.add(new Message(
        MessageRole.TOOL,
        List.of(new ContentBlock(
            ContentType.TOOL_RESULT,
            outputJson,
            null,
            "application/json",
            call.toolCallId(),
            call.alias(),
            null
        ))
    ));
    List<PendingSkillCall> pending = pendingCalls(execution);
    if (pending.isEmpty()
        || !Objects.equals(
            pending.getFirst().toolCallId(),
            call.toolCallId()
        )) {
      throw new IllegalStateException(
          "Agent pending Skill call checkpoint is corrupted"
      );
    }
    List<PendingSkillCall> remaining = pending.subList(1, pending.size());
    AgentConversationMemory.Compaction memory = compactMemory(
        execution,
        conversation
    );
    return coordinator.rejectSkillArguments(
        task,
        new SkillFailureCheckpoint(
            skill.binding().getId(),
            skill.binding().getSkillId(),
            skill.binding().getSkillVersionId(),
            call.alias(),
            call.toolCallId(),
            sha256(inputJson),
            inputJson,
            "AGENT_SKILL_ARGUMENTS_INVALID",
            errorMessage,
            sha256(outputJson),
            outputJson,
            writeJson(memory.messages()),
            remaining.isEmpty() ? null : writeJson(remaining),
            memory.compactedMessages(),
            memory.summary() == null ? null : sha256(memory.summary())
        )
    );
  }

  private boolean resumeSkill(
      final ExecutionTask task,
      final AiAgentExecution execution
  ) {
    AiSkillExecution child = skillExecutionRepository.findActiveById(
        execution.getCurrentSkillExecutionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Pinned child Skill execution no longer exists"
    ));
    if (child.getStatus() == SkillExecutionStatus.SUCCEEDED) {
      String outputJson = child.getOutputJson() == null
          ? "null" : child.getOutputJson();
      List<Message> conversation = messages(execution.getConversationJson());
      conversation.add(new Message(
          MessageRole.TOOL,
          List.of(new ContentBlock(
              ContentType.TOOL_RESULT,
              outputJson,
              null,
              "application/json",
              execution.getCurrentToolCallId(),
              bindingAlias(execution),
              null
          ))
      ));
      List<PendingSkillCall> pending = pendingCalls(execution);
      if (pending.isEmpty()
          || !Objects.equals(
              pending.getFirst().toolCallId(),
              execution.getCurrentToolCallId()
          )) {
        throw new IllegalStateException(
            "Agent pending Skill call checkpoint is corrupted"
        );
      }
      List<PendingSkillCall> remaining = pending.subList(1, pending.size());
      AgentConversationMemory.Compaction memory = compactMemory(
          execution,
          conversation
      );
      return coordinator.completeSkill(
          task,
          new SkillResultCheckpoint(
              sha256(outputJson),
              outputJson,
              writeJson(memory.messages()),
              remaining.isEmpty() ? null : writeJson(remaining),
              memory.compactedMessages(),
              memory.summary() == null ? null : sha256(memory.summary())
          )
      );
    }
    if (child.getStatus() == SkillExecutionStatus.FAILED
        || child.getStatus() == SkillExecutionStatus.REJECTED
        || child.getStatus() == SkillExecutionStatus.CANCELLED) {
      coordinator.failSkill(
          task,
          "AGENT_SKILL_EXECUTION_FAILED",
          "AGENT_SKILL_EXECUTION_FAILED"
      );
      return false;
    }
    coordinator.continueWaitingForSkill(task);
    return false;
  }

  private AgentConversationMemory.Compaction compactMemory(
      final AiAgentExecution execution,
      final List<Message> conversation
  ) {
    return AgentConversationMemory.compact(
        conversation,
        Boolean.TRUE.equals(execution.getShortTermMemoryEnabled()),
        positive(execution.getMaximumMemoryMessages(), 20),
        positive(execution.getMaximumMemorySummaryCharacters(), 8_192)
    );
  }

  private static int positive(
      final Integer value,
      final int fallback
  ) {
    return value == null || value <= 0 ? fallback : value;
  }

  private RuntimeDefinition runtimeDefinition(
      final AiAgentExecution execution
  ) {
    AiAgentVersion version = versionRepository.findActiveById(
        execution.getAgentVersionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Pinned Agent version no longer exists"
    ));
    if (!Objects.equals(
        version.getContentHash(),
        execution.getAgentVersionContentHash()
    )) {
      throw new IllegalStateException(
          "Pinned Agent version content hash changed"
      );
    }
    Map<String, Object> manifest = readMap(
        version.getManifestJson(),
        "Agent Manifest"
    );
    Map<String, Object> spec = map(manifest.get("spec"), "Agent spec");
    Map<String, Object> model = map(
        spec.get("model"),
        "Agent model selector"
    );
    List<String> fallbackModels = stringList(
        model.get("fallbackModelIds")
    );
    List<RuntimeSkill> skills = bindingRepository
        .findAllActiveByAgentVersionId(version.getId())
        .stream()
        .map(this::runtimeSkill)
        .toList();
    String systemPrompt = required(
        spec.get("systemPrompt"),
        "Agent system prompt"
    );
    Map<String, Object> behavior = optionalMap(spec.get("behavior"));
    List<String> behaviorInstructions = stringList(
        behavior.get("instructions")
    );
    String instructions = behaviorInstructions.isEmpty()
        ? systemPrompt
        : systemPrompt + "\n\nBehavior constraints:\n- "
            + String.join("\n- ", behaviorInstructions);
    return new RuntimeDefinition(
        instructions,
        fallbackModels,
        skills,
        map(spec.get("outputSchema"), "Agent output Schema")
    );
  }

  private RuntimeSkill runtimeSkill(final AiAgentSkillBinding binding) {
    AiSkillDefinition skill = skillRepository.findActiveById(
        binding.getSkillId()
    ).orElseThrow(() -> new IllegalStateException(
        "Pinned Skill no longer exists"
    ));
    AiSkillVersion version = skillVersionRepository.findActiveByIdAndSkillId(
        binding.getSkillVersionId(),
        binding.getSkillId()
    ).orElseThrow(() -> new IllegalStateException(
        "Pinned Skill version no longer exists"
    ));
    if (version.getStatus() != SkillVersionStatus.PUBLISHED
        || !Objects.equals(
            version.getContentHash(),
            binding.getSkillContentHash()
        )) {
      throw new IllegalStateException(
          "Pinned Skill version is no longer executable"
      );
    }
    String description = skill.getDescription() == null
        ? skill.getName() : skill.getDescription();
    AgentSkillInputContract inputContract = AgentSkillInputContract.create(
        readMap(version.getInputSchemaJson(), "Skill input Schema"),
        readMap(version.getManifestJson(), "Skill Manifest")
    );
    return new RuntimeSkill(
        binding,
        new ToolDefinition(
            binding.getSkillAlias(),
            description,
            writeJson(inputContract.schema()),
            true
        ),
        inputContract
    );
  }

  private List<PendingSkillCall> toolCalls(
      final List<ContentBlock> output,
      final RuntimeDefinition runtime,
      final int maximumConcurrency
  ) {
    Map<String, RuntimeSkill> skills = new LinkedHashMap<>();
    runtime.skills().forEach(skill ->
        skills.put(skill.binding().getSkillAlias(), skill));
    List<PendingSkillCall> calls = new ArrayList<>();
    for (ContentBlock block : output) {
      if (block.type() != ContentType.TOOL_CALL) {
        continue;
      }
      RuntimeSkill skill = skills.get(block.toolName());
      if (skill == null) {
        throw new IllegalStateException(
            "Model attempted to call an unbound Skill: " + block.toolName()
        );
      }
      String toolCallId = required(block.toolCallId(), "Tool call ID");
      String arguments = block.argumentsJson() == null
          ? "{}" : block.argumentsJson();
      readMap(arguments, "Agent Skill arguments");
      calls.add(new PendingSkillCall(
          toolCallId,
          skill.binding().getId(),
          skill.binding().getSkillAlias(),
          arguments
      ));
    }
    if (calls.size() > maximumConcurrency) {
      throw new IllegalStateException(
          "Model returned too many concurrent Skill calls"
      );
    }
    return List.copyOf(calls);
  }

  static List<ContentBlock> checkpointContent(
      final List<ContentBlock> output
  ) {
    return output.stream()
        .filter(Objects::nonNull)
        .filter(block ->
            (block.type() != ContentType.TEXT
                && block.type() != ContentType.REFUSAL)
                || (block.text() != null && !block.text().isBlank())
        )
        .toList();
  }

  private Object finalOutput(
      final List<ContentBlock> output,
      final String modelId,
      final String stopReason
  ) {
    String text = output.stream()
        .filter(block -> block.type() == ContentType.TEXT
            || block.type() == ContentType.REFUSAL)
        .map(ContentBlock::text)
        .filter(Objects::nonNull)
        .reduce("", String::concat);
    if (!text.isBlank()) {
      try {
        Object parsed = objectMapper.readValue(text, Object.class);
        if (parsed instanceof Map<?, ?>) {
          return parsed;
        }
      } catch (JsonProcessingException ignored) {
        // Plain model text is represented by a stable object envelope.
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("content", text);
    result.put("modelId", modelId);
    result.put("stopReason", stopReason);
    return result;
  }

  private List<Message> messages(final String json) {
    try {
      return new ArrayList<>(objectMapper.readValue(json, MESSAGE_LIST));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Agent conversation checkpoint is corrupted",
          ex
      );
    }
  }

  private List<PendingSkillCall> pendingCalls(
      final AiAgentExecution execution
  ) {
    if (execution.getPendingSkillCallsJson() == null) {
      return List.of();
    }
    try {
      return objectMapper.readValue(
          execution.getPendingSkillCallsJson(),
          CALL_LIST
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Agent pending Skill calls are corrupted",
          ex
      );
    }
  }

  private String bindingAlias(final AiAgentExecution execution) {
    return bindingRepository.findAllActiveByAgentVersionId(
        execution.getAgentVersionId()
    ).stream().filter(binding -> binding.getId().equals(
        execution.getCurrentSkillBindingId()
    )).map(AiAgentSkillBinding::getSkillAlias).findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Agent Skill binding no longer exists"
        ));
  }

  private AiAgentExecution requireExecution(final String executionId) {
    AiAgentExecution execution = executionRepository.findActiveById(
        executionId
    ).orElseThrow(() -> new IllegalStateException(
        "Agent execution no longer exists"
    ));
    if (execution.getStatus() != AgentExecutionStatus.RUNNING) {
      throw new IllegalStateException(
          "Agent execution is no longer running"
      );
    }
    return execution;
  }

  private <T> T withExecutionContext(
      final AiAgentExecution execution,
      final Supplier<T> operation
  ) {
    AuthorizationContext previous = AuthorizationContextHolder.getContext();
    AuthorizationContext context = new AuthorizationContext();
    context.setContextId("agent-execution:" + execution.getId());
    context.setUserId(execution.getRequestedBy() == null
        ? "agent-runtime" : execution.getRequestedBy());
    context.setIsAdministrator(
        execution.getScopeType() == AiResourceScope.SYSTEM
    );
    context.setRoles(List.of());
    context.setResources(List.of());
    context.setScopeType(execution.getScopeType() == AiResourceScope.SYSTEM
        ? AuthorizationScopeType.PLATFORM : AuthorizationScopeType.TENANT);
    context.setActorRole(execution.getScopeType() == AiResourceScope.SYSTEM
        ? AuthorizationActorRole.PLATFORM_ADMIN
        : AuthorizationActorRole.TENANT_MEMBER);
    context.setAttributes(execution.getScopeType() == AiResourceScope.TENANT
        ? Map.of(TENANT_ID_ATTRIBUTE, execution.getTenantId()) : Map.of());
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        context
    );
    try {
      return operation.get();
    } finally {
      RequestContextHolder.clearContext(
          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY
      );
      if (previous != null) {
        RequestContextHolder.setContext(
            RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
            previous
        );
      }
    }
  }

  private Map<String, Object> readMap(
      final String json,
      final String label
  ) {
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is not valid JSON", ex);
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Agent runtime checkpoint is not valid JSON",
          ex
      );
    }
  }

  private static Map<String, Object> map(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?> source)) {
      throw new IllegalStateException(label + " is corrupted");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static Map<String, Object> optionalMap(final Object value) {
    return value == null ? Map.of() : map(value, "Agent policy");
  }

  private static List<String> stringList(final Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException("Agent string list is corrupted");
    }
    return list.stream()
        .map(item -> required(item, "Agent list item"))
        .toList();
  }

  private static String required(final Object value, final String label) {
    String normalized = value == null ? null : String.valueOf(value).trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalStateException(label + " must not be blank");
    }
    return normalized;
  }

  private static int tokens(final Integer value) {
    return value == null ? 0 : value;
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

  private void safelyFail(
      final ExecutionTask task,
      final String errorCode,
      final String errorMessage
  ) {
    try {
      coordinator.fail(task, errorCode, errorMessage);
    } catch (RuntimeException ignored) {
      // A newer fenced worker or user cancellation owns the terminal state.
    }
  }

  private record RuntimeDefinition(
      String instructions,
      List<String> fallbackModelIds,
      List<RuntimeSkill> skills,
      Map<String, Object> outputSchema
  ) {
  }

  private record RuntimeSkill(
      AiAgentSkillBinding binding,
      ToolDefinition tool,
      AgentSkillInputContract inputContract
  ) {
  }

  private record PendingSkillCall(
      String toolCallId,
      String skillBindingId,
      String alias,
      String argumentsJson
  ) {
  }
}
