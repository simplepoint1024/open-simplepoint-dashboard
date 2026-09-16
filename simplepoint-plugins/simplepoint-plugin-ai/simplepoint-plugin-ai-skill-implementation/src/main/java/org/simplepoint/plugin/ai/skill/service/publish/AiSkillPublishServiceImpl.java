package org.simplepoint.plugin.ai.skill.service.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftPublishRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftValidationStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStage;
import org.simplepoint.plugin.ai.skill.api.model.SkillPublishTaskStatus;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPublishTaskRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillPublishService;
import org.simplepoint.plugin.ai.skill.service.support.SkillOperationAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed submission and inspection of managed publications. */
@Service
public class AiSkillPublishServiceImpl implements AiSkillPublishService {

  private static final Pattern SEMANTIC_VERSION = Pattern.compile(
      "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
          + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
          + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillDefinitionRepository skillRepository;

  private final AiSkillDraftRepository draftRepository;

  private final AiSkillDraftRevisionRepository revisionRepository;

  private final AiSkillPublishTaskRepository taskRepository;

  private final AiSkillVersionRepository versionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final ObjectMapper canonicalMapper;

  private final AiSkillPublishMetrics metrics;

  /** Creates the durable publication service. */
  public AiSkillPublishServiceImpl(
      final AiSkillDefinitionRepository skillRepository,
      final AiSkillDraftRepository draftRepository,
      final AiSkillDraftRevisionRepository revisionRepository,
      final AiSkillPublishTaskRepository taskRepository,
      final AiSkillVersionRepository versionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final ObjectMapper objectMapper,
      final AiSkillPublishMetrics metrics
  ) {
    this.skillRepository = skillRepository;
    this.draftRepository = draftRepository;
    this.revisionRepository = revisionRepository;
    this.taskRepository = taskRepository;
    this.versionRepository = versionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.metrics = metrics;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillPublishTask start(
      final String skillId,
      final SkillDraftPublishRequest request
  ) {
    AiSkillDefinition skill = requireSkill(skillId, true, true);
    if (request == null || request.draftRevision() < 1) {
      throw new IllegalArgumentException("Skill Draft Revision is required");
    }
    String version = requireVersion(request.version());
    String idempotencyHash = sha256(requireIdempotencyKey(
        request.idempotencyKey()
    ));
    Optional<AiSkillPublishTask> existing =
        taskRepository.findActiveByIdempotency(
            skill.getId(),
            skill.getScopeType(),
            skill.getTenantId(),
            idempotencyHash
        );
    if (existing.isPresent()) {
      AiSkillPublishTask task = assertSameRequest(
          existing.get(),
          request,
          version
      );
      SkillOperationAudit.success(
          "PUBLISH_IDEMPOTENT_REPLAY",
          skill.getId(),
          task.getId(),
          "revision=" + task.getDraftRevision() + ",version=" + version
      );
      metrics.outcome("idempotent_replay");
      return task;
    }
    versionRepository.findActiveByVersionAndSkillId(version, skill.getId())
        .ifPresent(value -> {
          throw new IllegalArgumentException("Skill version already exists");
        });
    AiSkillDraft draft = draftRepository.findActiveBySkillId(skill.getId())
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft does not exist"
        ));
    AiSkillDraftRevision revision = revisionRepository
        .findActiveByDraftIdAndRevision(
            draft.getId(),
            request.draftRevision()
        )
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft Revision does not exist"
        ));
    if (revision.getValidationStatus() != SkillDraftValidationStatus.VALID
        || revision.getCompiledManifestJson() == null
        || revision.getContentHash() == null) {
      throw new IllegalStateException(
          "Only a valid compiled Skill Draft Revision can be published"
      );
    }
    Map<String, Object> source = readManifest(
        revision.getCompiledManifestJson()
    );
    if (!revision.getContentHash().equals(sha256(writeManifest(source)))) {
      throw new IllegalStateException(
          "Skill Draft Revision content hash is invalid"
      );
    }
    Map<String, Object> manifest = publishManifest(
        source,
        skill.getCode(),
        version
    );
    AiSkillPublishTask task = new AiSkillPublishTask();
    task.setSkillId(skill.getId());
    task.setDraftId(draft.getId());
    task.setDraftRevision(revision.getRevision());
    task.setDraftContentHash(revision.getContentHash());
    task.setScopeType(skill.getScopeType());
    task.setTenantId(skill.getTenantId());
    task.setRequestedBy(currentUserId());
    task.setCreatedBy(currentUserId());
    task.setUpdatedBy(currentUserId());
    task.setVersion(version);
    task.setActivate(request.activate());
    task.setIdempotencyKeyHash(idempotencyHash);
    task.setManifestJson(writeManifest(manifest));
    task.setStatus(SkillPublishTaskStatus.PENDING);
    task.setStage(SkillPublishTaskStage.QUEUED);
    task.setAttemptCount(0);
    task.setNextAttemptAt(Instant.now());
    task.setLeaseToken(0L);
    AiSkillPublishTask saved = taskRepository.save(task);
    SkillOperationAudit.success(
        "PUBLISH_SUBMIT",
        skill.getId(),
        saved.getId(),
        "revision=" + saved.getDraftRevision() + ",version=" + version
    );
    metrics.outcome("submitted");
    return saved;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiSkillPublishTask> findAll(
      final String skillId,
      final Pageable pageable
  ) {
    AiSkillDefinition skill = requireSkill(skillId, false, false);
    return taskRepository.findAllActiveBySkillAndScope(
        skill.getId(),
        skill.getScopeType(),
        skill.getTenantId(),
        pageable
    );
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiSkillPublishTask> find(
      final String skillId,
      final String taskId
  ) {
    AiSkillDefinition skill = requireSkill(skillId, false, false);
    return taskRepository.findActiveById(requireText(taskId, "Task ID", 64))
        .filter(task -> task.getSkillId().equals(skill.getId())
            && task.getScopeType() == skill.getScopeType()
            && java.util.Objects.equals(
                task.getTenantId(),
                skill.getTenantId()
            ));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiSkillPublishTask retry(
      final String skillId,
      final String taskId
  ) {
    AiSkillDefinition skill = requireSkill(skillId, true, true);
    AiSkillPublishTask task = taskRepository.findActiveByIdForUpdate(
        requireText(taskId, "Task ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException(
        "Skill publication task does not exist"
    ));
    assertOwned(task, skill);
    if (task.getStatus() != SkillPublishTaskStatus.FAILED) {
      throw new IllegalStateException(
          "Only a failed Skill publication task can be retried"
      );
    }
    task.setStatus(SkillPublishTaskStatus.PENDING);
    task.setStage(SkillPublishTaskStage.QUEUED);
    task.setAttemptCount(0);
    task.setNextAttemptAt(Instant.now());
    task.setCompletedAt(null);
    task.setErrorCode(null);
    task.setErrorMessage(null);
    task.setUpdatedBy(currentUserId());
    clearLease(task);
    AiSkillPublishTask saved = taskRepository.save(task);
    SkillOperationAudit.success(
        "PUBLISH_RETRY",
        skill.getId(),
        saved.getId(),
        "version=" + saved.getVersion()
    );
    metrics.outcome("manual_retry");
    return saved;
  }

  private AiSkillDefinition requireSkill(
      final String skillId,
      final boolean write,
      final boolean lock
  ) {
    String id = requireText(skillId, "Skill ID", 64);
    AiSkillDefinition skill = (lock
        ? skillRepository.findActiveByIdForUpdate(id)
        : skillRepository.findActiveById(id))
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

  private AiSkillPublishTask assertSameRequest(
      final AiSkillPublishTask task,
      final SkillDraftPublishRequest request,
      final String version
  ) {
    if (task.getDraftRevision() != request.draftRevision()
        || !task.getVersion().equals(version)
        || task.getActivate() != request.activate()) {
      throw new IllegalArgumentException(
          "Idempotency key was already used for a different publication"
      );
    }
    return task;
  }

  private void assertOwned(
      final AiSkillPublishTask task,
      final AiSkillDefinition skill
  ) {
    if (!task.getSkillId().equals(skill.getId())
        || task.getScopeType() != skill.getScopeType()
        || !java.util.Objects.equals(task.getTenantId(), skill.getTenantId())) {
      throw new IllegalArgumentException(
          "Skill publication task does not exist"
      );
    }
  }

  private Map<String, Object> publishManifest(
      final Map<String, Object> source,
      final String code,
      final String version
  ) {
    final Map<String, Object> result = new LinkedHashMap<>(source);
    Object metadataValue = source.get("metadata");
    if (!(metadataValue instanceof Map<?, ?> metadataSource)) {
      throw new IllegalStateException("Skill Manifest metadata is invalid");
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadataSource.forEach((key, value) ->
        metadata.put(String.valueOf(key), value));
    metadata.put("name", code);
    metadata.put("version", version);
    result.put("metadata", metadata);
    return result;
  }

  private Map<String, Object> readManifest(final String value) {
    try {
      return canonicalMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Persisted Skill Manifest is invalid",
          ex
      );
    }
  }

  private String writeManifest(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Skill Manifest cannot be encoded", ex);
    }
  }

  private String requireVersion(final String value) {
    String version = requireText(value, "Skill semantic version", 64);
    if (!SEMANTIC_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Skill semantic version is invalid");
    }
    return version;
  }

  private String requireIdempotencyKey(final String value) {
    return requireText(value, "Idempotency key", 256);
  }

  private String requireText(
      final String value,
      final String label,
      final int maximumLength
  ) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty() || result.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return result;
  }

  private String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(
              value.getBytes(StandardCharsets.UTF_8)
          )
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private void clearLease(final AiSkillPublishTask task) {
    task.setLeaseOwner(null);
    task.setLeaseExpiresAt(null);
  }

  private static String currentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }
}
