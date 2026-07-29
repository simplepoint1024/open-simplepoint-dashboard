package org.simplepoint.plugin.ai.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeSecretRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware encrypted secret store and short-lived Runtime broker.
 */
@Service
public class AiRuntimeSecretServiceImpl implements AiRuntimeSecretService {

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern CODE =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private static final TypeReference<List<String>> STRING_LIST =
      new TypeReference<>() {
      };

  private static final int MAXIMUM_SECRET_BYTES = 16 * 1024;

  private static final int MAXIMUM_SECRET_REFERENCES = 16;

  private final AiRuntimeSecretRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiCredentialCipher credentialCipher;

  private final ObjectMapper objectMapper;

  /**
   * Creates the encrypted Runtime secret service.
   */
  public AiRuntimeSecretServiceImpl(
      final AiRuntimeSecretRepository repository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiCredentialCipher credentialCipher,
      final ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.credentialCipher = credentialCipher;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiRuntimeSecret> findAll(final Pageable pageable) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    Page<AiRuntimeSecret> result = repository.findAllActiveByScope(
        scope.scopeType(),
        scope.tenantId(),
        pageable
    );
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiRuntimeSecret> find(final String id) {
    Optional<AiRuntimeSecret> result =
        repository.findActiveById(requireIdentifier(id, "Runtime secret ID"));
    result.ifPresent(secret -> scopeAccessPolicy.assertCanReadManagedResource(
        secret.getScopeType(),
        secret.getTenantId()
    ));
    return result.map(this::decorate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeSecret create(final AiRuntimeSecret secret) {
    if (secret == null) {
      throw new IllegalArgumentException("Runtime secret is required");
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    String code = normalizeCode(secret.getCode());
    repository.findActiveByCodeAndScope(
        code,
        scope.scopeType(),
        scope.tenantId()
    ).ifPresent(existing -> {
      throw new IllegalArgumentException(
          "Runtime secret code already exists: " + code
      );
    });
    String value = requireValue(secret.getValue());
    secret.setId(null);
    secret.setScopeType(scope.scopeType());
    secret.setTenantId(scope.tenantId());
    secret.setCode(code);
    secret.setName(required(secret.getName(), "Runtime secret name", 128));
    secret.setDescription(optional(secret.getDescription(), 512));
    secret.setValueCiphertext(credentialCipher.encrypt(value));
    secret.setValue(null);
    secret.setEnabled(secret.getEnabled() == null
        ? Boolean.TRUE : secret.getEnabled());
    secret.setRotatedAt(Instant.now());
    return decorate(repository.save(secret));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiRuntimeSecret rotate(final String id, final String value) {
    AiRuntimeSecret secret = repository.findActiveById(
        requireIdentifier(id, "Runtime secret ID")
    ).orElseThrow(() -> new IllegalArgumentException(
        "Runtime secret does not exist"
    ));
    scopeAccessPolicy.assertCanManageOwnedResource(
        secret.getScopeType(),
        secret.getTenantId()
    );
    secret.setValueCiphertext(credentialCipher.encrypt(requireValue(value)));
    secret.setRotatedAt(Instant.now());
    return decorate(repository.save(secret));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    List<String> normalized = ids.stream()
        .map(id -> requireIdentifier(id, "Runtime secret ID"))
        .distinct()
        .toList();
    for (String id : normalized) {
      AiRuntimeSecret secret = repository.findActiveById(id)
          .orElseThrow(() -> new IllegalArgumentException(
              "Runtime secret does not exist"
          ));
      scopeAccessPolicy.assertCanManageOwnedResource(
          secret.getScopeType(),
          secret.getTenantId()
      );
    }
    repository.deleteByIds(normalized);
  }

  @Override
  @Transactional(readOnly = true)
  public String normalizeReferences(
      final List<String> secretIds,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    if (secretIds == null || secretIds.isEmpty()) {
      return "[]";
    }
    if (secretIds.size() > MAXIMUM_SECRET_REFERENCES) {
      throw new IllegalArgumentException(
          "Runtime workload has too many secret references"
      );
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    secretIds.forEach(id ->
        unique.add(requireIdentifier(id, "Runtime secret ID")));
    if (unique.size() != secretIds.size()) {
      throw new IllegalArgumentException(
          "Runtime secret references must be unique"
      );
    }
    List<String> normalized = unique.stream().sorted().toList();
    for (String id : normalized) {
      requireUsableSecret(id, scopeType, tenantId);
    }
    return encode(normalized);
  }

  @Override
  public List<String> references(final String referencesJson) {
    String value = referencesJson == null || referencesJson.isBlank()
        ? "[]" : referencesJson;
    try {
      List<String> decoded = objectMapper.readValue(value, STRING_LIST);
      if (decoded == null || decoded.size() > MAXIMUM_SECRET_REFERENCES) {
        throw new IllegalStateException(
            "Runtime workload secret references are invalid"
        );
      }
      List<String> normalized = decoded.stream()
          .map(id -> requireIdentifier(id, "Runtime secret ID"))
          .distinct()
          .sorted()
          .toList();
      if (normalized.size() != decoded.size()) {
        throw new IllegalStateException(
            "Runtime workload secret references are invalid"
        );
      }
      return normalized;
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Runtime workload secret references are invalid",
          ex
      );
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<RuntimeSecretFile> resolve(
      final String referencesJson,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    List<RuntimeSecretFile> result = new ArrayList<>();
    for (String id : references(referencesJson)) {
      AiRuntimeSecret secret = requireUsableSecret(id, scopeType, tenantId);
      result.add(new RuntimeSecretFile(
          secret.getCode(),
          credentialCipher.decrypt(secret.getValueCiphertext())
      ));
    }
    return List.copyOf(result);
  }

  private AiRuntimeSecret requireUsableSecret(
      final String id,
      final AiResourceScope scopeType,
      final String tenantId
  ) {
    AiRuntimeSecret secret = repository.findActiveById(id)
        .orElseThrow(() -> new IllegalArgumentException(
            "Runtime secret does not exist"
        ));
    if (secret.getScopeType() != scopeType
        || !Objects.equals(secret.getTenantId(), tenantId)) {
      throw new IllegalArgumentException(
          "Runtime secret belongs to a different scope"
      );
    }
    if (!Boolean.TRUE.equals(secret.getEnabled())
        || secret.getValueCiphertext() == null
        || secret.getValueCiphertext().isBlank()) {
      throw new IllegalArgumentException("Runtime secret is not available");
    }
    return secret;
  }

  private AiRuntimeSecret decorate(final AiRuntimeSecret secret) {
    secret.setHasValue(
        secret.getValueCiphertext() != null
            && !secret.getValueCiphertext().isBlank()
    );
    secret.setValue(null);
    return secret;
  }

  private String encode(final List<String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Runtime secret references cannot be encoded",
          ex
      );
    }
  }

  private String requireValue(final String value) {
    if (value == null
        || value.isEmpty()
        || value.getBytes(StandardCharsets.UTF_8).length
        > MAXIMUM_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "Runtime secret value must contain 1 to 16384 UTF-8 bytes"
      );
    }
    return value;
  }

  private String normalizeCode(final String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase();
    if (!CODE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Runtime secret code is invalid");
    }
    return normalized;
  }

  private String requireIdentifier(final String value, final String field) {
    String normalized = value == null ? "" : value.trim();
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private String required(
      final String value,
      final String field,
      final int maximumLength
  ) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximumLength) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private String optional(final String value, final int maximumLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("Runtime secret description is invalid");
    }
    return normalized;
  }
}
