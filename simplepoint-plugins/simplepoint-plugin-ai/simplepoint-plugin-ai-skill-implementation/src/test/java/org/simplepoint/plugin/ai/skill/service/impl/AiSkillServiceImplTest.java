package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
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
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.SkillArtifactVerifier;
import org.simplepoint.plugin.ai.skill.service.support.SkillApprovalPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillBudgetPolicy;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

@ExtendWith(MockitoExtension.class)
class AiSkillServiceImplTest {

  @Mock
  private AiSkillDefinitionRepository skillRepository;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiSkillToolBindingRepository bindingRepository;

  @Mock
  private AiSkillPromptBindingRepository promptBindingRepository;

  @Mock
  private AiSkillResourceBindingRepository resourceBindingRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private AiMcpServerDefinitionService mcpServerService;

  @Mock
  private SkillArtifactVerifier artifactVerifier;

  private AiSkillServiceImpl service;

  private AiSkillDefinition skill;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    SkillWorkflowTemplateResolver templateResolver =
        new SkillWorkflowTemplateResolver();
    SkillWorkflowConditionEvaluator conditionEvaluator =
        new SkillWorkflowConditionEvaluator(templateResolver);
    service = new AiSkillServiceImpl(
        skillRepository,
        versionRepository,
        bindingRepository,
        promptBindingRepository,
        resourceBindingRepository,
        scopeAccessPolicy,
        mcpServerService,
        artifactVerifier,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowPlanCompiler(
            templateResolver,
            conditionEvaluator
        ),
        new SkillBudgetPolicy(
            new SkillExecutionProperties(),
            objectMapper
        ),
        new SkillApprovalPolicy(objectMapper),
        objectMapper
    );
    skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setCode("document-summary");
    skill.setName("Document summary");
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setEnabled(true);
    skill.setStatus(SkillStatus.DRAFT);
  }

  @Test
  void createsImmutableVersionWithPinnedToolSchemas() {
    Map<String, Object> manifest = manifest(true, false);
    stubArtifact(manifest);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByVersionAndSkillId("1.0.0", "skill-a"))
        .thenReturn(Optional.empty());
    when(mcpServerService.getSnapshotForScope(
        "server-a",
        "snapshot-a",
        AiResourceScope.SYSTEM,
        null
    ))
        .thenReturn(snapshot());
    when(versionRepository.save(any(AiSkillVersion.class))).thenAnswer(invocation -> {
      AiSkillVersion version = invocation.getArgument(0);
      version.setId("version-a");
      return version;
    });
    when(bindingRepository.save(any(AiSkillToolBinding.class))).thenAnswer(
        invocation -> {
          AiSkillToolBinding binding = invocation.getArgument(0);
          binding.setId("binding-a");
          return binding;
        }
    );

    AiSkillVersion result = service.createVersion(
        "skill-a",
        request(manifest)
    );

    assertThat(result.getStatus()).isEqualTo(SkillVersionStatus.DRAFT);
    assertThat(result.getContentHash()).hasSize(64);
    assertThat(result.getArtifactDigest()).startsWith("sha256:");
    assertThat(result.getArtifactSignatureVerified()).isTrue();
    assertThat(result.getArtifactVerifiedAt()).isNotNull();
    assertThat(result.getBudget()).satisfies(budget -> {
      assertThat(budget.maximumToolCalls()).isEqualTo(1);
      assertThat(budget.maximumDurationSeconds()).isEqualTo(300);
      assertThat(budget.maximumPayloadBytes()).isEqualTo(1024L * 1024L);
    });
    assertThat(result.getToolBindings()).singleElement().satisfies(binding -> {
      assertThat(binding.getMcpServerId()).isEqualTo("server-a");
      assertThat(binding.getCapabilitySnapshotId()).isEqualTo("snapshot-a");
      assertThat(binding.getToolName()).isEqualTo("summarize");
      assertThat(binding.getToolAlias()).isEqualTo("summary");
      assertThat(binding.getInputSchemaHash()).hasSize(64);
      assertThat(binding.getOutputSchemaHash()).hasSize(64);
    });
  }

  @Test
  void rejectsWorkflowThatReferencesUnboundTool() {
    Map<String, Object> manifest = manifest(false, false);
    stubArtifact(manifest);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));

    assertThatThrownBy(() -> service.createVersion(
        "skill-a",
        request(manifest)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbound Tool alias");
  }

  @Test
  void rejectsExecutableFieldsInDeclarativeManifest() {
    Map<String, Object> manifest = manifest(false, true);
    stubArtifact(manifest);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));

    assertThatThrownBy(() -> service.createVersion(
        "skill-a",
        request(manifest)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot contain executable field");
  }

  @Test
  void createsVersionWithPinnedPromptAndResourceBindings() {
    Map<String, Object> manifest = manifestWithPromptAndResource();
    stubArtifact(manifest);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByVersionAndSkillId("1.0.0", "skill-a"))
        .thenReturn(Optional.empty());
    when(mcpServerService.getSnapshotForScope(
        "server-a",
        "snapshot-a",
        AiResourceScope.SYSTEM,
        null
    ))
        .thenReturn(snapshot());
    when(versionRepository.save(any(AiSkillVersion.class))).thenAnswer(invocation -> {
      AiSkillVersion version = invocation.getArgument(0);
      version.setId("version-a");
      return version;
    });
    when(promptBindingRepository.save(any(AiSkillPromptBinding.class)))
        .thenAnswer(invocation -> {
          AiSkillPromptBinding binding = invocation.getArgument(0);
          binding.setId("prompt-binding-a");
          return binding;
        });
    when(resourceBindingRepository.save(any(AiSkillResourceBinding.class)))
        .thenAnswer(invocation -> {
          AiSkillResourceBinding binding = invocation.getArgument(0);
          binding.setId("resource-binding-a");
          return binding;
        });

    AiSkillVersion result = service.createVersion("skill-a", request(manifest));

    assertThat(result.getBudget().maximumToolCalls()).isEqualTo(1);
    assertThat(result.getPromptBindings()).singleElement().satisfies(binding -> {
      assertThat(binding.getPromptAlias()).isEqualTo("welcome");
      assertThat(binding.getPromptName()).isEqualTo("welcome");
      assertThat(binding.getDescriptorHash()).hasSize(64);
    });
    assertThat(result.getResourceBindings()).singleElement().satisfies(binding -> {
      assertThat(binding.getResourceAlias()).isEqualTo("document");
      assertThat(binding.getResourceSelector())
          .isEqualTo("document://{documentId}");
      assertThat(binding.getResourceTemplate()).isTrue();
      assertThat(binding.getDescriptorHash()).hasSize(64);
    });
  }

  @Test
  void publishActivatesAnImmutableVersion() {
    AiSkillVersion version = publishableVersion(SkillVersionStatus.DRAFT);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByIdAndSkillIdForUpdate(
        "version-a",
        "skill-a"
    )).thenReturn(Optional.of(version));
    AiSkillToolBinding binding = new AiSkillToolBinding();
    binding.setToolAlias("echo");
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of(binding));

    AiSkillVersion result = service.publishVersion("skill-a", "version-a");

    assertThat(result.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
    assertThat(result.getPublishedAt()).isBeforeOrEqualTo(Instant.now());
    assertThat(skill.getActiveVersionId()).isEqualTo("version-a");
    assertThat(skill.getStatus()).isEqualTo(SkillStatus.ACTIVE);
    verify(versionRepository).save(version);
    verify(skillRepository).save(skill);
  }

  @Test
  void managedPublicationWithoutActivationIsBindableButLeavesSkillInactive() {
    AiSkillVersion version = publishableVersion(SkillVersionStatus.DRAFT);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByIdAndSkillIdForUpdate(
        "version-a",
        "skill-a"
    )).thenReturn(Optional.of(version));

    AiSkillVersion result = service.publishManagedVersion(
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "version-a",
        false
    );

    assertThat(result.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
    assertThat(skill.getActiveVersionId()).isNull();
    assertThat(skill.getStatus()).isEqualTo(SkillStatus.DRAFT);
    verify(versionRepository).save(version);
    verify(skillRepository, never()).save(skill);
  }

  @Test
  void managedActivationReassertsActiveVersionAfterRestartCheckpoint() {
    AiSkillVersion version = publishableVersion(SkillVersionStatus.PUBLISHED);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByIdAndSkillIdForUpdate(
        "version-a",
        "skill-a"
    )).thenReturn(Optional.of(version));

    service.publishManagedVersion(
        "skill-a",
        AiResourceScope.SYSTEM,
        null,
        "version-a",
        true
    );

    assertThat(skill.getActiveVersionId()).isEqualTo("version-a");
    assertThat(skill.getStatus()).isEqualTo(SkillStatus.ACTIVE);
    verify(versionRepository, never()).save(version);
    verify(skillRepository).save(skill);
  }

  private AiSkillVersion publishableVersion(
      final SkillVersionStatus status
  ) {
    AiSkillVersion version = new AiSkillVersion();
    version.setId("version-a");
    version.setSkillId(skill.getId());
    version.setStatus(status);
    version.setManifestJson("{\"spec\":{}}");
    version.setInputSchemaJson("{}");
    version.setOutputSchemaJson("{}");
    version.setWorkflowJson(
        "{\"steps\":[{\"id\":\"step-a\",\"type\":\"tool\",\"tool\":\"echo\"}]}"
    );
    version.setArtifactVerifiedAt(Instant.now());
    version.setArtifactSignatureRequired(true);
    version.setArtifactSignatureVerified(true);
    AiSkillToolBinding binding = new AiSkillToolBinding();
    binding.setToolAlias("echo");
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of(binding));
    return version;
  }

  private SkillVersionCreateRequest request(
      final Map<String, Object> manifest
  ) {
    return new SkillVersionCreateRequest(
        "1.0.0",
        "registry.example.com/skills/document-summary:1.0.0",
        "sha256:" + "a".repeat(64),
        manifest
    );
  }

  private void stubArtifact(final Map<String, Object> manifest) {
    when(artifactVerifier.verify(
        "registry.example.com/skills/document-summary:1.0.0",
        "sha256:" + "a".repeat(64)
    )).thenReturn(new VerifiedSkillArtifact(
        "sha256:" + "a".repeat(64),
        "application/vnd.simplepoint.skill.v1+json",
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64),
        manifest,
        true,
        true,
        "sha256:" + "d".repeat(64),
        Instant.now()
    ));
  }

  private Map<String, Object> manifest(
      final boolean bindTool,
      final boolean executable
  ) {
    Map<String, Object> step = executable
        ? Map.of("id", "step-a", "type", "prompt", "command", "whoami")
        : Map.of("id", "step-a", "type", "tool", "tool", "summary");
    return manifestWithStep(bindTool, step);
  }

  private Map<String, Object> manifestWithStep(
      final boolean bindTool,
      final Map<String, Object> step
  ) {
    List<Map<String, Object>> tools = bindTool
        ? List.of(Map.of(
            "alias", "summary",
            "serverId", "server-a",
            "snapshotId", "snapshot-a",
            "name", "summarize"
        ))
        : List.of();
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "document-summary",
            "version", "1.0.0"
        ),
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", tools,
            "workflow", Map.of("steps", List.of(step))
        )
    );
  }

  private Map<String, Object> manifestWithPromptAndResource() {
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "document-summary",
            "version", "1.0.0"
        ),
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(),
            "prompts", List.of(Map.of(
                "alias", "welcome",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "name", "welcome"
            )),
            "resources", List.of(Map.of(
                "alias", "document",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "uriTemplate", "document://{documentId}"
            )),
            "workflow", Map.of("steps", List.of(
                Map.of(
                    "id", "prompt-step",
                    "type", "prompt",
                    "prompt", "welcome"
                ),
                Map.of(
                    "id", "resource-step",
                    "type", "resource",
                    "resource", "document",
                    "uri", "document://42"
                )
            ))
        )
    );
  }

  private McpCapabilitySnapshotDetails snapshot() {
    McpToolDescriptor tool = new McpToolDescriptor(
        "summarize",
        "Summarize",
        "Summarizes text",
        Map.of("type", "object", "properties", Map.of()),
        Map.of("type", "object", "properties", Map.of()),
        Map.of("readOnlyHint", true),
        List.of()
    );
    McpPromptDescriptor prompt = new McpPromptDescriptor(
        "welcome",
        "Welcome",
        "Builds a welcome message",
        List.of(Map.of("name", "name", "required", false)),
        Map.of(),
        List.of()
    );
    McpResourceDescriptor resource = new McpResourceDescriptor(
        "document://fixed",
        "Fixed document",
        null,
        "A fixed document",
        "text/plain",
        null,
        Map.of(),
        Map.of(),
        List.of()
    );
    McpResourceTemplateDescriptor resourceTemplate =
        new McpResourceTemplateDescriptor(
            "document://{documentId}",
            "Document",
            null,
            "A document by identifier",
            "text/plain",
            Map.of(),
            Map.of(),
            List.of()
        );
    return new McpCapabilitySnapshotDetails(
        "snapshot-a",
        "server-a",
        "2025-11-25",
        "test",
        "1.0.0",
        "schema-hash",
        Instant.now(),
        true,
        Map.of(),
        List.of(tool),
        List.of(resource),
        List.of(resourceTemplate),
        List.of(prompt)
    );
  }
}
