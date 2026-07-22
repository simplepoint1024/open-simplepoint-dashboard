package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import java.util.function.Supplier;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Authenticates public model API requests and installs their isolated invocation context. */
public interface AiGatewayAccessService {

  /** Authenticates a raw public key and creates its immutable gateway session. */
  GatewaySession authenticate(String rawApiKey, String remoteAddress);

  /** Lists generation models visible to the gateway session. */
  List<GatewayModel> availableModels(GatewaySession session);

  /** Resolves a public model identifier to its internal definition identifier. */
  String resolveModelDefinitionId(GatewaySession session, String externalModelId);

  /** Runs model preparation inside an isolated authorization context for the key. */
  <T> T withSession(GatewaySession session, Supplier<T> operation);

  /** Authenticated API key identity and ownership scope. */
  record GatewaySession(
      String apiKeyId,
      String apiKeyName,
      AiResourceScope scopeType,
      String tenantId
  ) {
  }

  /** Public model catalog entry with the internal model definition it routes to. */
  record GatewayModel(
      String id,
      String modelDefinitionId,
      String displayName,
      long createdAtEpochSeconds
  ) {
  }
}
