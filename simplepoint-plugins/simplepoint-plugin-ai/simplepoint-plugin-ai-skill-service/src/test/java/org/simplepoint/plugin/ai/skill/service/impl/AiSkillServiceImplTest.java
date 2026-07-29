package org.simplepoint.plugin.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus;
import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.SkillArtifactVerifier;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
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
  private AiScopeAccessPolicy scopeAccessPolicy;

  @Mock
  private AiMcpServerDefinitionService mcpServerService;

  @Mock
  private SkillArtifactVerifier artifactVerifier;

  private AiSkillServiceImpl service;

  private AiSkillDefinition skill;

  @BeforeEach
  void setUp() {
    service = new AiSkillServiceImpl(
        skillRepository,
        versionRepository,
        bindingRepository,
        scopeAccessPolicy,
        mcpServerService,
        artifactVerifier,
        new SkillJsonSchemaValidator(),
        new SkillWorkflowTemplateResolver(),
        new ObjectMapper()
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
    when(mcpServerService.getSnapshot("server-a", "snapshot-a"))
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
  void rejectsWorkflowStepTypesNotImplementedByV1Alpha1Executor() {
    Map<String, Object> manifest = manifestWithStep(
        false,
        Map.of("id", "step-a", "type", "prompt")
    );
    stubArtifact(manifest);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));

    assertThatThrownBy(() -> service.createVersion(
        "skill-a",
        request(manifest)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported workflow step type");
  }

  @Test
  void publishActivatesAnImmutableVersion() {
    AiSkillVersion version = new AiSkillVersion();
    version.setId("version-a");
    version.setSkillId(skill.getId());
    version.setStatus(SkillVersionStatus.DRAFT);
    version.setManifestJson("{}");
    version.setInputSchemaJson("{}");
    version.setOutputSchemaJson("{}");
    version.setWorkflowJson("{}");
    version.setArtifactVerifiedAt(Instant.now());
    version.setArtifactSignatureRequired(true);
    version.setArtifactSignatureVerified(true);
    when(skillRepository.findActiveByIdForUpdate("skill-a"))
        .thenReturn(Optional.of(skill));
    when(versionRepository.findActiveByIdAndSkillIdForUpdate(
        "version-a",
        "skill-a"
    )).thenReturn(Optional.of(version));
    when(bindingRepository.findAllActiveBySkillVersionId("version-a"))
        .thenReturn(List.of());

    AiSkillVersion result = service.publishVersion("skill-a", "version-a");

    assertThat(result.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
    assertThat(result.getPublishedAt()).isBeforeOrEqualTo(Instant.now());
    assertThat(skill.getActiveVersionId()).isEqualTo("version-a");
    assertThat(skill.getStatus()).isEqualTo(SkillStatus.ACTIVE);
    verify(versionRepository).save(version);
    verify(skillRepository).save(skill);
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

  private McpCapabilitySnapshotDetails snapshot() {
    McpToolDescriptor tool = new McpToolDescriptor(
        "summarize",
        "Summarize",
        "Summarizes text",
        Map.of("type", "object", "properties", Map.of()),
        Map.of("type", "object", "properties", Map.of()),
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
        List.of(),
        List.of(),
        List.of()
    );
  }
}
