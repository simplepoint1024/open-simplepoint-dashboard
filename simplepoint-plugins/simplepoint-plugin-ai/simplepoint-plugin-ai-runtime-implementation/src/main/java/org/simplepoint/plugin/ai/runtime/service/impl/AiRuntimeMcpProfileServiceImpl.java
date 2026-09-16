package org.simplepoint.plugin.ai.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpDescriptor;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpDescriptorImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportResult;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfilePublishRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileUpdateRequest;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpDescriptorRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpProfileRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpRevisionRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpProfileService;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolRevisionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scope-aware descriptor, Runtime Profile and revision management. */
@Service
public class AiRuntimeMcpProfileServiceImpl
    implements AiRuntimeMcpProfileService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

  private static final Pattern OCI_DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private final AiRuntimeMcpDescriptorRepository descriptorRepository;

  private final AiRuntimeMcpProfileRepository profileRepository;

  private final AiRuntimeMcpRevisionRepository revisionRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiRuntimePoolRevisionService poolRevisionService;

  private final AiRuntimeImageResolver imageResolver;

  private final AiRuntimeMcpAdmissionService admissionService;

  private final ObjectMapper canonicalMapper;

  /** Creates the descriptor and Runtime Profile management service. */
  public AiRuntimeMcpProfileServiceImpl(
      final AiRuntimeMcpDescriptorRepository descriptorRepository,
      final AiRuntimeMcpProfileRepository profileRepository,
      final AiRuntimeMcpRevisionRepository revisionRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiRuntimePoolRevisionService poolRevisionService,
      final AiRuntimeImageResolver imageResolver,
      final AiRuntimeMcpAdmissionService admissionService,
      final ObjectMapper objectMapper
  ) {
    this.descriptorRepository = descriptorRepository;
    this.profileRepository = profileRepository;
    this.revisionRepository = revisionRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.poolRevisionService = poolRevisionService;
    this.imageResolver = imageResolver;
    this.admissionService = admissionService;
    this.canonicalMapper = objectMapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RuntimeMcpProfileImportResult importDraft(
      final RuntimeMcpProfileImportRequest request
  ) {
    if (request == null || request.descriptor() == null || request.spec() == null) {
      throw new IllegalArgumentException(
          "MCP descriptor and Runtime Profile are required"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String code = identifier(request.code(), "Runtime Profile code");
    if (profileRepository.findActiveByCodeForUpdate(
        scope.scopeType(), scope.tenantId(), code
    ).isPresent()) {
      throw new IllegalArgumentException("Runtime Profile code already exists");
    }
    RuntimeMcpDescriptorImportRequest imported = request.descriptor();
    String registryName = text(
        imported.registryName(), "MCP descriptor name", 512
    );
    String version = text(
        imported.serverVersion(), "MCP descriptor version", 128
    );
    RuntimeMcpProfileSpec spec = request.spec();
    if (!registryName.equals(spec.descriptorName())
        || !version.equals(spec.descriptorVersion())) {
      throw new IllegalArgumentException(
          "Runtime Profile descriptor identity does not match imported descriptor"
      );
    }
    String descriptorJson = canonicalJson(
        imported.descriptorJson(), "MCP descriptor JSON"
    );
    String contentHash = sha256(descriptorJson);
    AiRuntimeMcpDescriptor descriptor = descriptorRepository
        .findActiveByIdentity(
            scope.scopeType(),
            scope.tenantId(),
            registryName,
            version,
            contentHash
        )
        .orElseGet(() -> descriptorRepository.save(newDescriptor(
            scope,
            imported,
            registryName,
            version,
            descriptorJson,
            contentHash
        )));
    String specJson = canonicalJson(spec);
    AiRuntimeMcpProfile profile = new AiRuntimeMcpProfile();
    profile.setScopeType(scope.scopeType());
    profile.setTenantId(scope.tenantId());
    profile.setDescriptorId(descriptor.getId());
    profile.setCode(code);
    profile.setName(text(request.name(), "Runtime Profile name", 128));
    profile.setStatus(RuntimeMcpProfileStatus.DRAFT);
    profile.setSpecJson(specJson);
    profile.setSpecHash(sha256(specJson));
    profile = profileRepository.save(profile);
    return new RuntimeMcpProfileImportResult(
        descriptor,
        decorate(profile)
    );
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiRuntimeMcpProfile> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    Page<AiRuntimeMcpProfile> result = profileRepository.findAllActiveByScope(
        scope.scopeType(), scope.tenantId(), pageable
    );
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimeMcpProfile> find(final String profileId) {
    Optional<AiRuntimeMcpProfile> result = profileRepository.findById(
        identifier(profileId, "Runtime Profile ID")
    ).filter(profile -> profile.getDeletedAt() == null);
    result.ifPresent(this::assertCanRead);
    return result.map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeMcpProfile update(
      final String profileId,
      final RuntimeMcpProfileUpdateRequest request
  ) {
    if (request == null || request.spec() == null) {
      throw new IllegalArgumentException("Runtime Profile specification is required");
    }
    AiRuntimeMcpProfile profile = requireManaged(profileId);
    if (profile.getStatus() == RuntimeMcpProfileStatus.ARCHIVED) {
      throw new IllegalArgumentException("Archived Runtime Profile cannot be edited");
    }
    final AiRuntimeMcpDescriptor descriptor = descriptorRepository.findById(
        profile.getDescriptorId()
    ).orElseThrow(() -> new IllegalStateException(
        "Runtime Profile descriptor does not exist"
    ));
    RuntimeMcpProfileSpec spec = request.spec();
    if (!descriptor.getRegistryName().equals(spec.descriptorName())
        || !descriptor.getServerVersion().equals(spec.descriptorVersion())) {
      throw new IllegalArgumentException(
          "Runtime Profile descriptor identity cannot be changed"
      );
    }
    String specJson = canonicalJson(spec);
    profile.setName(text(request.name(), "Runtime Profile name", 128));
    profile.setSpecJson(specJson);
    profile.setSpecHash(sha256(specJson));
    profile.setStatus(RuntimeMcpProfileStatus.DRAFT);
    return decorate(profileRepository.save(profile));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeMcpRevision publish(
      final String profileId,
      final RuntimeMcpProfilePublishRequest request
  ) {
    AiRuntimeMcpProfile profile = requireManaged(profileId);
    if (profile.getStatus() == RuntimeMcpProfileStatus.ARCHIVED) {
      throw new IllegalArgumentException("Archived Runtime Profile cannot be published");
    }
    final AiRuntimeMcpDescriptor descriptor = descriptorRepository.findById(
        profile.getDescriptorId()
    ).orElseThrow(() -> new IllegalStateException(
        "Runtime Profile descriptor does not exist"
    ));
    RuntimeMcpProfileSpec spec = readSpec(profile.getSpecJson());
    String imageReference = null;
    String imageDigest = null;
    String imageAdmissionPolicyHash = null;
    if (spec.artifact().type() == RuntimeMcpProfileSpec.ArtifactType.OCI) {
      imageReference = spec.artifact().reference();
      RuntimeImageResolution resolution = resolveImage(imageReference, request);
      imageDigest = resolution.imageDigest();
      imageAdmissionPolicyHash = resolution.admissionPolicyHash();
    } else if (request != null && request.imageDigest() != null) {
      throw new IllegalArgumentException(
          "Image digest is valid only for an OCI Runtime Profile"
      );
    }
    AiRuntimeMcpAdmissionService.AdmissionResult admission =
        admissionService.admit(
        profile,
        spec,
        imageReference,
        imageDigest,
        imageAdmissionPolicyHash
    );
    String admissionHash = admission.hash();
    String requestedAdmissionHash = optionalHash(
        request == null ? null : request.admissionReportHash()
    );
    if (requestedAdmissionHash != null
        && !requestedAdmissionHash.equals(admissionHash)) {
      throw new IllegalArgumentException(
          "MCP admission report hash does not match the expected hash"
      );
    }
    AiRuntimeMcpRevision revision = new AiRuntimeMcpRevision();
    revision.setScopeType(profile.getScopeType());
    revision.setTenantId(profile.getTenantId());
    revision.setProfileId(profile.getId());
    revision.setDescriptorId(descriptor.getId());
    revision.setRevisionNumber(
        revisionRepository.nextRevisionNumber(profile.getId())
    );
    revision.setDescriptorContentHash(descriptor.getContentHash());
    revision.setProfileJson(profile.getSpecJson());
    revision.setProfileHash(profile.getSpecHash());
    revision.setImageReference(imageReference);
    revision.setImageDigest(imageDigest);
    revision.setAdmissionReportHash(admissionHash);
    revision.setAdmissionReportJson(admission.reportJson());
    revision.setPublishedAt(Instant.now());
    revision = revisionRepository.save(revision);
    profile.setActiveRevisionId(revision.getId());
    profile.setStatus(RuntimeMcpProfileStatus.PUBLISHED);
    profileRepository.save(profile);
    poolRevisionService.activateAttached(profile.getId(), revision.getId());
    return decorate(revision);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AiRuntimeMcpRevision> findRevisions(final String profileId) {
    AiRuntimeMcpProfile profile = profileRepository.findById(
        identifier(profileId, "Runtime Profile ID")
    ).filter(value -> value.getDeletedAt() == null)
        .orElseThrow(() -> new IllegalArgumentException(
            "Runtime Profile does not exist"
        ));
    assertCanRead(profile);
    return revisionRepository.findActiveByProfile(profile.getId()).stream()
        .map(this::decorate)
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeMcpProfile activateRevision(
      final String profileId,
      final String revisionId
  ) {
    AiRuntimeMcpProfile profile = requireManaged(profileId);
    AiRuntimeMcpRevision revision = revisionRepository.findActiveByProfileAndId(
        profile.getId(), identifier(revisionId, "Runtime revision ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime revision does not exist"
    ));
    profile.setSpecJson(revision.getProfileJson());
    profile.setSpecHash(revision.getProfileHash());
    profile.setActiveRevisionId(revision.getId());
    profile.setStatus(RuntimeMcpProfileStatus.PUBLISHED);
    profile = profileRepository.save(profile);
    poolRevisionService.activateAttached(profile.getId(), revision.getId());
    return decorate(profile);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeDraft(final String profileId) {
    AiRuntimeMcpProfile profile = requireManaged(profileId);
    if (profile.getStatus() != RuntimeMcpProfileStatus.DRAFT
        || profile.getActiveRevisionId() != null
        || !revisionRepository.findActiveByProfile(profile.getId()).isEmpty()) {
      throw new IllegalArgumentException(
          "Only a never-published Runtime Profile draft can be deleted"
      );
    }
    profile.setDeletedAt(Instant.now());
    profileRepository.save(profile);
  }

  private AiRuntimeMcpDescriptor newDescriptor(
      final ScopeAssignment scope,
      final RuntimeMcpDescriptorImportRequest request,
      final String registryName,
      final String version,
      final String descriptorJson,
      final String contentHash
  ) {
    AiRuntimeMcpDescriptor descriptor = new AiRuntimeMcpDescriptor();
    descriptor.setScopeType(scope.scopeType());
    descriptor.setTenantId(scope.tenantId());
    descriptor.setSourceCatalogEntryId(optionalIdentifier(
        request.sourceCatalogEntryId(), "Catalog entry ID"
    ));
    descriptor.setRegistryName(registryName);
    descriptor.setServerVersion(version);
    descriptor.setTitle(optionalText(request.title(), 256));
    descriptor.setRepositoryUrl(optionalText(request.repositoryUrl(), 2048));
    descriptor.setContentHash(contentHash);
    descriptor.setDescriptorJson(descriptorJson);
    return descriptor;
  }

  private AiRuntimeMcpProfile requireManaged(final String profileId) {
    AiRuntimeMcpProfile profile = profileRepository.findActiveByIdForUpdate(
        identifier(profileId, "Runtime Profile ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime Profile does not exist"
    ));
    scopeAccessPolicy.assertCanManageOwnedResource(
        profile.getScopeType(), profile.getTenantId()
    );
    return profile;
  }

  private void assertCanRead(final AiRuntimeMcpProfile profile) {
    scopeAccessPolicy.assertCanReadManagedResource(
        profile.getScopeType(), profile.getTenantId()
    );
  }

  private AiRuntimeMcpProfile decorate(final AiRuntimeMcpProfile profile) {
    profile.setSpec(readSpec(profile.getSpecJson()));
    return profile;
  }

  private AiRuntimeMcpRevision decorate(final AiRuntimeMcpRevision revision) {
    revision.setProfileSpec(readSpec(revision.getProfileJson()));
    return revision;
  }

  private RuntimeMcpProfileSpec readSpec(final String json) {
    try {
      return canonicalMapper.readValue(json, RuntimeMcpProfileSpec.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored Runtime Profile JSON is invalid", ex);
    }
  }

  private String canonicalJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Runtime Profile cannot be serialized", ex);
    }
  }

  private String canonicalJson(final String value, final String label) {
    try {
      return canonicalMapper.writeValueAsString(canonicalMapper.readTree(
          text(value, label, 1024 * 1024)
      ));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(label + " is invalid", ex);
    }
  }

  private RuntimeImageResolution resolveImage(
      final String imageReference,
      final RuntimeMcpProfilePublishRequest request
  ) {
    int digestSeparator = imageReference.lastIndexOf("@sha256:");
    String embedded = digestSeparator < 0
        ? null : imageReference.substring(digestSeparator + 1);
    String requested = request == null || request.imageDigest() == null
        ? null : request.imageDigest().trim().toLowerCase();
    if (embedded != null && requested != null && !embedded.equals(requested)) {
      throw new IllegalArgumentException(
          "Published image digest does not match the artifact reference"
      );
    }
    if (embedded != null) {
      if (!OCI_DIGEST.matcher(embedded).matches()) {
        throw new IllegalArgumentException("OCI image digest is invalid");
      }
      return new RuntimeImageResolution(embedded, null);
    }
    RuntimeImageResolution resolution = imageResolver.resolve(imageReference);
    String resolved = resolution.imageDigest() == null
        ? null : resolution.imageDigest().trim().toLowerCase();
    if (resolved == null || !OCI_DIGEST.matcher(resolved).matches()) {
      throw new IllegalArgumentException(
          "OCI image resolver returned an invalid sha256 digest"
      );
    }
    if (requested != null && !resolved.equals(requested)) {
      throw new IllegalArgumentException(
          "Resolved image digest does not match the expected digest"
      );
    }
    return new RuntimeImageResolution(
        resolved,
        resolution.admissionPolicyHash()
    );
  }

  private String optionalHash(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase();
    if (normalized.startsWith("sha256:")) {
      normalized = normalized.substring("sha256:".length());
    }
    if (!SHA256.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Admission report hash is invalid");
    }
    return normalized;
  }

  private String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private String identifier(final String value, final String label) {
    String normalized = value == null ? "" : value.trim();
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return normalized;
  }

  private String optionalIdentifier(final String value, final String label) {
    return value == null || value.isBlank() ? null : identifier(value, label);
  }

  private String text(
      final String value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return normalized;
  }

  private String optionalText(final String value, final int maximumLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("Optional descriptor text is too long");
    }
    return normalized;
  }
}
