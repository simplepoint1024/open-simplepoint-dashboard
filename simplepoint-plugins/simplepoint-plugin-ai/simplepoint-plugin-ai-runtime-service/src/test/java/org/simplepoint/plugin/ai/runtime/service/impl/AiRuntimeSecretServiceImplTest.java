package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeSecretRepository;

class AiRuntimeSecretServiceImplTest {

  private AiRuntimeSecretRepository repository;

  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiRuntimeSecretServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(AiRuntimeSecretRepository.class);
    scopeAccessPolicy = mock(AiScopeAccessPolicy.class);
    AiProperties properties = new AiProperties();
    properties.setCredentialEncryptionKey(
        "test-runtime-secret-encryption-key"
    );
    service = new AiRuntimeSecretServiceImpl(
        repository,
        scopeAccessPolicy,
        new AiCredentialCipher(properties),
        new ObjectMapper()
    );
    when(repository.save(any())).thenAnswer(invocation ->
        invocation.getArgument(0));
  }

  @Test
  void shouldEncryptValueAndOwnSecretInCurrentTenantScope() {
    when(scopeAccessPolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.TENANT, "tenant-a")
    );
    when(repository.findActiveByCodeAndScope(
        "github.token",
        AiResourceScope.TENANT,
        "tenant-a"
    )).thenReturn(Optional.empty());
    AiRuntimeSecret input = new AiRuntimeSecret();
    input.setCode(" GitHub.Token ");
    input.setName("GitHub token");
    input.setValue("plain-value");

    AiRuntimeSecret result = service.create(input);

    assertEquals(AiResourceScope.TENANT, result.getScopeType());
    assertEquals("tenant-a", result.getTenantId());
    assertEquals("github.token", result.getCode());
    assertNull(result.getValue());
    assertTrue(result.isHasValue());
    assertTrue(result.getValueCiphertext().startsWith("v1:"));
    assertFalse(result.getValueCiphertext().contains("plain-value"));
  }

  @Test
  void shouldResolveOnlySameScopeReferencesAtDispatchTime() {
    AiRuntimeSecret secret = storedSecret(
        "secret-a",
        AiResourceScope.SYSTEM,
        null,
        "github.token",
        "plain-value"
    );
    when(repository.findActiveById("secret-a"))
        .thenReturn(Optional.of(secret));

    String references = service.normalizeReferences(
        List.of("secret-a"),
        AiResourceScope.SYSTEM,
        null
    );
    var files = service.resolve(
        references,
        AiResourceScope.SYSTEM,
        null
    );

    assertEquals("[\"secret-a\"]", references);
    assertEquals(1, files.size());
    assertEquals("github.token", files.getFirst().name());
    assertEquals("plain-value", files.getFirst().value());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resolve(
            references,
            AiResourceScope.TENANT,
            "tenant-a"
        )
    );
  }

  @Test
  void shouldRejectDuplicateOrMalformedReferences() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.normalizeReferences(
            List.of("secret-a", "secret-a"),
            AiResourceScope.SYSTEM,
            null
        )
    );
    assertThrows(
        IllegalStateException.class,
        () -> service.references("[\"../secret\"]")
    );
    assertThrows(
        IllegalStateException.class,
        () -> service.references("[\"secret-a\",\"secret-a\"]")
    );
  }

  private AiRuntimeSecret storedSecret(
      final String id,
      final AiResourceScope scopeType,
      final String tenantId,
      final String code,
      final String value
  ) {
    AiProperties properties = new AiProperties();
    properties.setCredentialEncryptionKey(
        "test-runtime-secret-encryption-key"
    );
    AiRuntimeSecret secret = new AiRuntimeSecret();
    secret.setId(id);
    secret.setScopeType(scopeType);
    secret.setTenantId(tenantId);
    secret.setCode(code);
    secret.setName(code);
    secret.setEnabled(Boolean.TRUE);
    secret.setValueCiphertext(new AiCredentialCipher(properties).encrypt(value));
    return secret;
  }
}
