package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionEvent;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftDebugExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBreakpointsRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionCancelRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionEventRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionEventPublisher;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.ResolvedBindings;
import org.simplepoint.plugin.ai.skill.service.execution.SkillDraftCapabilityResolver.ToolBinding;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ToolNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowPlan;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AiSkillExecutionServiceImplTest {

  @Mock
  private AiSkillDefinitionRepository skillRepository;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiSkillDraftRepository draftRepository;

  @Mock
  private AiSkillDraftRevisionRepository draftRevisionRepository;

  @Mock
  private AiSkillToolBindingRepository bindingRepository;

  @Mock
  private AiSkillPromptBindingRepository promptBindingRepository;

  @Mock
  private AiSkillResourceBindingRepository resourceBindingRepository;

  @Mock
  private AiSkillExecutionRepository executionRepository;

  @Mock
  private AiSkillExecutionEventRepository eventRepository;

  @Mock
  private AiSkillExecutionStepRepository stepRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private SkillDraftCapabilityResolver draftCapabilityResolver;

  @Mock
  private AiSkillExecutionEventPublisher eventPublisher;

  private AiSkillExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    SkillExecutionProperties properties = new SkillExecutionProperties();
    SkillWorkflowTemplateResolver templateResolver =
        new SkillWorkflowTemplateResolver();
    SkillWorkflowConditionEvaluator conditionEvaluator =
        new SkillWorkflowConditionEvaluator(templateResolver);
    service = new AiSkillExecutionServiceImpl(
        skillRepository,
        versionRepository,
        draftRepository,
        draftRevisionRepository,
        bindingRepository,
        promptBindingRepository,
        resourceBindingRepository,
        executionRepository,
        eventRepository,
        stepRepository,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowPlanCompiler(
            templateResolver,
            conditionEvaluator
        ),
        new SkillBudgetPolicy(properties, objectMapper),
        new SkillApprovalPolicy(objectMapper),
        draftCapabilityResolver,
        eventPublisher,
        properties,
        objectMapper
    );
  }

  @Test
  void submitsPinnedToolStepAsDurablePendingExecution() {
    final AiSkillDefinition skill = skill();
    AiSkillVersion version = version();
    AiSkillToolBinding binding = binding();
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveById("version-a"))
        .thenReturn(Optional.of(version));
    when(executionRepository.findActiveByIdempotency(
        eq("skill-a"),
        eq(AiResourceScope.SYSTEM),
        isNull(),
        anyString()
    )).thenReturn(Optional.empty());
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of(binding));
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("execution-a");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecutionStep step = invocation.getArgument(0);
      step.setId("step-a");
      return step;
    });

    AiSkillExecution result = service.start(
        "skill-a",
        new SkillExecutionStartRequest(
            "request-a",
            Map.of("message", "hello")
        )
    );

    assertThat(result.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
    assertThat(result.getSourceType())
        .isEqualTo(SkillExecutionSource.PUBLISHED);
    assertThat(result.getSkillVersionId()).isEqualTo("version-a");
    assertThat(result.getDraftId()).isNull();
    assertThat(result.getInput()).isEqualTo(Map.of("message", "hello"));
    assertThat(result.getMaximumToolCalls()).isEqualTo(1);
    assertThat(result.getMaximumDurationSeconds()).isEqualTo(300);
    assertThat(result.getMaximumPayloadBytes()).isEqualTo(1024L * 1024L);
    assertThat(result.getConsumedToolCalls()).isZero();
    assertThat(result.getConsumedPayloadBytes()).isPositive();
    assertThat(result.getDeadlineAt()).isNotNull();
    assertThat(result.getSteps()).singleElement().satisfies(step -> {
      assertThat(step.getStatus())
          .isEqualTo(SkillExecutionStepStatus.PENDING);
      assertThat(step.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(step.getCapabilityName()).isEqualTo("echo");
    });
    ArgumentCaptor<AiSkillExecutionStep> savedStep =
        ArgumentCaptor.forClass(AiSkillExecutionStep.class);
    verify(stepRepository).save(savedStep.capture());
    assertThat(savedStep.getValue().getInputTemplateJson())
        .isEqualTo("{\"message\":{\"$ref\":\"input.message\"}}");
  }

  @Test
  void submitsExactDraftRevisionWithPinnedLiveCapabilities() throws Exception {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setId("draft-a");
    draft.setSkillId("skill-a");
    draft.setRevision(3L);
    Map<String, Object> manifest = Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of("name", "echo-skill", "version", "0.1.0"),
        "spec", Map.of(
            "inputSchema", Map.of(
                "type", "object",
                "required", List.of("message"),
                "properties", Map.of("message", Map.of("type", "string"))
            ),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "name", "echo"
            )),
            "workflow", Map.of("steps", List.of(Map.of(
                "id", "echo-step",
                "type", "tool",
                "tool", "echo",
                "arguments", Map.of(
                    "message", Map.of("$ref", "input.message")
                )
            )))
        )
    );
    String manifestJson = canonicalJson(manifest);
    AiSkillDraftRevision revision = new AiSkillDraftRevision();
    revision.setDraftId("draft-a");
    revision.setSkillId("skill-a");
    revision.setRevision(2L);
    revision.setValidationStatus(SkillDraftValidationStatus.VALID);
    revision.setCompiledManifestJson(manifestJson);
    revision.setContentHash(sha256(manifestJson));
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));
    when(draftRevisionRepository.findActiveByDraftIdAndRevision(
        "draft-a",
        2L
    )).thenReturn(Optional.of(revision));
    when(executionRepository.findActiveDraftByIdempotency(
        eq("skill-a"),
        eq("draft-a"),
        eq(2L),
        eq(AiResourceScope.SYSTEM),
        isNull(),
        anyString()
    )).thenReturn(Optional.empty());
    when(draftCapabilityResolver.resolve(any())).thenReturn(
        new ResolvedBindings(
            Map.of("echo", new ToolBinding(
                "echo",
                "server-a",
                "snapshot-a",
                "echo",
                "b".repeat(64),
                null,
                false
            )),
            Map.of(),
            Map.of()
        )
    );
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("debug-execution-a");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> invocation
        .getArgument(0));

    AiSkillExecution result = service.startDraftDebug(
        "skill-a",
        new SkillDraftDebugExecutionStartRequest(
            2L,
            SkillDebugMode.LIVE,
            null,
            "draft-request-a",
            Map.of("message", "hello")
        )
    );

    assertThat(result.getSourceType()).isEqualTo(SkillExecutionSource.DRAFT);
    assertThat(result.getSkillVersionId()).isNull();
    assertThat(result.getDraftId()).isEqualTo("draft-a");
    assertThat(result.getDraftRevision()).isEqualTo(2L);
    assertThat(result.getDraftContentHash()).isEqualTo(sha256(manifestJson));
    assertThat(result.getDebugMode()).isEqualTo(SkillDebugMode.LIVE);
    assertThat(result.getSteps()).singleElement().satisfies(step -> {
      assertThat(step.getBindingId()).startsWith("draft-");
      assertThat(step.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(step.getCapabilitySchemaHash()).isEqualTo("b".repeat(64));
    });
  }

  @Test
  void rejectsMockModeWithoutPinnedTestCase() {
    assertThatThrownBy(() -> service.startDraftDebug(
        "skill-a",
        new SkillDraftDebugExecutionStartRequest(
            1L,
            SkillDebugMode.MOCK,
            null,
            "request-a",
            Map.of()
        )
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mock test case ID");
  }

  @Test
  void pinsEnabledMockTestCaseAndItsInputWithoutApproval() throws Exception {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setId("draft-a");
    draft.setSkillId("skill-a");
    Map<String, Object> manifest = Map.of(
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo", "serverId", "server-a",
                "snapshotId", "snapshot-a", "name", "echo"
            )),
            "workflow", Map.of("steps", List.of(Map.of(
                "id", "echo-step", "type", "tool", "tool", "echo"
            ))),
            "approvals", Map.of("execution", Map.of("required", true))
        )
    );
    String manifestJson = canonicalJson(manifest);
    AiSkillDraftRevision revision = new AiSkillDraftRevision();
    revision.setDraftId("draft-a");
    revision.setSkillId("skill-a");
    revision.setRevision(4L);
    revision.setValidationStatus(SkillDraftValidationStatus.VALID);
    revision.setCompiledManifestJson(manifestJson);
    revision.setContentHash(sha256(manifestJson));
    revision.setDesignerJson("""
        {
          "schemaVersion":"simplepoint.io/designer/v1alpha1",
          "metadata":{},"inputSchema":{"type":"object"},
          "outputSchema":{"type":"object"},
          "tools":[],"prompts":[],"resources":[],
          "nodes":[],"ports":[],"edges":[],
          "workflowOutput":null,"budgets":{},"approvals":{},
          "tests":[{
            "id":"happy-path","name":"Happy path","enabled":true,
            "input":{"message":"from-test"},
            "mocks":[{"nodeId":"echo-step","mode":"SUCCESS",
              "output":{"message":"mocked"}}],
            "assertions":[{
              "id":"expect-message","sourceNodeId":"__output",
              "fieldPath":"message","operator":"EQUALS",
              "expected":"mocked"
            }]
          }],
          "viewport":{"x":0,"y":0,"zoom":1}
        }
        """);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));
    when(draftRevisionRepository.findActiveByDraftIdAndRevision(
        "draft-a", 4L
    )).thenReturn(Optional.of(revision));
    when(executionRepository.findActiveDraftByIdempotency(
        eq("skill-a"), eq("draft-a"), eq(4L),
        eq(AiResourceScope.SYSTEM), isNull(), anyString()
    )).thenReturn(Optional.empty());
    when(draftCapabilityResolver.resolve(any())).thenReturn(
        new ResolvedBindings(
            Map.of("echo", new ToolBinding(
                "echo", "server-a", "snapshot-a", "echo",
                "d".repeat(64),
                Map.of(
                    "type", "object",
                    "required", List.of("message"),
                    "properties", Map.of(
                        "message", Map.of("type", "string")
                    ),
                    "additionalProperties", false
                ),
                true
            )),
            Map.of(),
            Map.of()
        )
    );
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("mock-execution-a");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> invocation
        .getArgument(0));

    AiSkillExecution result = service.startDraftDebug(
        "skill-a",
        new SkillDraftDebugExecutionStartRequest(
            4L, SkillDebugMode.MOCK, "happy-path",
            "mock-request-a", null
        )
    );

    assertThat(result.getDebugMode()).isEqualTo(SkillDebugMode.MOCK);
    assertThat(result.getTestCaseId()).isEqualTo("happy-path");
    assertThat(result.getMockConfigHash()).hasSize(64);
    assertThat(result.getMockConfigJson())
        .contains("happy-path", "echo-step", "mocked", "expect-message");
    assertThat(result.getInput()).containsEntry("message", "from-test");
    assertThat(result.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
    assertThat(result.getApprovalRequired()).isFalse();
  }

  @Test
  void runsEnabledMockCasesInStableDurableBatchAndSummarizesResults()
      throws Exception {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setId("draft-a");
    draft.setSkillId("skill-a");
    Map<String, Object> manifest = Map.of(
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo", "serverId", "server-a",
                "snapshotId", "snapshot-a", "name", "echo"
            )),
            "workflow", Map.of("steps", List.of(Map.of(
                "id", "echo-step", "type", "tool", "tool", "echo"
            )))
        )
    );
    String manifestJson = canonicalJson(manifest);
    AiSkillDraftRevision revision = new AiSkillDraftRevision();
    revision.setDraftId("draft-a");
    revision.setSkillId("skill-a");
    revision.setRevision(5L);
    revision.setValidationStatus(SkillDraftValidationStatus.VALID);
    revision.setCompiledManifestJson(manifestJson);
    revision.setContentHash(sha256(manifestJson));
    revision.setDesignerJson("""
        {
          "schemaVersion":"simplepoint.io/designer/v1alpha1",
          "metadata":{},"inputSchema":{"type":"object"},
          "outputSchema":{"type":"object"},
          "tools":[],"prompts":[],"resources":[],
          "nodes":[],"ports":[],"edges":[],
          "workflowOutput":null,"budgets":{},"approvals":{},
          "tests":[
            {"id":"happy","name":"Happy path","enabled":true,
             "input":{"message":"one"},
             "mocks":[{"nodeId":"echo-step","mode":"SUCCESS","output":{}}],
             "assertions":[]},
            {"id":"disabled","name":"Disabled","enabled":false,
             "input":{},"mocks":[],"assertions":[]},
            {"id":"error","name":"Error path","enabled":true,
             "input":{"message":"two"},
             "mocks":[{"nodeId":"echo-step","mode":"ERROR",
               "errorCode":"MOCK_ERROR","errorMessage":"Expected"}],
             "assertions":[]}
          ],
          "viewport":{"x":0,"y":0,"zoom":1}
        }
        """);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));
    when(draftRevisionRepository.findActiveByDraftIdAndRevision(
        "draft-a", 5L
    )).thenReturn(Optional.of(revision));
    when(draftCapabilityResolver.resolve(any())).thenReturn(
        new ResolvedBindings(
            Map.of("echo", new ToolBinding(
                "echo", "server-a", "snapshot-a", "echo",
                "e".repeat(64), null, true
            )),
            Map.of(),
            Map.of()
        )
    );
    Instant createdAt = Instant.parse("2026-08-08T01:00:00Z");
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("execution-" + execution.getTestCaseId());
      execution.setCreatedAt(createdAt.plusSeconds(
          execution.getTestRunOrder()
      ));
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> invocation
        .getArgument(0));

    var started = service.startDraftMockTestRun(
        "skill-a",
        new SkillDraftMockTestRunStartRequest(5L, "batch-request-a")
    );

    assertThat(started.status()).isEqualTo(
        SkillDraftMockTestRunStatus.RUNNING
    );
    assertThat(started.totalCount()).isEqualTo(2);
    assertThat(started.cases())
        .extracting(item -> item.testCaseId())
        .containsExactly("happy", "error");
    ArgumentCaptor<AiSkillExecution> saved =
        ArgumentCaptor.forClass(AiSkillExecution.class);
    verify(executionRepository, times(2)).save(saved.capture());
    assertThat(saved.getAllValues())
        .extracting(AiSkillExecution::getTestRunOrder)
        .containsExactly(0, 1);
    assertThat(saved.getAllValues())
        .extracting(AiSkillExecution::getTestRunId)
        .containsOnly(started.id());

    List<AiSkillExecution> completed = saved.getAllValues();
    completed.get(0).setStatus(SkillExecutionStatus.SUCCEEDED);
    completed.get(0).setAssertionsPassed(true);
    completed.get(0).setCompletedAt(createdAt.plusSeconds(3));
    completed.get(1).setStatus(SkillExecutionStatus.FAILED);
    completed.get(1).setAssertionsPassed(false);
    completed.get(1).setErrorCode("MOCK_ERROR");
    completed.get(1).setErrorMessage("Expected");
    completed.get(1).setCompletedAt(createdAt.plusSeconds(4));
    when(executionRepository.findAllActiveByTestRun(
        "skill-a", AiResourceScope.SYSTEM, null, started.id()
    )).thenReturn(completed);

    var summary = service.findDraftMockTestRun(
        "skill-a", started.id()
    ).orElseThrow();

    assertThat(summary.status()).isEqualTo(
        SkillDraftMockTestRunStatus.FAILED
    );
    assertThat(summary.completedCount()).isEqualTo(2);
    assertThat(summary.passedCount()).isEqualTo(1);
    assertThat(summary.failedCount()).isEqualTo(1);
    assertThat(summary.cases())
        .extracting(item -> item.testCaseName())
        .containsExactly("Happy path", "Error path");
    assertThat(summary.completedAt()).isEqualTo(createdAt.plusSeconds(4));
  }

  @Test
  void rejectsSuccessfulToolMockThatViolatesPinnedOutputSchema() {
    ToolNode tool = new ToolNode("echo-step", "echo", Map.of());
    WorkflowPlan plan = new WorkflowPlan(
        List.of(tool),
        null,
        1,
        List.of(tool)
    );
    ResolvedBindings bindings = new ResolvedBindings(
        Map.of("echo", new ToolBinding(
            "echo", "server-a", "snapshot-a", "echo",
            "d".repeat(64),
            Map.of(
                "type", "object",
                "required", List.of("message"),
                "properties", Map.of(
                    "message", Map.of("type", "string")
                ),
                "additionalProperties", false
            ),
            false
        )),
        Map.of(),
        Map.of()
    );
    Map<String, NodeMock> mocks = Map.of(
        "echo-step",
        new NodeMock(
            "echo-step",
            MockMode.SUCCESS,
            Map.of("message", 42),
            null,
            null
        )
    );

    assertThatThrownBy(() -> service.validateMockToolOutputs(
        plan,
        bindings,
        mocks
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MOCK output for workflow step echo-step.message")
        .hasMessageContaining("must be string");
  }

  @Test
  void snapshotsControlPlanAndFlattensAllDurableToolLeaves() {
    final AiSkillDefinition skill = skill();
    AiSkillVersion version = version();
    version.setInputSchemaJson("{\"type\":\"object\"}");
    version.setWorkflowJson("""
        {
          "steps": [
            {
              "id": "route",
              "type": "condition",
              "condition": {
                "equals": [
                  {"$ref": "input.mode"},
                  "full"
                ]
              },
              "then": [
                {"id": "full", "type": "tool", "tool": "echo"}
              ],
              "else": [
                {"id": "compact", "type": "tool", "tool": "echo"}
              ]
            },
            {
              "id": "fanout",
              "type": "parallel",
              "branches": [
                {
                  "id": "left",
                  "steps": [
                    {"id": "left-call", "type": "tool", "tool": "echo"}
                  ]
                },
                {
                  "id": "right",
                  "steps": [
                    {"id": "right-call", "type": "tool", "tool": "echo"}
                  ]
                }
              ]
            }
          ]
        }
        """);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveById("version-a"))
        .thenReturn(Optional.of(version));
    when(executionRepository.findActiveByIdempotency(
        eq("skill-a"),
        eq(AiResourceScope.SYSTEM),
        isNull(),
        anyString()
    )).thenReturn(Optional.empty());
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of(binding()));
    when(executionRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecution execution = invocation.getArgument(0);
      execution.setId("execution-control");
      return execution;
    });
    when(stepRepository.save(any())).thenAnswer(invocation -> {
      AiSkillExecutionStep step = invocation.getArgument(0);
      step.setId("row-" + step.getStepId());
      return step;
    });

    AiSkillExecution result = service.start(
        "skill-a",
        new SkillExecutionStartRequest(
            "request-control",
            Map.of("mode", "full")
        )
    );

    assertThat(result.getWorkflowPlanJson())
        .contains("\"type\":\"condition\"")
        .contains("\"type\":\"parallel\"");
    assertThat(result.getMaximumToolCalls()).isEqualTo(3);
    assertThat(result.getSteps())
        .extracting(AiSkillExecutionStep::getStepId)
        .containsExactly("full", "compact", "left-call", "right-call");
  }

  @Test
  void approvesWithSeparationOfDutiesAndExcludesInactiveTime() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.WAITING_APPROVAL
    );
    Instant originalDeadline = Instant.now().plusSeconds(60);
    execution.setDeadlineAt(originalDeadline);
    execution.setInactiveSince(Instant.now().minusSeconds(30));
    execution.setApprovalRequired(true);
    execution.setSelfApprovalAllowed(false);
    execution.setRequestedBy("requester-a");
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("approver-a");

    try (MockedStatic<AuthorizationContextHolder> holder =
             mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      AiSkillExecution result = service.approve(
          "skill-a",
          "execution-a",
          new SkillExecutionDecisionRequest("Looks safe")
      );

      assertThat(result.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
      assertThat(result.getApprovedBy()).isEqualTo("approver-a");
      assertThat(result.getApprovalComment()).isEqualTo("Looks safe");
      assertThat(result.getDeadlineAt()).isAfterOrEqualTo(
          originalDeadline.plus(Duration.ofSeconds(29))
      );
      assertThat(result.getInactiveSince()).isNull();
    }
  }

  @Test
  void pausesAndResumesPendingExecutionWithoutConsumingItsDeadline() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.PENDING
    );
    Instant originalDeadline = Instant.now().plusSeconds(60);
    execution.setDeadlineAt(originalDeadline);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());

    AiSkillExecution paused = service.pause(
        "skill-a",
        "execution-a",
        new SkillExecutionPauseRequest("Operator maintenance")
    );
    assertThat(paused.getStatus()).isEqualTo(SkillExecutionStatus.PAUSED);
    assertThat(paused.getPauseRequested()).isTrue();
    assertThat(paused.getInactiveSince()).isNotNull();
    execution.setInactiveSince(Instant.now().minusSeconds(10));

    AiSkillExecution resumed = service.resume("skill-a", "execution-a");

    assertThat(resumed.getStatus()).isEqualTo(SkillExecutionStatus.PENDING);
    assertThat(resumed.getPauseRequested()).isFalse();
    assertThat(resumed.getDeadlineAt()).isAfterOrEqualTo(
        originalDeadline.plus(Duration.ofSeconds(9))
    );
    assertThat(resumed.getInactiveSince()).isNull();
  }

  @Test
  void cancelsPendingDraftExecutionAndSkipsUnstartedSteps() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.PENDING
    );
    execution.setSourceType(SkillExecutionSource.DRAFT);
    execution.setSkillVersionId(null);
    execution.setDraftId("draft-a");
    execution.setDraftRevision(7L);
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setId("step-record-a");
    step.setExecutionId("execution-a");
    step.setStepId("step-a");
    step.setStatus(SkillExecutionStepStatus.PENDING);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of(step));

    AiSkillExecution cancelled = service.cancel(
        "skill-a",
        "execution-a",
        new SkillExecutionCancelRequest("No longer needed")
    );

    assertThat(cancelled.getStatus())
        .isEqualTo(SkillExecutionStatus.CANCELLED);
    assertThat(cancelled.getCancelRequested()).isTrue();
    assertThat(cancelled.getCancelReason()).isEqualTo("No longer needed");
    assertThat(step.getStatus()).isEqualTo(SkillExecutionStepStatus.SKIPPED);
    assertThat(step.getErrorCode()).isEqualTo("SKILL_EXECUTION_CANCELLED");
    assertThat(cancelled.getCompletedAt()).isNotNull();
  }

  @Test
  void persistsSafeBreakpointsOnlyForKnownDraftSteps() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.PENDING
    );
    execution.setSourceType(SkillExecutionSource.DRAFT);
    execution.setSkillVersionId(null);
    execution.setDraftId("draft-a");
    execution.setDraftRevision(7L);
    AiSkillExecutionStep first = new AiSkillExecutionStep();
    first.setStepId("step-a");
    AiSkillExecutionStep second = new AiSkillExecutionStep();
    second.setStepId("step-b");
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of(first, second));

    AiSkillExecution updated = service.setDraftDebugBreakpoints(
        "skill-a",
        "execution-a",
        new SkillExecutionBreakpointsRequest(List.of("step-b", "step-a"))
    );

    assertThat(updated.getBreakpointStepIds())
        .containsExactly("step-b", "step-a");
    assertThat(updated.getBreakpointStepIdsJson())
        .isEqualTo("[\"step-b\",\"step-a\"]");
  }

  @Test
  void readsBoundedDraftDebugEventsWithExclusiveCursor() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.RUNNING
    );
    execution.setSourceType(SkillExecutionSource.DRAFT);
    execution.setDraftId("draft-a");
    execution.setDraftRevision(7L);
    AiSkillExecutionEvent fourth = event(4L);
    AiSkillExecutionEvent fifth = event(5L);
    AiSkillExecutionEvent lookAhead = event(6L);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveById("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of());
    when(eventRepository.findActiveAfterSequence(
        eq("execution-a"),
        eq(3L),
        any(Pageable.class)
    )).thenReturn(List.of(fourth, fifth, lookAhead));
    when(eventPublisher.decorate(fourth)).thenReturn(fourth);
    when(eventPublisher.decorate(fifth)).thenReturn(fifth);

    var feed = service.findDraftDebugEvents(
        "skill-a",
        "execution-a",
        3L,
        2
    );

    assertThat(feed.afterSequence()).isEqualTo(3L);
    assertThat(feed.nextSequence()).isEqualTo(5L);
    assertThat(feed.hasMore()).isTrue();
    assertThat(feed.executionStatus()).isEqualTo(SkillExecutionStatus.RUNNING);
    assertThat(feed.events()).containsExactly(fourth, fifth);
  }

  @Test
  void decoratesDraftStepWithTemplateInputOutputAndPinnedIdentity() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.SUCCEEDED
    );
    execution.setSourceType(SkillExecutionSource.DRAFT);
    execution.setSkillVersionId(null);
    execution.setDraftId("draft-a");
    execution.setDraftRevision(7L);
    execution.setDraftContentHash("a".repeat(64));
    execution.setDebugMode(SkillDebugMode.LIVE);
    execution.setOutputJson("{\"message\":\"done\"}");
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setId("step-record-a");
    step.setExecutionId("execution-a");
    step.setStepId("echo-step");
    step.setStepType("tool");
    step.setCapabilitySnapshotId("snapshot-a");
    step.setCapabilitySchemaHash("b".repeat(64));
    step.setInputTemplateJson(
        "{\"message\":{\"$ref\":\"input.message\"}}"
    );
    step.setInputJson("{\"message\":\"hello\"}");
    step.setOutputJson("{\"message\":\"done\"}");
    step.setStatus(SkillExecutionStepStatus.SUCCEEDED);
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveById("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of(step));

    AiSkillExecution result = service.findDraftDebug(
        "skill-a", "execution-a"
    ).orElseThrow();

    assertThat(result.getSteps()).singleElement().satisfies(item -> {
      assertThat(item.getInputTemplate()).isEqualTo(Map.of(
          "message", Map.of("$ref", "input.message")
      ));
      assertThat(item.getInput()).containsEntry("message", "hello");
      assertThat(item.getOutput()).isEqualTo(Map.of("message", "done"));
      assertThat(item.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(item.getCapabilitySchemaHash()).isEqualTo("b".repeat(64));
    });
  }

  @Test
  void rejectsSelfApprovalUnlessTheImmutablePolicyAllowsIt() {
    AiSkillExecution execution = controlledExecution(
        SkillExecutionStatus.WAITING_APPROVAL
    );
    execution.setApprovalRequired(true);
    execution.setSelfApprovalAllowed(false);
    execution.setRequestedBy("requester-a");
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill()));
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("requester-a");

    try (MockedStatic<AuthorizationContextHolder> holder =
             mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      assertThatThrownBy(() -> service.approve(
          "skill-a",
          "execution-a",
          new SkillExecutionDecisionRequest(null)
      )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot approve or reject");
    }
  }

  private static AiSkillDefinition skill() {
    AiSkillDefinition skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setCode("echo-skill");
    skill.setName("Echo skill");
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setStatus(SkillStatus.ACTIVE);
    skill.setEnabled(true);
    skill.setActiveVersionId("version-a");
    return skill;
  }

  private static AiSkillVersion version() {
    AiSkillVersion version = new AiSkillVersion();
    version.setId("version-a");
    version.setSkillId("skill-a");
    version.setScopeType(AiResourceScope.SYSTEM);
    version.setStatus(SkillVersionStatus.PUBLISHED);
    version.setInputSchemaJson("""
        {
          "type": "object",
          "required": ["message"],
          "additionalProperties": false,
          "properties": {"message": {"type": "string"}}
        }
        """);
    version.setOutputSchemaJson("{\"type\":\"object\"}");
    version.setWorkflowJson("""
        {
          "steps": [
            {
              "id": "echo-step",
              "type": "tool",
              "tool": "echo",
              "arguments": {
                "message": {"$ref": "input.message"}
              }
            }
          ]
        }
        """);
    return version;
  }

  private static AiSkillToolBinding binding() {
    AiSkillToolBinding binding = new AiSkillToolBinding();
    binding.setId("binding-a");
    binding.setSkillVersionId("version-a");
    binding.setMcpServerId("server-a");
    binding.setCapabilitySnapshotId("snapshot-a");
    binding.setToolName("echo");
    binding.setToolAlias("echo");
    binding.setInputSchemaHash("a".repeat(64));
    return binding;
  }

  private static AiSkillExecution controlledExecution(
      final SkillExecutionStatus status
  ) {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setSkillVersionId("version-a");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(status);
    execution.setApprovalRequired(false);
    execution.setSelfApprovalAllowed(false);
    execution.setPauseRequested(false);
    execution.setInputJson("{}");
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    return execution;
  }

  private static AiSkillExecutionEvent event(final long sequence) {
    AiSkillExecutionEvent event = new AiSkillExecutionEvent();
    event.setSequence(sequence);
    event.setType(SkillExecutionEventType.STEP_STARTED);
    event.setExecutionStatus(SkillExecutionStatus.RUNNING);
    event.setStepId("step-" + sequence);
    return event;
  }

  private static String canonicalJson(final Object value) throws Exception {
    return new ObjectMapper().copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .writeValueAsString(value);
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
