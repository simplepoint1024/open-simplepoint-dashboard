package org.simplepoint.plugin.ai.mcp.service.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpProviderConnectionRepository;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSubjectSecretResolver;
import org.springframework.stereotype.Service;

/** Bridges one subject-owned OAuth token into a dedicated managed workload. */
@Service
public class AiMcpProviderRuntimeSecretResolver
    implements AiRuntimeSubjectSecretResolver {

  private final AiMcpProviderConnectionRepository repository;

  private final AiCredentialCipher credentialCipher;

  /** Creates the dispatch-only provider token resolver. */
  public AiMcpProviderRuntimeSecretResolver(
      final AiMcpProviderConnectionRepository repository,
      final AiCredentialCipher credentialCipher
  ) {
    this.repository = repository;
    this.credentialCipher = credentialCipher;
  }

  @Override
  public List<RuntimeSecretFile> resolve(
      final String serverId,
      final AiResourceScope scopeType,
      final String tenantId,
      final List<RuntimeMcpProfileSpec.SecretBinding> bindings
  ) {
    if (bindings == null || bindings.stream().noneMatch(binding ->
        binding.secretReference().startsWith("provider://"))) {
      return List.of();
    }
    List<AiMcpProviderConnection> candidates = repository
        .findActiveByServer(serverId).stream()
        .filter(AiMcpProviderConnection::isConnected)
        .filter(connection -> connection.getScopeType() == scopeType)
        .filter(connection -> Objects.equals(
            connection.getTenantId(), tenantId
        ))
        .toList();
    if (candidates.size() != 1) {
      throw new IllegalStateException(
          "Managed OAuth MCP server must have exactly one connected subject"
      );
    }
    String token = credentialCipher.decrypt(
        candidates.getFirst().getAccessTokenCiphertext()
    );
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("Managed OAuth access token is missing");
    }
    List<RuntimeSecretFile> result = new ArrayList<>();
    for (int index = 0; index < bindings.size(); index++) {
      RuntimeMcpProfileSpec.SecretBinding binding = bindings.get(index);
      if (!binding.secretReference().startsWith("provider://")) {
        continue;
      }
      String targetPath = binding.target()
          == RuntimeMcpProfileSpec.SecretTarget.FILE
          ? binding.targetName()
          : "/run/secrets/simplepoint/.env/provider-binding-" + index;
      String targetEnvironment = binding.target()
          == RuntimeMcpProfileSpec.SecretTarget.ENV_AT_EXEC
          ? binding.targetName() : null;
      result.add(new RuntimeSecretFile(
          "provider-binding-" + index,
          token,
          targetPath,
          targetEnvironment
      ));
    }
    return List.copyOf(result);
  }
}
