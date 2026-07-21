package org.simplepoint.plugin.ai.core.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiModelCatalogService;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ConnectionTestResult;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ModelSyncResult;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiProviderAdapterRegistry;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.stereotype.Service;

/**
 * Model catalog discovery and synchronization service.
 */
@Slf4j
@Service
public class AiModelCatalogServiceImpl implements AiModelCatalogService {

  private final AiProviderDefinitionRepository providerRepository;

  private final AiModelDefinitionRepository modelRepository;

  private final AiProviderAdapterRegistry adapterRegistry;

  private final AiCredentialCipher credentialCipher;

  private final AiProperties properties;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /**
   * Creates the model catalog service.
   *
   * @param providerRepository provider repository
   * @param modelRepository    model repository
   * @param adapterRegistry    adapter registry
   * @param credentialCipher   credential cipher
   * @param properties         AI integration properties
   * @param scopeAccessPolicy  AI resource scope policy
   */
  public AiModelCatalogServiceImpl(
      final AiProviderDefinitionRepository providerRepository,
      final AiModelDefinitionRepository modelRepository,
      final AiProviderAdapterRegistry adapterRegistry,
      final AiCredentialCipher credentialCipher,
      final AiProperties properties,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    this.providerRepository = providerRepository;
    this.modelRepository = modelRepository;
    this.adapterRegistry = adapterRegistry;
    this.credentialCipher = credentialCipher;
    this.properties = properties;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  /** {@inheritDoc} */
  @Override
  public ConnectionTestResult testConnection(final String providerId) {
    AiProviderDefinition provider = requireManagedProvider(providerId);
    Instant testedAt = Instant.now();
    try {
      List<DiscoveredModel> models = discover(provider);
      provider.setLastStatus("SUCCESS");
      provider.setLastMessage("连接成功，可用模型 " + models.size() + " 个");
      provider.setLastTestedAt(testedAt);
      providerRepository.save(provider);
      return new ConnectionTestResult(
          provider.getId(),
          true,
          models.size(),
          testedAt,
          provider.getLastMessage()
      );
    } catch (RuntimeException ex) {
      provider.setLastStatus("FAILED");
      provider.setLastMessage(truncate(ex.getMessage()));
      provider.setLastTestedAt(testedAt);
      providerRepository.save(provider);
      throw ex;
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<DiscoveredModel> discoverModels(final String providerId) {
    return discover(requireManagedProvider(providerId));
  }

  /** {@inheritDoc} */
  @Override
  public ModelSyncResult syncModels(final String providerId) {
    return syncModels(requireManagedProvider(providerId));
  }

  private ModelSyncResult syncModels(final AiProviderDefinition provider) {
    Instant syncedAt = Instant.now();
    try {
      List<DiscoveredModel> discovered = deduplicate(discover(provider));
      Map<String, AiModelDefinition> existing = modelRepository
          .findAllActiveByProviderId(provider.getId())
          .stream()
          .collect(Collectors.toMap(
              AiModelDefinition::getModelId,
              Function.identity(),
              (left, right) -> left,
              LinkedHashMap::new
          ));
      List<AiModelDefinition> changed = new ArrayList<>();
      int created = 0;
      int updated = 0;
      for (DiscoveredModel remote : discovered) {
        AiModelDefinition local = existing.get(remote.modelId());
        if (local == null) {
          changed.add(fromRemote(provider, remote));
          created++;
        } else {
          applyRemote(local, remote, provider);
          changed.add(local);
          updated++;
        }
      }
      Set<String> remoteIds = discovered.stream()
          .map(DiscoveredModel::modelId)
          .collect(Collectors.toSet());
      int unavailable = 0;
      for (AiModelDefinition local : existing.values()) {
        if (Boolean.TRUE.equals(local.getDiscovered()) && !remoteIds.contains(local.getModelId())) {
          if (!Boolean.FALSE.equals(local.getAvailable())) {
            unavailable++;
          }
          local.setAvailable(Boolean.FALSE);
          changed.add(local);
        }
      }
      if (!changed.isEmpty()) {
        modelRepository.saveAll(changed);
      }
      provider.setLastStatus("SYNCED");
      provider.setLastMessage("已同步模型 " + discovered.size() + " 个");
      provider.setLastSyncedAt(syncedAt);
      providerRepository.save(provider);
      return new ModelSyncResult(
          provider.getId(),
          discovered.size(),
          created,
          updated,
          unavailable,
          syncedAt
      );
    } catch (RuntimeException ex) {
      provider.setLastStatus("FAILED");
      provider.setLastMessage(truncate(ex.getMessage()));
      provider.setLastSyncedAt(syncedAt);
      providerRepository.save(provider);
      throw ex;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void syncAutoEnabledProviders() {
    for (AiProviderDefinition provider : providerRepository.findAllAutoSyncEnabled()) {
      try {
        syncModels(provider);
      } catch (RuntimeException ex) {
        log.warn("Automatic AI model sync failed for provider {}: {}", provider.getCode(), ex.getMessage());
      }
    }
  }

  private List<DiscoveredModel> discover(final AiProviderDefinition provider) {
    if (!Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalStateException("AI 供应商已禁用: " + provider.getCode());
    }
    AiProviderAdapter adapter = adapterRegistry.require(provider.getProviderType());
    return adapter.discoverModels(toConnection(provider));
  }

  private ProviderConnection toConnection(final AiProviderDefinition provider) {
    int timeout = properties.getRequestTimeoutSeconds() == null
        ? 30 : properties.getRequestTimeoutSeconds();
    return new ProviderConnection(
        provider.getId(),
        provider.getProviderType(),
        provider.getBaseUrl(),
        credentialCipher.decrypt(provider.getCredentialCiphertext()),
        provider.getOrganizationId(),
        provider.getProjectId(),
        provider.getApiVersion(),
        timeout
    );
  }

  private AiProviderDefinition requireProvider(final String providerId) {
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalArgumentException("供应商 ID 不能为空");
    }
    return providerRepository.findActiveById(providerId.trim())
        .orElseThrow(() -> new IllegalArgumentException("AI 供应商不存在: " + providerId));
  }

  private AiProviderDefinition requireManagedProvider(final String providerId) {
    AiProviderDefinition provider = requireProvider(providerId);
    scopeAccessPolicy.assertCanWriteResource(provider.getScopeType(), provider.getTenantId());
    return provider;
  }

  private static List<DiscoveredModel> deduplicate(final List<DiscoveredModel> models) {
    return new ArrayList<>(models.stream().collect(Collectors.toMap(
        DiscoveredModel::modelId,
        Function.identity(),
        (left, right) -> left,
        LinkedHashMap::new
    )).values());
  }

  private static AiModelDefinition fromRemote(
      final AiProviderDefinition provider,
      final DiscoveredModel remote
  ) {
    AiModelDefinition model = new AiModelDefinition();
    model.setProviderId(provider.getId());
    model.setScopeType(AiScopeAccessPolicy.effectiveScope(provider.getScopeType()));
    model.setTenantId(provider.getTenantId());
    model.setModelId(remote.modelId());
    model.setDisplayName(remote.displayName());
    model.setModelType(remote.modelType());
    model.setEnabled(Boolean.TRUE);
    model.setAvailable(Boolean.TRUE);
    model.setDiscovered(Boolean.TRUE);
    model.setTypeAutoDetected(Boolean.TRUE);
    model.setOwnedBy(remote.ownedBy());
    model.setReleasedAt(remote.releasedAt());
    model.setMetadataJson(remote.metadataJson());
    return model;
  }

  private static void applyRemote(
      final AiModelDefinition local,
      final DiscoveredModel remote,
      final AiProviderDefinition provider
  ) {
    local.setScopeType(AiScopeAccessPolicy.effectiveScope(provider.getScopeType()));
    local.setTenantId(provider.getTenantId());
    local.setDisplayName(remote.displayName());
    if (!Boolean.FALSE.equals(local.getTypeAutoDetected())) {
      local.setModelType(remote.modelType());
      local.setTypeAutoDetected(Boolean.TRUE);
    }
    local.setAvailable(Boolean.TRUE);
    local.setDiscovered(Boolean.TRUE);
    local.setOwnedBy(remote.ownedBy());
    local.setReleasedAt(remote.releasedAt());
    local.setMetadataJson(remote.metadataJson());
  }

  private static String truncate(final String message) {
    if (message == null || message.isBlank()) {
      return "未知错误";
    }
    return message.length() <= 1024 ? message : message.substring(0, 1024);
  }
}
