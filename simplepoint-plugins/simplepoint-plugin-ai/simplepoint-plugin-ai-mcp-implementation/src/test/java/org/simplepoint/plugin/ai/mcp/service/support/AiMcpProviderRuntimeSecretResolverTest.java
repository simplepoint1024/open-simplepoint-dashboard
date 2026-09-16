package org.simplepoint.plugin.ai.mcp.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthStatus;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpProviderConnectionRepository;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;

class AiMcpProviderRuntimeSecretResolverTest {

  @Test
  void resolvesOneSubjectTokenOnlyAtDispatch() {
    final AiMcpProviderConnectionRepository repository = mock(
        AiMcpProviderConnectionRepository.class
    );
    final AiCredentialCipher cipher = mock(AiCredentialCipher.class);
    AiMcpProviderConnection connection = new AiMcpProviderConnection();
    connection.setServerId("github-user-1");
    connection.setScopeType(AiResourceScope.TENANT);
    connection.setTenantId("tenant-1");
    connection.setUserId("user-1");
    connection.setStatus(McpOauthStatus.CONNECTED);
    connection.setAccessTokenCiphertext("encrypted");
    when(repository.findActiveByServer("github-user-1"))
        .thenReturn(List.of(connection));
    when(cipher.decrypt("encrypted")).thenReturn("github-token");
    AiMcpProviderRuntimeSecretResolver resolver =
        new AiMcpProviderRuntimeSecretResolver(repository, cipher);

    var secrets = resolver.resolve(
        "github-user-1",
        AiResourceScope.TENANT,
        "tenant-1",
        List.of(new RuntimeMcpProfileSpec.SecretBinding(
            "github-oauth",
            "provider://github/access-token",
            RuntimeMcpProfileSpec.SecretTarget.ENV_AT_EXEC,
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        ))
    );

    assertThat(secrets).singleElement().satisfies(secret -> {
      assertThat(secret.value()).isEqualTo("github-token");
      assertThat(secret.targetEnvironment())
          .isEqualTo("GITHUB_PERSONAL_ACCESS_TOKEN");
    });
  }

  @Test
  void rejectsSharedManagedServerAcrossSubjects() {
    AiMcpProviderConnectionRepository repository = mock(
        AiMcpProviderConnectionRepository.class
    );
    AiMcpProviderConnection first = connected("user-1");
    AiMcpProviderConnection second = connected("user-2");
    when(repository.findActiveByServer("github-shared"))
        .thenReturn(List.of(first, second));
    var resolver = new AiMcpProviderRuntimeSecretResolver(
        repository, mock(AiCredentialCipher.class)
    );

    assertThatThrownBy(() -> resolver.resolve(
        "github-shared",
        AiResourceScope.SYSTEM,
        null,
        List.of(new RuntimeMcpProfileSpec.SecretBinding(
            "github-oauth",
            "provider://github/access-token",
            RuntimeMcpProfileSpec.SecretTarget.ENV_AT_EXEC,
            "GITHUB_PERSONAL_ACCESS_TOKEN"
        ))
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one connected subject");
  }

  private static AiMcpProviderConnection connected(final String userId) {
    AiMcpProviderConnection connection = new AiMcpProviderConnection();
    connection.setScopeType(AiResourceScope.SYSTEM);
    connection.setUserId(userId);
    connection.setStatus(McpOauthStatus.CONNECTED);
    connection.setAccessTokenCiphertext("encrypted-" + userId);
    return connection;
  }
}
