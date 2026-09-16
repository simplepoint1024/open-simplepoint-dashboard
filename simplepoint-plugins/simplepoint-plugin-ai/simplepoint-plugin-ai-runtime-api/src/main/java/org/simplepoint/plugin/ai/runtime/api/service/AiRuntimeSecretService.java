package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Management and dispatch-time broker for encrypted Runtime secrets.
 */
public interface AiRuntimeSecretService {

  /**
   * Pages secret metadata in the current authorization scope.
   */
  Page<AiRuntimeSecret> findAll(Pageable pageable);

  /**
   * Finds secret metadata without exposing plaintext.
   */
  Optional<AiRuntimeSecret> find(String id);

  /**
   * Encrypts and creates a new secret reference.
   */
  AiRuntimeSecret create(AiRuntimeSecret secret);

  /**
   * Replaces encrypted secret material and advances its version.
   */
  AiRuntimeSecret rotate(String id, String value);

  /**
   * Soft-deletes secret references in the current scope.
   */
  void remove(Collection<String> ids);

  /**
   * Validates and serializes workload secret references.
   */
  String normalizeReferences(
      List<String> secretIds,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Decodes persisted reference identifiers without resolving plaintext.
   */
  List<String> references(String referencesJson);

  /**
   * Resolves approved references only for immediate runtime dispatch.
   */
  List<RuntimeSecretFile> resolve(
      String referencesJson,
      AiResourceScope scopeType,
      String tenantId
  );

  /** Resolves Profile FILE and ENV_AT_EXEC bindings for one dispatch only. */
  List<RuntimeSecretFile> resolveBindings(
      List<RuntimeMcpProfileSpec.SecretBinding> bindings,
      AiResourceScope scopeType,
      String tenantId
  );
}
