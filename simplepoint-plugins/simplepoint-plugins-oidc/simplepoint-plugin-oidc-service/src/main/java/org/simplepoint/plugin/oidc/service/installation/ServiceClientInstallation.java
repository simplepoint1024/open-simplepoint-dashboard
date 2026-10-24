package org.simplepoint.plugin.oidc.service.installation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Privileged local provisioning of deployment-scoped clients; no browser or RPC export. */
@Service
public class ServiceClientInstallation {
  private final RegisteredClientRepository clients;
  private final PasswordEncoder passwords;

  /** Uses the OIDC-owned repository adapter and its normal secret encoding. */
  public ServiceClientInstallation(@org.springframework.beans.factory.annotation.Qualifier("registeredClientRepositoryImpl") RegisteredClientRepository clients, PasswordEncoder passwords) {
    this.clients = clients;
    this.passwords = passwords;
  }

  /** Exact retries retain clients; mismatching existing identities are never adopted or changed. */
  @Transactional(rollbackFor = Exception.class)
  public void provision(List<Credential> credentials) {
    validate(credentials);
    // Preflight the complete batch before creating any row.
    for (var credential : credentials) {
      var existing = clients.findByClientId(credential.clientId());
      if (existing != null) {
        requireMatching(credential, existing);
      }
    }
    for (var credential : credentials) {
      if (clients.findByClientId(credential.clientId()) == null) {
        clients.save(expected(credential).clientSecret(credential.secret()).clientIdIssuedAt(Instant.now()).build());
      }
    }
  }

  /** Read-only reconciliation verifies the entire committed configuration and supplied credential. */
  @Transactional(readOnly = true)
  public boolean installed(List<Credential> credentials) {
    validate(credentials);
    boolean complete = true;
    for (var credential : credentials) {
      var existing = clients.findByClientId(credential.clientId());
      if (existing == null) {
        complete = false;
      } else {
        requireMatching(credential, existing);
      }
    }
    return complete;
  }

  private void validate(List<Credential> credentials) {
    if (credentials == null || credentials.isEmpty() || credentials.size() > 256
        || credentials.stream().anyMatch(java.util.Objects::isNull)
        || credentials.stream().map(Credential::clientId).distinct().count() != credentials.size()
        || credentials.stream().map(Credential::module).distinct().count() != credentials.size()
        || credentials.stream().map(Credential::operation).distinct().count() != 1) {
      throw new IllegalArgumentException("Invalid service client installation");
    }
  }

  private RegisteredClient.Builder expected(Credential credential) {
    return RegisteredClient.withId(UUID.nameUUIDFromBytes(credential.clientId().getBytes(StandardCharsets.UTF_8)).toString())
        .clientId(credential.clientId()).clientName("module/" + credential.module() + "/" + credential.operation())
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope("service-router.invoke")
        .clientSettings(ClientSettings.builder().requireProofKey(false).requireAuthorizationConsent(false).build())
        .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build());
  }

  private void requireMatching(Credential credential, RegisteredClient actual) {
    var expected = expected(credential).build();
    if (!expected.getId().equals(actual.getId()) || !expected.getClientName().equals(actual.getClientName())
        || !expected.getClientAuthenticationMethods().equals(actual.getClientAuthenticationMethods())
        || !expected.getAuthorizationGrantTypes().equals(actual.getAuthorizationGrantTypes())
        || !expected.getScopes().equals(actual.getScopes())
        || !actual.getRedirectUris().isEmpty() || !actual.getPostLogoutRedirectUris().isEmpty()
        || actual.getClientSecretExpiresAt() != null
        || !expected.getClientSettings().getSettings().equals(actual.getClientSettings().getSettings())
        || !expected.getTokenSettings().getSettings().equals(actual.getTokenSettings().getSettings())
        || !passwords.matches(credential.secret(), actual.getClientSecret())) {
      throw new IllegalStateException("Service client installation conflicts with durable configuration");
    }
  }

  /** Secret-bearing local input; diagnostics deliberately exclude the secret. */
  public record Credential(UUID operation, String module, String clientId, String secret) {
    /** Requires a fresh deployment identity and high-entropy secret supplied by the installer. */
    public Credential {
      if (operation == null || module == null || !module.matches("[a-z][a-z0-9-]*")
          || !ModuleClientPlan.belongsToDeployment(clientId, operation)
          || secret == null || !secret.matches("[A-Za-z0-9_-]{43,128}")) {
        throw new IllegalArgumentException("Invalid service client credential");
      }
    }

    @Override
    public String toString() { return "Credential[operation=" + operation + ", module=" + module + ", secret=<redacted>]"; }
  }
}
