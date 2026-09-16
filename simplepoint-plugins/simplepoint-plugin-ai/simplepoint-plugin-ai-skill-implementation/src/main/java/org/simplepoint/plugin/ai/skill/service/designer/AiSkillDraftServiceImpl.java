package org.simplepoint.plugin.ai.skill.service.designer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDiagnostic;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRevisionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRevisionView;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftSaveRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftView;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionDesignerView;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillDraftService;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.simplepoint.plugin.ai.skill.service.support.SkillOperationAudit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed lifecycle for mutable Skill Designer Drafts. */
@Service
public class AiSkillDraftServiceImpl implements AiSkillDraftService {

  private static final int MAXIMUM_DESIGNER_BYTES = 2 * 1024 * 1024;

  private static final int MAXIMUM_SECRET_SCAN_DEPTH = 64;

  private static final int MAXIMUM_SECRET_SCAN_VALUES = 100_000;

  private static final TypeReference<List<SkillDesignerDiagnostic>>
      DIAGNOSTIC_LIST = new TypeReference<>() {
      };

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final Set<String> SENSITIVE_KEYS = Set.of(
      "password",
      "secret",
      "clientsecret",
      "apikey",
      "token",
      "accesstoken",
      "refreshtoken",
      "authorization",
      "credential",
      "privatekey"
  );

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillDraftRepository draftRepository;

  private final AiSkillDraftRevisionRepository revisionRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillDesignerCompiler compiler;

  private final SkillDesignerManifestAdapter manifestAdapter;

  private final ObjectMapper objectMapper;

