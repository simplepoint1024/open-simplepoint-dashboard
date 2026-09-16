package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentSkillBindingRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentMemoryService;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryContext;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiInvocationRecordRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;

/**
 * Agent Runtime security and terminal output tests.
 */
class AiAgentExecutionRunnerTest {

  private AiAgentExecutionCoordinator coordinator;

  private AiAgentExecutionRepository executionRepository;

  private AiAgentVersionRepository versionRepository;

  private AiAgentSkillBindingRepository bindingRepository;

  private AiGenerationService generationService;

  private AiInvocationRecordRepository invocationRepository;

  private AiAgentMemoryService memoryService;

  private AiSkillDefinitionRepository skillRepository;

  private AiSkillVersionRepository skillVersionRepository;

  private AiAgentExecutionRunner runner;

  private final ObjectMapper objectMapper = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .build();

  @BeforeEach
  void setUp() {
    coordinator = mock(AiAgentExecutionCoordinator.class);
    executionRepository = mock(AiAgentExecutionRepository.class);
    versionRepository = mock(AiAgentVersionRepository.class);
    bindingRepository = mock(AiAgentSkillBindingRepository.class);
    generationService = mock(AiGenerationService.class);
    invocationRepository = mock(AiInvocationRecordRepository.class);
    memoryService = mock(AiAgentMemoryService.class);
    skillRepository = mock(AiSkillDefinitionRepository.class);
    skillVersionRepository = mock(AiSkillVersionRepository.class);
    when(coordinator.failModelAttempt(any(), any(), any(), any()))
        .thenReturn(true);
    when(memoryService.context(any())).thenReturn(
        new AiAgentMemoryContext(
            List.of(),
            "[]",
            null,
            0,
            "a".repeat(64)
        )
    );
    runner = new AiAgentExecutionRunner(
        coordinator,
        executionRepository,
        versionRepository,
        bindingRepository,
        skillRepository,
        skillVersionRepository,
        mock(AiSkillExecutionRepository.class),
        generationService,
        invocationRepository,
        memoryService,
        new SkillJsonSchemaValidator(),
        objectMapper
    );
  }

  @Test
  void completesPlainModelOutputWhenUsageCountsAreNull() {
    AiAgentExecution execution = execution();
    stubRuntime(execution);
    when(coordinator.beginModel(any(), eq("model-1"), any(), any()))
        .thenReturn("trace-1");
    when(generationService.generate(any())).thenReturn(new GenerationResult(
        "invocation-1",
        "model-1",
        "provider-model",
        "provider-request",
        List.of(new ContentBlock(
            ContentType.TEXT,
            "hello",
            null,
            null,
            null,
            null,
            null
        )),
        "stop",
        new TokenUsage(null, null, null, null),
        12,
        Instant.now()
    ));
    when(invocationRepository.findById("invocation-1"))
        .thenReturn(Optional.empty());
    when(coordinator.completeModel(any(), any())).thenReturn(true);

    runner.execute(new ExecutionTask("execution-1", "worker-1", 1));

    verify(coordinator).succeed(
        any(),
        contains("\"content\":\"hello\""),
        isNull()
    );
  }

  @Test
  void rejectsModelAttemptToCallAnUnboundCapability() {
    AiAgentExecution execution = execution();
    stubRuntime(execution);
    when(coordinator.beginModel(any(), eq("model-1"), any(), any()))
        .thenReturn("trace-1");
    when(generationService.generate(any())).thenReturn(new GenerationResult(
        "invocation-1",
        "model-1",
        "provider-model",
        "provider-request",
        List.of(new ContentBlock(
            ContentType.TOOL_CALL,
            null,
            null,
            null,
            "call-1",
            "direct_mcp_tool",
            "{}"
        )),
        "tool_calls",
        new TokenUsage(3, 2, 5, 0),
        12,
        Instant.now()
    ));
    when(invocationRepository.findById("invocation-1"))
        .thenReturn(Optional.empty());

    runner.execute(new ExecutionTask("execution-1", "worker-1", 1));

    verify(coordinator).fail(
        any(),
        eq("AGENT_MODEL_FALLBACK_EXHAUSTED"),
        eq("AGENT_MODEL_FALLBACK_EXHAUSTED")
    );
  }

