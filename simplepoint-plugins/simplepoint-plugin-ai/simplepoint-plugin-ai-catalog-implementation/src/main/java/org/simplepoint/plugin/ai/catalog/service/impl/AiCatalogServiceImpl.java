package org.simplepoint.plugin.ai.catalog.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogItem;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogPage;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogRuntimeImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncResult;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.model.McpServerDescriptor;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogEntryRepository;
import org.simplepoint.plugin.ai.catalog.api.repository.AiCatalogSyncStateRepository;
import org.simplepoint.plugin.ai.catalog.api.service.AiCatalogService;
import org.simplepoint.plugin.ai.catalog.service.registry.AiCatalogProperties;
import org.simplepoint.plugin.ai.catalog.service.registry.OfficialMcpRegistrySync;
import org.simplepoint.plugin.ai.catalog.service.support.OffsetPageRequest;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpTransportType;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpDescriptorImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportResult;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpProfileService;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mixed internal/external extension catalog.
 */
@Service
public class AiCatalogServiceImpl implements AiCatalogService {

  private static final int INTERNAL_LIMIT = 500;

  private final AiCatalogEntryRepository entryRepository;

  private final AiCatalogSyncStateRepository stateRepository;

  private final AiMcpServerDefinitionService mcpServerService;

  private final AiSkillService skillService;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiCatalogProperties properties;

  private final OfficialMcpRegistrySync registrySync;

  private final AiRuntimeMcpProfileService runtimeProfileService;

  private final ObjectMapper objectMapper;