  /** Creates the Draft lifecycle service. */
  public AiSkillDraftServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillDraftRepository draftRepository,
      final AiSkillDraftRevisionRepository revisionRepository,
      final AiSkillVersionRepository versionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillDesignerCompiler compiler,
      final SkillDesignerManifestAdapter manifestAdapter,
      final ObjectMapper objectMapper
  ) {
    this.skillRepository = skillRepository;
    this.draftRepository = draftRepository;
    this.revisionRepository = revisionRepository;
    this.versionRepository = versionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.compiler = compiler;
    this.manifestAdapter = manifestAdapter;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SkillDraftView> find(final String skillId) {
    AiSkillDefinition skill = requireSkill(skillId, false);
    return draftRepository.findActiveBySkillId(skill.getId()).map(this::view);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SkillDraftView save(
      final String skillId,
      final SkillDraftSaveRequest request
  ) {
    if (request == null || request.document() == null) {
      throw new IllegalArgumentException(
          "Skill Draft request and document are required"
      );
    }
    AiSkillDefinition skill = requireSkill(skillId, true);
    Long expectedRevision = requireExpectedRevision(request.expectedRevision());
    AiSkillDraft draft = draftRepository.findActiveBySkillId(skill.getId())
        .orElse(null);
    if (draft == null) {
      assertRevision(expectedRevision, 0L);
      draft = newDraft(skill);
    } else {
      assertRevision(expectedRevision, draft.getRevision());
    }
    return persist(draft, request.document(), SkillDraftRevisionSource.SAVE);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final String skillId, final long expectedRevision) {
    AiSkillDefinition skill = requireSkill(skillId, true);
    AiSkillDraft draft = requireDraft(skill.getId());
    assertRevision(expectedRevision, draft.getRevision());
    draft.setDeletedAt(Instant.now());
    draft.setUpdatedBy(currentUserId());
    try {
      draftRepository.save(draft);
      draftRepository.flush();
      SkillOperationAudit.success(
          "DRAFT_DELETE",
          skill.getId(),
          draft.getId(),
          "revision=" + expectedRevision
      );
    } catch (OptimisticLockingFailureException exception) {
      throw concurrentConflict(expectedRevision, exception);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SkillDraftRevisionView> findRevisions(
      final String skillId,
      final Pageable pageable
  ) {
    AiSkillDefinition skill = requireSkill(skillId, false);
    AiSkillDraft draft = requireDraft(skill.getId());
    return revisionRepository.findAllActiveByDraftId(
        draft.getId(),
        pageable
    ).map(this::revisionView);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SkillDraftView restore(
      final String skillId,
      final long revision,
      final SkillDraftRestoreRequest request
  ) {
    if (revision < 1 || request == null) {
      throw new IllegalArgumentException(
          "Draft Revision and restore request are required"
      );
    }
    AiSkillDefinition skill = requireSkill(skillId, true);
    AiSkillDraft draft = requireDraft(skill.getId());
    Long expectedRevision = requireExpectedRevision(request.expectedRevision());
    assertRevision(expectedRevision, draft.getRevision());
    AiSkillDraftRevision snapshot = revisionRepository
        .findActiveByDraftIdAndRevision(draft.getId(), revision)
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft Revision does not exist"
        ));
    return persist(
        draft,
        readDocument(snapshot.getDesignerJson()),
        SkillDraftRevisionSource.RESTORE
    );
  }

  @Override
  @Transactional(readOnly = true)
  public SkillDesignerCompilationResult compile(final String skillId) {
    AiSkillDefinition skill = requireSkill(skillId, false);
    AiSkillDraft draft = requireDraft(skill.getId());
    SkillDesignerCompilationResult result = compiler.compile(
        readDocument(draft.getDesignerJson())
    );
    SkillOperationAudit.success(
        "DRAFT_COMPILE",
        skill.getId(),
        draft.getId(),
        "revision=" + draft.getRevision() + ",valid=" + result.valid()
    );
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public SkillVersionDesignerView viewVersion(
      final String skillId,
      final String versionId
  ) {
    AiSkillDefinition skill = requireSkill(skillId, false);
    AiSkillVersion version = requireVersion(skill.getId(), versionId);
    Map<String, Object> manifest = readRequiredMap(
        version.getManifestJson(),
        "Persisted Skill Version Manifest"
    );
    try {
      SkillDesignerDocument document = manifestAdapter.fromManifest(manifest);
      return new SkillVersionDesignerView(
          skill.getId(),
          version.getId(),
          version.getVersion(),
          version.getStatus(),
          true,
          null,
          document,
          manifest
      );
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return new SkillVersionDesignerView(
          skill.getId(),
          version.getId(),
          version.getVersion(),
          version.getStatus(),
          false,
          exception.getMessage(),
          null,
          manifest
      );
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SkillDraftView copyVersion(
      final String skillId,
      final String versionId,
      final SkillDraftRestoreRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Version copy request is required");
    }
    AiSkillDefinition skill = requireSkill(skillId, true);
    AiSkillVersion version = requireVersion(skill.getId(), versionId);
    Long expectedRevision = requireExpectedRevision(request.expectedRevision());
    AiSkillDraft draft = draftRepository.findActiveBySkillId(skill.getId())
        .orElse(null);
    if (draft == null) {
      assertRevision(expectedRevision, 0L);
      draft = newDraft(skill);
    } else {
      assertRevision(expectedRevision, draft.getRevision());
    }
    Map<String, Object> manifest = readRequiredMap(
        version.getManifestJson(),
        "Persisted Skill Version Manifest"
    );
    SkillDesignerDocument document;
    try {
      document = manifestAdapter.fromManifest(manifest);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw new IllegalArgumentException(
          "Skill Version is not compatible with the current Designer: "
              + exception.getMessage(),
          exception
      );
    }
    return persist(draft, document, SkillDraftRevisionSource.VERSION_COPY);
  }

  private SkillDraftView persist(
      final AiSkillDraft draft,
      final SkillDesignerDocument document,
      final SkillDraftRevisionSource source
  ) {
    String designerJson = write(document, "Skill Designer Document");
    if (designerJson.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_DESIGNER_BYTES) {
      throw new IllegalArgumentException(
          "Skill Designer Document exceeds 2 MiB"
      );
    }
    assertNoPlaintextSecrets(document);
    SkillDesignerCompilationResult compilation = compiler.compile(document);
    long nextRevision = draft.getRevision() + 1;
    String actor = currentUserId();
    if (draft.getCreatedBy() == null) {
      draft.setCreatedBy(actor);
    }
    draft.setUpdatedBy(actor);
    applyCompilation(draft, nextRevision, designerJson, compilation);
    try {
      AiSkillDraft saved = draftRepository.save(draft);
      draftRepository.flush();
      revisionRepository.save(revision(saved, compilation, source, actor));
      revisionRepository.flush();
      SkillOperationAudit.success(
          "DRAFT_" + source.name(),
          saved.getSkillId(),
          saved.getId(),
          "revision=" + saved.getRevision()
              + ",contentHash=" + safeHash(saved.getContentHash())
      );
      return view(saved);
    } catch (OptimisticLockingFailureException
             | DataIntegrityViolationException exception) {
      throw concurrentConflict(nextRevision - 1, exception);
    }
  }

  private AiSkillDraft newDraft(final AiSkillDefinition skill) {
    AiSkillDraft draft = new AiSkillDraft();
    draft.setSkillId(skill.getId());
    draft.setScopeType(skill.getScopeType());
    draft.setTenantId(skill.getTenantId());
    draft.setRevision(0L);
    return draft;
  }

  private void applyCompilation(
      final AiSkillDraft draft,
      final long revision,
      final String designerJson,
      final SkillDesignerCompilationResult compilation
  ) {
    draft.setRevision(revision);
    draft.setDesignerJson(designerJson);
    draft.setCompiledManifestJson(compilation.valid()
        ? write(compilation.manifest(), "compiled Skill Manifest") : null);
    draft.setContentHash(compilation.contentHash());
    draft.setValidationStatus(compilation.valid()
        ? SkillDraftValidationStatus.VALID
        : SkillDraftValidationStatus.INVALID);
    draft.setValidationResultJson(write(
        compilation.diagnostics(),
        "Skill Draft diagnostics"
    ));
  }

  private AiSkillDraftRevision revision(
      final AiSkillDraft draft,
      final SkillDesignerCompilationResult compilation,
      final SkillDraftRevisionSource source,
      final String actor
  ) {
    AiSkillDraftRevision revision = new AiSkillDraftRevision();
    revision.setDraftId(draft.getId());
    revision.setSkillId(draft.getSkillId());
    revision.setRevision(draft.getRevision());
    revision.setRevisionSource(source);
    revision.setCreatedBy(actor);
    revision.setUpdatedBy(actor);
    revision.setDesignerJson(draft.getDesignerJson());
    revision.setCompiledManifestJson(draft.getCompiledManifestJson());
    revision.setContentHash(draft.getContentHash());
    revision.setValidationStatus(compilation.valid()
        ? SkillDraftValidationStatus.VALID
        : SkillDraftValidationStatus.INVALID);
    revision.setValidationResultJson(draft.getValidationResultJson());
    return revision;
  }

  private SkillDraftView view(final AiSkillDraft draft) {
    List<SkillDesignerDiagnostic> diagnostics = readDiagnostics(
        draft.getValidationResultJson()
    );
    SkillDesignerCompilationResult compilation =
        new SkillDesignerCompilationResult(
            draft.getValidationStatus() == SkillDraftValidationStatus.VALID,
            readOptionalMap(draft.getCompiledManifestJson()),
            draft.getContentHash(),
            diagnostics
        );
    return new SkillDraftView(
        draft.getId(),
        draft.getSkillId(),
        draft.getScopeType(),
        draft.getTenantId(),
        draft.getRevision(),
        readDocument(draft.getDesignerJson()),
        compilation,
        draft.getCreatedAt(),
        draft.getUpdatedAt()
    );
  }

  private SkillDraftRevisionView revisionView(
      final AiSkillDraftRevision revision
  ) {
    return new SkillDraftRevisionView(
        revision.getId(),
        revision.getDraftId(),
        revision.getRevision(),
        revision.getRevisionSource(),
        revision.getValidationStatus(),
        revision.getContentHash(),
        revision.getCreatedBy(),
        revision.getCreatedAt()
    );
  }

  private AiSkillDefinition requireSkill(
      final String skillId,
      final boolean write
  ) {
    if (skillId == null || skillId.isBlank()) {
      throw new IllegalArgumentException("Skill ID is required");
    }
    AiSkillDefinition skill = skillRepository.findActiveById(skillId.trim())
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill does not exist"
        ));
    if (write) {
      scopeAccessPolicy.assertCanManageOwnedResource(
          skill.getScopeType(),
          skill.getTenantId()
      );
    } else {
      scopeAccessPolicy.assertCanReadManagedResource(
          skill.getScopeType(),
          skill.getTenantId()
      );
    }
    return skill;
  }

  private AiSkillDraft requireDraft(final String skillId) {
    return draftRepository.findActiveBySkillId(skillId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft does not exist"
        ));
  }

  private AiSkillVersion requireVersion(
      final String skillId,
      final String versionId
  ) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("Skill Version ID is required");
    }
    return versionRepository.findActiveByIdAndSkillId(
        versionId.trim(),
        skillId
    ).orElseThrow(() -> new IllegalArgumentException(
        "Skill Version does not exist"
    ));
  }

  private Long requireExpectedRevision(final Long revision) {
    if (revision == null || revision < 0) {
      throw new IllegalArgumentException(
          "expectedRevision must be zero or greater"
      );
    }
    return revision;
  }

  private static String currentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }

  private static String safeHash(final String value) {
    return value == null ? "-" : value;
  }

  private void assertRevision(final long expected, final long current) {
    if (expected != current) {
      throw new SkillDraftConflictException(
          "Skill Draft changed since it was loaded",
          expected,
          current
      );
    }
  }

  private SkillDraftConflictException concurrentConflict(
      final long expectedRevision,
      final RuntimeException cause
  ) {
    SkillDraftConflictException conflict = new SkillDraftConflictException(
        "Skill Draft was changed by another request",
        expectedRevision,
        null
    );
    conflict.initCause(cause);
    return conflict;
  }

  private String write(final Object value, final String label) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(label + " cannot be serialized", exception);
    }
  }

  private SkillDesignerDocument readDocument(final String value) {
    try {
      return objectMapper.readValue(value, SkillDesignerDocument.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Persisted Skill Designer Document is invalid",
          exception
      );
    }
  }

  private List<SkillDesignerDiagnostic> readDiagnostics(final String value) {
    try {
      return objectMapper.readValue(value, DIAGNOSTIC_LIST);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Persisted Skill Draft diagnostics are invalid",
          exception
      );
    }
  }

  private Map<String, Object> readOptionalMap(final String value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Persisted compiled Skill Manifest is invalid",
          exception
      );
    }
  }

  private Map<String, Object> readRequiredMap(
      final String value,
      final String label
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(label + " is missing");
    }
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(label + " is invalid", exception);
    }
  }

  private void assertNoPlaintextSecrets(
      final SkillDesignerDocument document
  ) {
    scanSecrets(document.metadata(), "/metadata");
    scanSecrets(document.inputSchema(), "/inputSchema");
    scanSecrets(document.outputSchema(), "/outputSchema");
    scanSecrets(document.tools(), "/tools");
    scanSecrets(document.prompts(), "/prompts");
    scanSecrets(document.resources(), "/resources");
    scanSecrets(document.workflowOutput(), "/workflowOutput");
    scanSecrets(document.budgets(), "/budgets");
    scanSecrets(document.approvals(), "/approvals");
    if (document.nodes() != null) {
      for (int index = 0; index < document.nodes().size(); index++) {
        SkillDesignerDocument.Node node = document.nodes().get(index);
        if (node != null) {
          scanSecrets(
              node.configuration(),
              "/nodes/" + index + "/configuration"
          );
        }
      }
    }
    if (document.tests() != null) {
      for (int index = 0; index < document.tests().size(); index++) {
        SkillDesignerDocument.TestCase test = document.tests().get(index);
        if (test != null) {
          scanSecrets(
              test.input(),
              "/tests/" + index + "/input"
          );
          for (int mockIndex = 0; mockIndex < test.mocks().size(); mockIndex++) {
            scanSecrets(
                test.mocks().get(mockIndex).output(),
                "/tests/" + index + "/mocks/" + mockIndex + "/output"
            );
          }
          for (int assertionIndex = 0;
               assertionIndex < test.assertions().size();
               assertionIndex++) {
            scanSecrets(
                test.assertions().get(assertionIndex).expected(),
                "/tests/" + index + "/assertions/"
                    + assertionIndex + "/expected"
            );
          }
        }
      }
    }
  }

  private void scanSecrets(final Object value, final String path) {
    ArrayDeque<SecretFrame> pending = new ArrayDeque<>();
    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    if (value != null) {
      pending.add(new SecretFrame(value, path, 0));
    }
    int scanned = 0;
    while (!pending.isEmpty()) {
      SecretFrame frame = pending.removeLast();
      if (++scanned > MAXIMUM_SECRET_SCAN_VALUES
          || frame.depth() > MAXIMUM_SECRET_SCAN_DEPTH) {
        throw new IllegalArgumentException(
            "Skill Designer Document exceeds structural complexity limits"
        );
      }
      if (frame.value() instanceof Map<?, ?> map && visited.add(map)) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          String key = String.valueOf(entry.getKey());
          Object nested = entry.getValue();
          String nestedPath = frame.path() + "/" + key;
          if (SENSITIVE_KEYS.contains(normalizeKey(key))
              && nested != null && !isReference(nested)) {
            throw new IllegalArgumentException(
                "Skill Draft cannot persist plaintext secret at " + nestedPath
            );
          }
          if (nested != null) {
            pending.add(new SecretFrame(
                nested,
                nestedPath,
                frame.depth() + 1
            ));
          }
        }
      } else if (frame.value() instanceof List<?> list
          && visited.add(list)) {
        for (int index = 0; index < list.size(); index++) {
          Object nested = list.get(index);
          if (nested != null) {
            pending.add(new SecretFrame(
                nested,
                frame.path() + "/" + index,
                frame.depth() + 1
            ));
          }
        }
      }
    }
  }

  private boolean isReference(final Object value) {
    return value instanceof Map<?, ?> map
        && (map.containsKey("$ref") || map.containsKey("secretRef"));
  }

  private String normalizeKey(final String value) {
    return value.replaceAll("[^A-Za-z0-9]", "")
        .toLowerCase(Locale.ROOT);
  }

  private record SecretFrame(Object value, String path, int depth) {
  }
}
