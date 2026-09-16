package org.simplepoint.plugin.ai.skill.service.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.Edge;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.MockMode;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.NodeMock;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestCase;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRevisionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftSaveRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

@ExtendWith(MockitoExtension.class)
class AiSkillDraftServiceImplTest {

  @Mock
  private AiSkillDefinitionRepository skillRepository;

  @Mock
  private AiSkillDraftRepository draftRepository;

  @Mock
  private AiSkillDraftRevisionRepository revisionRepository;

  @Mock
  private AiSkillVersionRepository versionRepository;

  @Mock
  private AiScopeAccessPolicy scopeAccessPolicy;

  private ObjectMapper objectMapper;

  private SkillDesignerManifestAdapter adapter;

  private AiSkillDraftServiceImpl service;

  private AiSkillDefinition skill;

  @BeforeEach
  void setUp() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("designer-user");
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        context
    );
    objectMapper = new ObjectMapper();
    adapter = new SkillDesignerManifestAdapter();
    SkillWorkflowTemplateResolver resolver =
        new SkillWorkflowTemplateResolver();
    SkillDesignerCompiler compiler = new SkillDesignerCompiler(
        adapter,
        new SkillWorkflowPlanCompiler(
            resolver,
            new SkillWorkflowConditionEvaluator(resolver)
        ),
        new SkillDesignerValidator(new SkillJsonSchemaValidator()),
        objectMapper
    );
    service = new AiSkillDraftServiceImpl(
        skillRepository,
        draftRepository,
        revisionRepository,
        versionRepository,
        scopeAccessPolicy,
        compiler,
        adapter,
        objectMapper
    );
    skill = new AiSkillDefinition();
    skill.setId("skill-a");
    skill.setScopeType(AiResourceScope.SYSTEM);
    skill.setName("Draft test");
    when(skillRepository.findActiveById("skill-a"))
        .thenReturn(Optional.of(skill));
  }

  @AfterEach
  void clearAuthorizationContext() {
    RequestContextHolder.clearContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY
    );
  }

  @Test
  void createsCompiledDraftAndImmutableRevision() {
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.empty());
    when(draftRepository.save(any(AiSkillDraft.class)))
        .thenAnswer(invocation -> {
          AiSkillDraft draft = invocation.getArgument(0);
          draft.setId("draft-a");
          return draft;
        });
    when(revisionRepository.save(any(AiSkillDraftRevision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.save(
        "skill-a",
        new SkillDraftSaveRequest(0L, document())
    );

    assertThat(result.revision()).isEqualTo(1);
    assertThat(result.compilation().valid()).isTrue();
    assertThat(result.compilation().contentHash()).hasSize(64);
    ArgumentCaptor<AiSkillDraftRevision> revision =
        ArgumentCaptor.forClass(AiSkillDraftRevision.class);
    verify(revisionRepository).save(revision.capture());
    assertThat(revision.getValue().getDraftId()).isEqualTo("draft-a");
    assertThat(revision.getValue().getRevision()).isEqualTo(1);
    assertThat(revision.getValue().getRevisionSource())
        .isEqualTo(SkillDraftRevisionSource.SAVE);
    assertThat(revision.getValue().getCreatedBy()).isEqualTo("designer-user");
    assertThat(revision.getValue().getValidationStatus())
        .isEqualTo(SkillDraftValidationStatus.VALID);
    verify(scopeAccessPolicy).assertCanManageOwnedResource(
        AiResourceScope.SYSTEM,
        null
    );
  }

  @Test
  void rejectsStaleExplicitRevision() {
    AiSkillDraft draft = draft(3L, document());
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));

    assertThatThrownBy(() -> service.save(
        "skill-a",
        new SkillDraftSaveRequest(2L, document())
    )).isInstanceOf(SkillDraftConflictException.class)
        .satisfies(exception -> {
          SkillDraftConflictException conflict =
              (SkillDraftConflictException) exception;
          assertThat(conflict.getExpectedRevision()).isEqualTo(2L);
          assertThat(conflict.getCurrentRevision()).isEqualTo(3L);
        });
  }

  @Test
  void persistsInvalidGraphWithStructuredDiagnostics() {
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.empty());
    when(draftRepository.save(any(AiSkillDraft.class)))
        .thenAnswer(invocation -> {
          AiSkillDraft draft = invocation.getArgument(0);
          draft.setId("draft-a");
          return draft;
        });
    when(revisionRepository.save(any(AiSkillDraftRevision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    SkillDesignerDocument source = document();
    SkillDesignerDocument invalid = copy(source, List.of(), source.tests());

    var result = service.save(
        "skill-a",
        new SkillDraftSaveRequest(0L, invalid)
    );

    assertThat(result.compilation().valid()).isFalse();
    assertThat(result.compilation().diagnostics())
        .isNotEmpty()
        .extracting(diagnostic -> diagnostic.code())
        .containsOnly("SKILL_DESIGNER_EDGE_MISSING");
  }

  @Test
  void restoresSnapshotAsNewRevision() throws Exception {
    SkillDesignerDocument document = document();
    final AiSkillDraft draft = draft(2L, document);
    AiSkillDraftRevision snapshot = new AiSkillDraftRevision();
    snapshot.setDraftId("draft-a");
    snapshot.setRevision(1L);
    snapshot.setDesignerJson(objectMapper.writeValueAsString(document));
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(draft));
    when(revisionRepository.findActiveByDraftIdAndRevision("draft-a", 1L))
        .thenReturn(Optional.of(snapshot));
    when(draftRepository.save(draft)).thenReturn(draft);
    when(revisionRepository.save(any(AiSkillDraftRevision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.restore(
        "skill-a",
        1L,
        new SkillDraftRestoreRequest(2L)
    );

    assertThat(result.revision()).isEqualTo(3L);
    ArgumentCaptor<AiSkillDraftRevision> restored =
        ArgumentCaptor.forClass(AiSkillDraftRevision.class);
    verify(revisionRepository).save(restored.capture());
    assertThat(restored.getValue().getRevisionSource())
        .isEqualTo(SkillDraftRevisionSource.RESTORE);
  }

  @Test
  void refusesPlaintextSecretsInTestInput() {
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.empty());
    SkillDesignerDocument source = document();
    TestCase test = new TestCase(
        "secret-case",
        "Secret case",
        true,
        Map.of("apiKey", "plaintext"),
        List.of(),
        List.of()
    );
    SkillDesignerDocument unsafe = copy(
        source,
        source.edges(),
        List.of(test)
    );

    assertThatThrownBy(() -> service.save(
        "skill-a",
        new SkillDraftSaveRequest(0L, unsafe)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot persist plaintext secret")
        .hasMessageContaining("/tests/0/input/apiKey");
  }

  @Test
  void refusesPlaintextSecretsHiddenInMockOutput() {
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.empty());
    SkillDesignerDocument source = document();
    TestCase test = new TestCase(
        "secret-mock",
        "Secret mock",
        true,
        Map.of(),
        List.of(new NodeMock(
            "echo-step",
            MockMode.SUCCESS,
            Map.of("authorization", "Bearer secret"),
            null,
            null
        )),
        List.of()
    );

    assertThatThrownBy(() -> service.save(
        "skill-a",
        new SkillDraftSaveRequest(
            0L,
            copy(source, source.edges(), List.of(test))
        )
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot persist plaintext secret")
        .hasMessageContaining("/tests/0/mocks/0/output/authorization");
  }

  @Test
  void rejectsOversizedDocumentBeforeCompilationOrPersistence() {
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.empty());
    SkillDesignerDocument source = document();
    SkillDesignerDocument oversized = new SkillDesignerDocument(
        source.schemaVersion(),
        Map.of(
            "name", "draft-test",
            "version", "0.1.0",
            "description", "x".repeat(2 * 1024 * 1024)
        ),
        source.inputSchema(),
        source.outputSchema(),
        source.tools(),
        source.prompts(),
        source.resources(),
        source.nodes(),
        source.ports(),
        source.edges(),
        source.workflowOutput(),
        source.budgets(),
        source.approvals(),
        source.tests(),
        source.viewport()
    );

    assertThatThrownBy(() -> service.save(
        "skill-a",
        new SkillDraftSaveRequest(0L, oversized)
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds 2 MiB");
  }

  @Test
  void exposesCompatibleImmutableVersionAsReadOnlyDocument() throws Exception {
    AiSkillVersion version = version("version-a", "1.2.3", manifest());
    when(versionRepository.findActiveByIdAndSkillId("version-a", "skill-a"))
        .thenReturn(Optional.of(version));

    var result = service.viewVersion("skill-a", "version-a");

    assertThat(result.compatible()).isTrue();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().nodes())
        .extracting(SkillDesignerDocument.Node::id)
        .contains("echo-step");
    assertThat(result.manifest()).isEqualTo(manifest());
    verify(scopeAccessPolicy).assertCanReadManagedResource(
        AiResourceScope.SYSTEM,
        null
    );
  }

  @Test
  void retainsOriginalManifestWhenVersionIsNotDesignerCompatible()
      throws Exception {
    Map<String, Object> futureManifest = Map.of(
        "apiVersion", "simplepoint.io/v2",
        "kind", "Skill",
        "metadata", Map.of("name", "future"),
        "spec", Map.of()
    );
    AiSkillVersion version = version("version-future", "2.0.0", futureManifest);
    when(versionRepository.findActiveByIdAndSkillId(
        "version-future", "skill-a"
    )).thenReturn(Optional.of(version));

    var result = service.viewVersion("skill-a", "version-future");

    assertThat(result.compatible()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.compatibilityMessage()).contains("apiVersion");
    assertThat(result.manifest()).isEqualTo(futureManifest);
  }

  @Test
  void copiesCompatibleVersionAsNewDraftRevision() throws Exception {
    AiSkillVersion version = version("version-a", "1.2.3", manifest());
    AiSkillDraft existing = draft(4L, document());
    when(versionRepository.findActiveByIdAndSkillId("version-a", "skill-a"))
        .thenReturn(Optional.of(version));
    when(draftRepository.findActiveBySkillId("skill-a"))
        .thenReturn(Optional.of(existing));
    when(draftRepository.save(existing)).thenReturn(existing);
    when(revisionRepository.save(any(AiSkillDraftRevision.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.copyVersion(
        "skill-a",
        "version-a",
        new SkillDraftRestoreRequest(4L)
    );

    assertThat(result.revision()).isEqualTo(5L);
    assertThat(result.document().nodes())
        .extracting(SkillDesignerDocument.Node::id)
        .contains("echo-step");
    ArgumentCaptor<AiSkillDraftRevision> copied =
        ArgumentCaptor.forClass(AiSkillDraftRevision.class);
    verify(revisionRepository).save(copied.capture());
    assertThat(copied.getValue().getRevisionSource())
        .isEqualTo(SkillDraftRevisionSource.VERSION_COPY);
  }

  private AiSkillDraft draft(
      final long revision,
      final SkillDesignerDocument document
  ) {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setId("draft-a");
    draft.setSkillId("skill-a");
    draft.setScopeType(AiResourceScope.SYSTEM);
    draft.setRevision(revision);
    try {
      draft.setDesignerJson(objectMapper.writeValueAsString(document));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    return draft;
  }

  private SkillDesignerDocument document() {
    return adapter.fromManifest(manifest());
  }

  private Map<String, Object> manifest() {
    return Map.of(
        "apiVersion", "simplepoint.io/v1alpha1",
        "kind", "Skill",
        "metadata", Map.of(
            "name", "draft-test",
            "version", "0.1.0"
        ),
        "spec", Map.of(
            "inputSchema", Map.of("type", "object"),
            "outputSchema", Map.of("type", "object"),
            "tools", List.of(Map.of(
                "alias", "echo",
                "serverId", "server-a",
                "snapshotId", "snapshot-a",
                "name", "echo"
            )),
            "workflow", Map.of(
                "steps", List.of(Map.of(
                    "id", "echo-step",
                    "type", "tool",
                    "tool", "echo",
                    "arguments", Map.of()
                )),
                "output", Map.of()
            )
        )
    );
  }

  private AiSkillVersion version(
      final String id,
      final String semanticVersion,
      final Map<String, Object> manifest
  ) throws Exception {
    AiSkillVersion version = new AiSkillVersion();
    version.setId(id);
    version.setSkillId("skill-a");
    version.setVersion(semanticVersion);
    version.setManifestJson(objectMapper.writeValueAsString(manifest));
    return version;
  }

  private SkillDesignerDocument copy(
      final SkillDesignerDocument source,
      final List<Edge> edges,
      final List<TestCase> tests
  ) {
    return new SkillDesignerDocument(
        source.schemaVersion(),
        source.metadata(),
        source.inputSchema(),
        source.outputSchema(),
        source.tools(),
        source.prompts(),
        source.resources(),
        source.nodes(),
        source.ports(),
        edges,
        source.workflowOutput(),
        source.budgets(),
        source.approvals(),
        tests,
        source.viewport()
    );
  }
}