  @Test
  void doesNotPropagateProviderFailureBodyIntoDurableDiagnostics() {
    String sentinel =
        "provider body token=sk-live-secret https://internal.example/v1";
    AiAgentExecution execution = execution();
    stubRuntime(execution);
    when(coordinator.beginModel(any(), eq("model-1"), any(), any()))
        .thenReturn("trace-1");
    when(generationService.generate(any()))
        .thenThrow(new IllegalStateException(sentinel));

    runner.execute(new ExecutionTask("execution-1", "worker-1", 1));

    verify(coordinator).failModelAttempt(
        any(),
        eq("trace-1"),
        eq("MODEL_INVOCATION_FAILED"),
        eq("MODEL_INVOCATION_FAILED")
    );
    verify(coordinator).fail(
        any(),
        eq("AGENT_MODEL_FALLBACK_EXHAUSTED"),
        eq("AGENT_MODEL_FALLBACK_EXHAUSTED")
    );
  }

  @Test
  void removesBlankTextFromToolCallConversationCheckpoint() {
    ContentBlock toolCall = new ContentBlock(
        ContentType.TOOL_CALL,
        null,
        null,
        null,
        "call-1",
        "bound_skill",
        "{}"
    );

    List<ContentBlock> result = AiAgentExecutionRunner.checkpointContent(
        List.of(
            new ContentBlock(
                ContentType.TEXT,
                "",
                null,
                null,
                null,
                null,
                null
            ),
            toolCall
        )
    );

    assertThat(result).containsExactly(toolCall);
  }

  @Test
  void submitsValidPinnedSkillAndCheckpointsWait() {
    AiAgentExecution execution = execution();
    execution.setPendingSkillCallsJson("""
        [{
          "toolCallId": "call-1",
          "skillBindingId": "binding-1",
          "alias": "echo",
          "argumentsJson": "{\\"message\\":\\"hello\\"}"
        }]
        """);
    AiAgentSkillBinding binding = new AiAgentSkillBinding();
    binding.setId("binding-1");
    binding.setAgentVersionId("version-1");
    binding.setSkillId("skill-1");
    binding.setSkillVersionId("skill-version-1");
    binding.setSkillContentHash("b".repeat(64));
    binding.setSkillAlias("echo");
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId("skill-1");
    skill.setName("Echo");
    skill.setDescription("Echo one message");
    AiSkillVersion skillVersion = new AiSkillVersion();
    skillVersion.setId("skill-version-1");
    skillVersion.setStatus(SkillVersionStatus.PUBLISHED);
    skillVersion.setContentHash("b".repeat(64));
    skillVersion.setInputSchemaJson("""
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["message"],
          "properties": {"message": {"type": "string"}}
        }
        """);
    skillVersion.setManifestJson("""
        {
          "spec": {
            "resources": [],
            "workflow": {"steps": []}
          }
        }
        """);
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    when(versionRepository.findActiveById("version-1"))
        .thenReturn(Optional.of(version()));
    when(bindingRepository.findAllActiveByAgentVersionId("version-1"))
        .thenReturn(List.of(binding));
    when(skillRepository.findActiveById("skill-1"))
        .thenReturn(Optional.of(skill));
    when(skillVersionRepository.findActiveByIdAndSkillId(
        "skill-version-1",
        "skill-1"
    )).thenReturn(Optional.of(skillVersion));

    runner.execute(new ExecutionTask("execution-1", "worker-1", 1));

    verify(coordinator).launchSkill(
        any(),
        org.mockito.ArgumentMatchers.argThat(checkpoint ->
            "skill-1".equals(checkpoint.skillId())
                && "skill-version-1".equals(checkpoint.skillVersionId())
                && "b".repeat(64).equals(
                    checkpoint.expectedContentHash()
                )
                && "call-1".equals(checkpoint.toolCallId())
                && "hello".equals(checkpoint.input().get("message")))
    );
  }

  private void stubRuntime(final AiAgentExecution execution) {
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    when(versionRepository.findActiveById("version-1"))
        .thenReturn(Optional.of(version()));
    when(bindingRepository.findAllActiveByAgentVersionId("version-1"))
        .thenReturn(List.of());
  }

  private AiAgentExecution execution() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentVersionId("version-1");
    execution.setAgentVersionContentHash("a".repeat(64));
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setPrimaryModelId("model-1");
    execution.setMaximumConcurrency(4);
    execution.setMaximumOutputTokens(100);
    execution.setConsumedOutputTokens(0);
    execution.setConversationJson("""
        [{"role":"USER","content":[{"type":"TEXT","text":"hello"}]}]
        """);
    return execution;
  }

  private AiAgentVersion version() {
    AiAgentVersion version = new AiAgentVersion();
    version.setId("version-1");
    version.setContentHash("a".repeat(64));
    version.setManifestJson("""
        {
          "spec": {
            "systemPrompt": "Use only bound Skills.",
            "model": {
              "primaryModelId": "model-1",
              "fallbackModelIds": []
            },
            "outputSchema": {
              "type": "object",
              "additionalProperties": true
            }
          }
        }
        """);
    return version;
  }
}