  /**
   * Creates the catalog facade.
   */
  public AiCatalogServiceImpl(
      final AiCatalogEntryRepository entryRepository,
      final AiCatalogSyncStateRepository stateRepository,
      final AiMcpServerDefinitionService mcpServerService,
      final AiSkillService skillService,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiCatalogProperties properties,
      final OfficialMcpRegistrySync registrySync,
      final AiRuntimeMcpProfileService runtimeProfileService,
      final ObjectMapper objectMapper
  ) {
    this.entryRepository = entryRepository;
    this.stateRepository = stateRepository;
    this.mcpServerService = mcpServerService;
    this.skillService = skillService;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.properties = properties;
    this.registrySync = registrySync;
    this.runtimeProfileService = runtimeProfileService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public AiCatalogPage findAll(
      final String query,
      final CatalogSource source,
      final CatalogPackageKind kind,
      final int page,
      final int size
  ) {
    int normalizedPage = Math.max(0, page);
    int normalizedSize = Math.max(1, Math.min(size, 100));
    String keyword = normalize(query);
    List<AiCatalogItem> internalItems = new ArrayList<>();
    if (source == null || source == CatalogSource.INTERNAL) {
      if (kind == null || kind == CatalogPackageKind.MCP_SERVER) {
        mcpServerService.limit(
            Map.of(),
            PageRequest.of(0, INTERNAL_LIMIT)
        ).forEach(server -> internalItems.add(internalServer(server)));
      }
      if (kind == null || kind == CatalogPackageKind.SKILL) {
        skillService.findAll(PageRequest.of(0, INTERNAL_LIMIT))
            .forEach(skill -> internalItems.add(internalSkill(skill)));
      }
    }
    List<AiCatalogItem> filteredInternal = internalItems.stream()
        .filter(item -> matches(item, keyword))
        .sorted(Comparator
            .comparing(
                (AiCatalogItem item) -> value(item.title()),
                String.CASE_INSENSITIVE_ORDER
            )
            .thenComparing(AiCatalogItem::id))
        .toList();
    long offset = (long) normalizedPage * normalizedSize;
    List<AiCatalogItem> result = new ArrayList<>();
    int internalFrom = (int) Math.min(offset, filteredInternal.size());
    int internalTo = Math.min(
        internalFrom + normalizedSize,
        filteredInternal.size()
    );
    if (internalFrom < internalTo) {
      result.addAll(filteredInternal.subList(internalFrom, internalTo));
    }
    boolean includeOfficial = (source == null
        || source == CatalogSource.OFFICIAL_MCP)
        && (kind == null || kind == CatalogPackageKind.MCP_SERVER);
    long officialTotal = 0;
    if (includeOfficial) {
      int remaining = normalizedSize - result.size();
      long officialOffset = Math.max(0, offset - filteredInternal.size());
      Page<AiCatalogEntry> officialPage =
          entryRepository.findAllActiveBySource(
              CatalogSource.OFFICIAL_MCP,
              CatalogEntryStatus.ACTIVE,
              keyword == null ? "" : "%" + keyword + "%",
              new OffsetPageRequest(
                  remaining > 0 ? officialOffset : 0,
                  Math.max(1, remaining)
              )
          );
      officialTotal = officialPage.getTotalElements();
      if (remaining > 0) {
        officialPage.stream()
            .map(this::officialEntry)
            .forEach(result::add);
      }
    }
    long total = filteredInternal.size() + officialTotal;
    int totalPages = total == 0
        ? 0 : Math.toIntExact((total + normalizedSize - 1) / normalizedSize);
    return new AiCatalogPage(
        List.copyOf(result),
        normalizedPage,
        normalizedSize,
        total,
        totalPages
    );
  }

  @Override
  @Transactional(readOnly = true)
  public AiCatalogSyncState getOfficialSyncState() {
    return stateRepository.findBySource(CatalogSource.OFFICIAL_MCP)
        .orElseGet(AiCatalogServiceImpl::neverSyncedState);
  }

  @Override
  public AiCatalogSyncResult syncOfficialRegistry() {
    if (scopeAccessPolicy.currentManagementScope().scopeType()
        != AiResourceScope.SYSTEM) {
      throw new IllegalArgumentException(
          "Official MCP Registry synchronization is platform-only"
      );
    }
    requireSyncEnabled();
    return registrySync.synchronize();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiMcpServerDefinition importOfficialMcpServer(
      final String entryId,
      final AiCatalogImportRequest request
  ) {
    AiCatalogEntry entry = entryRepository.findActiveById(requireText(entryId))
        .orElseThrow(() -> new IllegalArgumentException(
            "Catalog entry does not exist"
        ));
    if (entry.getSource() != CatalogSource.OFFICIAL_MCP
        || entry.getKind() != CatalogPackageKind.MCP_SERVER
        || entry.getStatus() != CatalogEntryStatus.ACTIVE
        || !isImportable(entry.getEndpointUrl(), entry.getTransportType())) {
      throw new IllegalArgumentException(
          "Catalog entry is not an importable Streamable HTTP MCP Server"
      );
    }
    AiMcpServerDefinition server = new AiMcpServerDefinition();
    server.setCode(importCode(entry, request == null ? null : request.code()));
    server.setName(truncate(first(
        request == null ? null : request.name(),
        first(entry.getTitle(), entry.getRegistryName())
    ), 128));
    server.setDescription(truncate(entry.getDescription(), 512));
    server.setDeploymentType(McpServerDeploymentType.REMOTE);
    server.setTransportType(McpTransportType.STREAMABLE_HTTP);
    server.setEndpointUrl(entry.getEndpointUrl());
    server.setAuthenticationType(McpAuthenticationType.NONE);
    server.setAllowPrivateNetwork(false);
    server.setEnabled(true);
    return mcpServerService.create(server);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RuntimeMcpProfileImportResult importOfficialMcpPackage(
      final String entryId,
      final AiCatalogRuntimeImportRequest request
  ) {
    AiCatalogEntry entry = entryRepository.findActiveById(requireText(entryId))
        .orElseThrow(() -> new IllegalArgumentException(
            "Catalog entry does not exist"
        ));
    if (entry.getSource() != CatalogSource.OFFICIAL_MCP
        || entry.getKind() != CatalogPackageKind.MCP_SERVER
        || entry.getStatus() != CatalogEntryStatus.ACTIVE) {
      throw new IllegalArgumentException(
          "Catalog entry is not an active official MCP Server"
      );
    }
    McpServerDescriptor descriptor = readDescriptor(entry);
    int packageIndex = selectPackage(descriptor, request);
    McpServerDescriptor.Package selected = descriptor.packages().get(packageIndex);
    RuntimeMcpProfileSpec.ArtifactType artifactType = artifactType(selected);
    RuntimeMcpProfileSpec spec = new RuntimeMcpProfileSpec(
        descriptor.name(),
        descriptor.version(),
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            artifactType,
            artifactReference(selected, artifactType)
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO,
            null,
            null
        ),
        new RuntimeMcpProfileSpec.Process(
            List.of(),
            List.of(),
            List.of(),
            null
        ),
        List.of(),
        List.of(),
        List.of(),
        new RuntimeMcpProfileSpec.NetworkPolicy(
            RuntimeMcpProfileSpec.NetworkMode.NONE,
            List.of()
        ),
        new RuntimeMcpProfileSpec.SessionPolicy(
            RuntimeMcpProfileSpec.SessionMode.DEDICATED,
            1
        ),
        new RuntimeMcpProfileSpec.SandboxPolicy(
            RuntimeMcpProfileSpec.SandboxProfile.STRICT,
            Map.of()
        )
    );
    RuntimeMcpDescriptorImportRequest importedDescriptor =
        new RuntimeMcpDescriptorImportRequest(
            entry.getId(),
            descriptor.name(),
            descriptor.version(),
            first(descriptor.title(), entry.getTitle()),
            descriptor.repository() == null
                ? entry.getRepositoryUrl() : descriptor.repository().url(),
            descriptor.rawJson()
        );
    return runtimeProfileService.importDraft(new RuntimeMcpProfileImportRequest(
        importCode(entry, request == null ? null : request.code()),
        truncate(first(
            request == null ? null : request.name(),
            first(descriptor.title(), descriptor.name())
        ), 128),
        importedDescriptor,
        spec
    ));
  }

  private McpServerDescriptor readDescriptor(final AiCatalogEntry entry) {
    if (entry.getDescriptorJson() == null
        || entry.getDescriptorJson().isBlank()) {
      throw new IllegalStateException(
          "Catalog entry predates normalized descriptors; synchronize the "
              + "official MCP Registry again"
      );
    }
    try {
      return objectMapper.readValue(
          entry.getDescriptorJson(),
          McpServerDescriptor.class
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Catalog entry contains an invalid normalized MCP descriptor",
          ex
      );
    }
  }

  private static int selectPackage(
      final McpServerDescriptor descriptor,
      final AiCatalogRuntimeImportRequest request
  ) {
    if (request != null && request.packageIndex() != null) {
      int requested = request.packageIndex();
      if (requested < 0 || requested >= descriptor.packages().size()
          || !isSupportedPackage(descriptor.packages().get(requested))) {
        throw new IllegalArgumentException(
            "Selected MCP package is not a supported stdio distribution"
        );
      }
      return requested;
    }
    for (int index = 0; index < descriptor.packages().size(); index++) {
      if (isSupportedPackage(descriptor.packages().get(index))) {
        return index;
      }
    }
    throw new IllegalArgumentException(
        "MCP descriptor has no supported OCI, npm or PyPI stdio package"
    );
  }

  private static boolean isSupportedPackage(
      final McpServerDescriptor.Package selected
  ) {
    String registry = selected.registryType().toLowerCase(Locale.ROOT);
    return "stdio".equalsIgnoreCase(selected.transport().type())
        && ("oci".equals(registry)
            || "npm".equals(registry)
            || "pypi".equals(registry));
  }

  private static RuntimeMcpProfileSpec.ArtifactType artifactType(
      final McpServerDescriptor.Package selected
  ) {
    return switch (selected.registryType().toLowerCase(Locale.ROOT)) {
      case "oci" -> RuntimeMcpProfileSpec.ArtifactType.OCI;
      case "npm" -> RuntimeMcpProfileSpec.ArtifactType.NPM;
      case "pypi" -> RuntimeMcpProfileSpec.ArtifactType.PYPI;
      default -> throw new IllegalArgumentException(
          "Unsupported MCP package registry: " + selected.registryType()
      );
    };
  }

  private static String artifactReference(
      final McpServerDescriptor.Package selected,
      final RuntimeMcpProfileSpec.ArtifactType artifactType
  ) {
    String version = first(selected.version(), null);
    if (version == null || artifactType == RuntimeMcpProfileSpec.ArtifactType.OCI) {
      return selected.identifier();
    }
    if (artifactType == RuntimeMcpProfileSpec.ArtifactType.NPM) {
      return selected.identifier() + "@" + version;
    }
    return selected.identifier() + "==" + version;
  }

  private AiCatalogItem internalServer(final AiMcpServerDefinition server) {
    return new AiCatalogItem(
        "internal:mcp:" + server.getId(),
        CatalogSource.INTERNAL,
        CatalogPackageKind.MCP_SERVER,
        null,
        server.getCode(),
        server.getRemoteServerVersion(),
        server.getName(),
        server.getDescription(),
        server.getStatus() == null ? null : server.getStatus().name(),
        server.getScopeType(),
        server.getEndpointUrl(),
        null,
        null,
        false,
        server.getUpdatedAt()
    );
  }

  private AiCatalogItem internalSkill(final AiSkillDefinition skill) {
    AiSkillVersion activeVersion = skill.getActiveVersionId() == null
        ? null : skillService.findVersion(skill.getId(), skill.getActiveVersionId())
            .orElse(null);
    return new AiCatalogItem(
        "internal:skill:" + skill.getId(),
        CatalogSource.INTERNAL,
        CatalogPackageKind.SKILL,
        null,
        skill.getCode(),
        activeVersion == null ? null : activeVersion.getVersion(),
        skill.getName(),
        skill.getDescription(),
        skill.getStatus() == null ? null : skill.getStatus().name(),
        skill.getScopeType(),
        null,
        activeVersion == null ? null : activeVersion.getArtifactReference(),
        null,
        false,
        skill.getUpdatedAt()
    );
  }

  private AiCatalogItem officialEntry(final AiCatalogEntry entry) {
    return new AiCatalogItem(
        entry.getId(),
        entry.getSource(),
        entry.getKind(),
        entry.getRegistryName(),
        null,
        entry.getVersion(),
        entry.getTitle(),
        entry.getDescription(),
        entry.getStatus().name(),
        null,
        entry.getEndpointUrl(),
        entry.getRepositoryUrl(),
        entry.getWebsiteUrl(),
        isImportable(entry.getEndpointUrl(), entry.getTransportType())
            || hasImportablePackage(entry),
        firstInstant(entry.getRegistryUpdatedAt(), entry.getUpdatedAt())
    );
  }

  private static boolean matches(
      final AiCatalogItem item,
      final String keyword
  ) {
    if (keyword == null) {
      return true;
    }
    return List.of(
        value(item.title()),
        value(item.description()),
        value(item.registryName()),
        value(item.code())
    ).stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(keyword));
  }

  private void requireSyncEnabled() {
    if (!Boolean.TRUE.equals(properties.getOfficialSyncEnabled())) {
      throw new IllegalStateException(
          "Official MCP Registry synchronization is disabled"
      );
    }
  }

  private static AiCatalogSyncState neverSyncedState() {
    AiCatalogSyncState state = new AiCatalogSyncState();
    state.setSource(CatalogSource.OFFICIAL_MCP);
    state.setSyncMode(CatalogSyncMode.FULL);
    state.setStatus(CatalogSyncStatus.NEVER);
    state.setSyncedCount(0L);
    return state;
  }

  private static boolean isImportable(
      final String endpoint,
      final String transport
  ) {
    if (!"streamable-http".equalsIgnoreCase(transport)
        || endpoint == null) {
      return false;
    }
    try {
      URI uri = URI.create(endpoint);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getRawUserInfo() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private boolean hasImportablePackage(final AiCatalogEntry entry) {
    if (entry.getDescriptorJson() == null) {
      return false;
    }
    try {
      return objectMapper.readValue(
          entry.getDescriptorJson(), McpServerDescriptor.class
      ).packages().stream().anyMatch(AiCatalogServiceImpl::isSupportedPackage);
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      return false;
    }
  }

  private static String importCode(
      final AiCatalogEntry entry,
      final String requestedCode
  ) {
    if (requestedCode != null && !requestedCode.isBlank()) {
      return requestedCode.trim().toLowerCase(Locale.ROOT);
    }
    return "registry-" + entry.getExternalKey().substring(0, 16);
  }

  private static String requireText(final String value) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException("Catalog entry ID must not be blank");
    }
    return normalized;
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.isEmpty() ? null : normalized;
  }

  private static String first(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String truncate(final String value, final int maximum) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.length() <= maximum
        ? normalized : normalized.substring(0, maximum);
  }

  private static String value(final String value) {
    return value == null ? "" : value;
  }

  private static Instant firstInstant(
      final Instant value,
      final Instant fallback
  ) {
    return value == null ? fallback : value;
  }

  /**
   * Creates a stable non-secret identity for one Registry name and version.
   */
  public static String externalKey(final String name, final String version) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(
          (name + "\u0000" + version).getBytes(StandardCharsets.UTF_8)
      ));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
