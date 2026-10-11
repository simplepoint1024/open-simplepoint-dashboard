package org.simplepoint.plugin.ai.core.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.repository.AiApiKeyRepository;
import org.simplepoint.plugin.ai.core.service.security.AiApiKeyHasher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.security.access.AccessDeniedException;

class AiApiKeyPagePermissionTest {
  AiApiKeyRepository repository = mock(AiApiKeyRepository.class);
  AiApiKeyHasher hasher = mock(AiApiKeyHasher.class);
  AiApiKeyServiceImpl service = new AiApiKeyServiceImpl(repository, mock(DetailsProviderService.class),
      mock(AiScopeAccessPolicy.class), hasher);

  @BeforeEach void scope() {
    var context = new AuthorizationContext(); context.setUserId("reader");
    context.setResourceDataScopes(Map.of("ai.api-keys.view", new ResourceDataScope("SELF", Set.of(), false)));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    ResourcePolicyContext.set("ai.api-keys.view");
  }
  @AfterEach void clear() {
    ResourcePolicyContext.clear(); RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }
  AiApiKey row(String id, String owner) {
    var row = new AiApiKey(); row.setId(id); row.setCreatedBy(owner); row.setEnabled(true);
    when(repository.findActiveById(id)).thenReturn(Optional.of(row)); return row;
  }
  @Test void foreignDetailAndRotationAreRejectedBeforeSecretGeneration() {
    var row = row("foreign", "other"); row.setSecretHash("original");
    assertThrows(AccessDeniedException.class, () -> service.findById("foreign"));
    assertThrows(AccessDeniedException.class, () -> service.rotate("foreign"));
    assertEquals("original", row.getSecretHash()); verifyNoInteractions(hasher);
    verify(repository, never()).save(any());
  }
  @Test void mixedBulkRevocationDoesNotRevokeEvenTheAccessibleKey() {
    var own = row("own", "reader"); row("foreign", "other");
    assertThrows(AccessDeniedException.class, () -> service.removeByIds(List.of("own", "foreign")));
    assertTrue(own.getEnabled()); assertNull(own.getRevokedAt());
    verify(repository, never()).save(any()); verify(repository, never()).deleteByIds(any());
  }
  @Test void ownKeyCanStillBeReadAndRotated() {
    var own = row("own", "reader");
    when(hasher.issue()).thenReturn(new AiApiKeyHasher.IssuedSecret("prefix", "raw", "hash"));
    when(repository.save(own)).thenReturn(own);
    assertEquals(own, service.findById("own").orElseThrow());
    var rotated = service.rotate("own");
    assertEquals("hash", rotated.getSecretHash()); assertEquals("raw", rotated.getIssuedKey());
    verify(repository).save(own);
  }
  @Test void rotationPreservesDisabledExpiryAndRateLimitPolicies() {
    var own = row("disabled", "reader"); own.setEnabled(false);
    var expires = java.time.Instant.parse("2025-01-01T00:00:00Z"); own.setExpiresAt(expires); own.setRateLimitPerMinute(17);
    when(hasher.issue()).thenReturn(new AiApiKeyHasher.IssuedSecret("new-prefix", "new-raw", "new-hash"));
    when(repository.save(own)).thenReturn(own);
    var rotated = service.rotate("disabled");
    assertFalse(rotated.getEnabled()); assertEquals(expires, rotated.getExpiresAt());
    assertEquals(17, rotated.getRateLimitPerMinute()); assertEquals("new-hash", rotated.getSecretHash());
    assertEquals("new-raw", rotated.getIssuedKey()); verify(repository).save(own);
  }
  @Test void revokedKeyCannotBeReactivatedByRotationBeforeSecretGeneration() {
    var revoked = row("revoked", "reader"); revoked.setRevokedAt(java.time.Instant.now());
    assertThrows(IllegalStateException.class, () -> service.rotate("revoked"));
    verifyNoInteractions(hasher); verify(repository, never()).save(any());
  }
  @Test void metadataOnlyEditRetainsReadonlyNameEnablementAndExpiryWithoutGeneratingAnySecret() {
    var context = AuthorizationContextHolder.getContext();
    context.setFieldPermissions(Map.of(AiApiKey.class.getName() + "#name", "VISIBLE",
        AiApiKey.class.getName() + "#enabled", "VISIBLE", AiApiKey.class.getName() + "#expiresAt", "VISIBLE"));
    var own = row("metadata", "reader"); own.setName("Original name"); own.setEnabled(false);
    var expires = java.time.Instant.parse("2025-01-01T00:00:00Z"); own.setExpiresAt(expires);
    when(repository.findById("metadata")).thenReturn(Optional.of(own)); when(repository.getDomainClass()).thenReturn(AiApiKey.class);
    var details = service.getDetailsProviderService();
    var generator = mock(org.simplepoint.api.security.generator.JsonSchemaGenerator.class);
    when(details.getDialect(org.simplepoint.api.security.service.JsonSchemaDetailsService.class))
        .thenReturn(mock(org.simplepoint.api.security.service.JsonSchemaDetailsService.class));
    when(details.getDialect(org.simplepoint.api.security.generator.JsonSchemaGenerator.class)).thenReturn(generator);
    var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); var properties = schema.putObject("properties");
    for (var field : List.of("name", "enabled", "expiresAt", "description", "rateLimitPerMinute")) properties.putObject(field);
    when(generator.generateSchema(AiApiKey.class)).thenReturn(schema);
    when(repository.updateById(any(AiApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var input = new AiApiKey(); input.setId("metadata"); input.setDescription("Actual metadata update");
    var result = service.modifyById(input);
    assertEquals("Original name", result.getName()); assertFalse(result.getEnabled()); assertEquals(expires, result.getExpiresAt());
    assertEquals("Actual metadata update", result.getDescription()); assertNull(result.getIssuedKey()); verifyNoInteractions(hasher);
  }
}
