package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.service.repository.impl.RegisteredClientRepositoryImpl;
import org.simplepoint.plugin.oidc.service.service.impl.OidcAuthorizationConsentServiceImpl;
import org.simplepoint.plugin.oidc.service.service.impl.OidcAuthorizationServiceImpl;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.authentication.TotpService;
import org.simplepoint.plugin.rbac.authentication.mfa.TwoFactorSetupService;
import org.simplepoint.plugin.rbac.authentication.mfa.TwoFactorSetupServiceImpl;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.simplepoint.security.entity.User;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises real authentication adapters with module-owned stores and a misleading primary. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class AuthenticationStoragePostgresTest {
  private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualAdaptersUseTheirOwnersForReadsWritesAndRollbacks(boolean split) throws Exception {
    try (var identity = new MigrationPostgresDatabase();
        var oidc = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      Map<String, Object> properties =
          split
              ? Map.of(
                  "simplepoint.persistence.modules.identity", "identity",
                  "simplepoint.persistence.sources.identity", "identitySource",
                  "simplepoint.persistence.modules.oidc", "oidc",
                  "simplepoint.persistence.sources.oidc", "oidcSource")
              : Map.of();
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("stores", properties));
      var modules =
          ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("identity", "oidc"));
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var migrations = ModuleMigrationPlan.discover(modules, persistence.bindings());
      new ModuleMigrationEngine()
          .migrate(
              migrations,
              split
                  ? Map.of("identity", identity.source(), "oidc", oidc.source())
                  : Map.of("default", identity.source()));
      context.registerBean(
          split ? "identitySource" : "dataSource", DataSource.class, identity::source);
      if (split) {
        context.registerBean("oidcSource", DataSource.class, oidc::source);
      }
      var foreignSource = mock(DataSource.class);
      var foreignTransactions = mock(PlatformTransactionManager.class);
      context.registerBean(
          "foreignSource",
          DataSource.class,
          () -> foreignSource,
          definition -> definition.setPrimary(true));
      context.registerBean(
          "transactionManager",
          PlatformTransactionManager.class,
          () -> foreignTransactions,
          definition -> definition.setPrimary(true));
      final var passwords = new BCryptPasswordEncoder(4);
      final var cipher = cipher();
      var totp = mock(TotpService.class);
      var protection = mock(LoginProtection.class);
      when(totp.generateSecret()).thenReturn("JBSWY3DPEHPK3PXP");
      when(totp.matchingStep(anyString(), anyString(), any())).thenReturn(123L);
      when(protection.consumeTotp(anyString(), anyString(), anyLong())).thenReturn(true);
      context.registerBean(ModulePlan.class, () -> modules);
      context.registerBean(PasswordEncoder.class, () -> passwords);
      context.registerBean(AuthenticationSecretCipher.class, () -> cipher);
      context.registerBean(TotpService.class, () -> totp);
      context.registerBean(LoginProtection.class, () -> protection);
      context.register(
          Persistence.class,
          RegisteredClientRepositoryImpl.class,
          OidcAuthorizationServiceImpl.class,
          OidcAuthorizationConsentServiceImpl.class,
          TwoFactorSetupServiceImpl.class);
      context.refresh();
      var oidcBinding = persistence.bindings().require("oidc");
      var identityBinding = persistence.bindings().require("identity");
      var oidcTx =
          new TransactionTemplate(
              context.getBean(oidcBinding.transactionManager(), PlatformTransactionManager.class));
      var identityTx =
          new TransactionTemplate(
              context.getBean(
                  identityBinding.transactionManager(), PlatformTransactionManager.class));
      var users = context.getBean(identityBinding.entityManager(), EntityManager.class);
      verifyOauth(
          context,
          passwords,
          oidcTx,
          context.getBean(oidcBinding.entityManager(), EntityManager.class));
      verifyEnrollment(context, users, identityTx, oidcTx, split, cipher, passwords);
      var tokenStore = split ? oidc.jdbc() : identity.jdbc();
      assertEquals(
          3L,
          tokenStore.queryForObject("select count(*) from simpoint_ac_oauth2_client", Long.class));
      assertEquals(
          0L,
          tokenStore.queryForObject(
              "select count(*) from simpoint_ac_oauth2_authorization", Long.class));
      assertEquals(
          0L,
          tokenStore.queryForObject("select count(*) from simpoint_ac_oauth2_consent", Long.class));
      if (split) {
        assertNull(
            identity
                .jdbc()
                .queryForObject(
                    "select to_regclass('simpoint_ac_oauth2_client')::text", String.class));
        assertNull(
            oidc.jdbc()
                .queryForObject("select to_regclass('simpoint_ac_users')::text", String.class));
      }
      verifyNoInteractions(foreignSource, foreignTransactions);
    }
  }

  private void verifyOauth(
      AnnotationConfigApplicationContext context,
      PasswordEncoder passwords,
      TransactionTemplate tx,
      EntityManager entities) {
    var clients = context.getBean(RegisteredClientRepository.class);
    final var authorizations = context.getBean(OAuth2AuthorizationService.class);
    final var consents = context.getBean(OAuth2AuthorizationConsentService.class);
    String encoded = passwords.encode("fixture-secret");
    clients.save(client("first", encoded));
    assertNotNull(clients.findById("first"));
    clients.save(client("second", encoded));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            clients.save(
                RegisteredClient.from(client("first", encoded))
                    .clientId("conflicting-client")
                    .build()));
    assertEquals("first", clients.findById("first").getClientId());
    tx.executeWithoutResult(
        status -> {
          var original = entities.find(Client.class, "first");
          original.setCreatedAt(ISSUED);
          original.setCreatedBy("original-creator");
          original.setCreateOrgDeptId("original-department");
        });
    var publicClient =
        RegisteredClient.withId("public")
            .clientId("public")
            .clientName("Public PKCE client")
            .clientIdIssuedAt(ISSUED)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://example.invalid/callback")
            .scope("openid")
            .clientSettings(ClientSettings.builder().requireProofKey(true).build())
            .build();
    clients.save(publicClient);
    clients.save(RegisteredClient.from(publicClient).clientSecret(encoded).build());
    assertNotNull(clients.findByClientId("public").getClientSecret());
    clients.save(publicClient);
    assertNull(clients.findByClientId("public").getClientSecret());
    assertTrue(clients.findByClientId("public").getClientSettings().isRequireProofKey());
    var first = clients.findByClientId("first");
    assertNotNull(first);
    assertTrue(passwords.matches("fixture-secret", first.getClientSecret()));
    var authorization =
        OAuth2Authorization.withRegisteredClient(first)
            .id("authorization")
            .principalName("alice")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("openid"))
            .accessToken(
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "test-access-token",
                    ISSUED,
                    ISSUED.plusSeconds(300),
                    Set.of("openid")))
            .refreshToken(
                new OAuth2RefreshToken("test-refresh-token", ISSUED, ISSUED.plusSeconds(3600)))
            .build();
    authorizations.save(authorization);
    var consent = OAuth2AuthorizationConsent.withId(first.getId(), "alice").scope("openid").build();
    consents.save(consent);
    clients.save(
        RegisteredClient.from(first)
            .id("restart-generated-id")
            .clientName("Updated client")
            .build());
    assertNull(clients.findById("restart-generated-id"));
    var preserved = entities.find(Client.class, "first");
    assertEquals(ISSUED, preserved.getCreatedAt());
    assertEquals("original-creator", preserved.getCreatedBy());
    assertEquals("original-department", preserved.getCreateOrgDeptId());
    assertEquals(first.getId(), clients.findByClientId("first").getId());
    assertEquals("Updated client", clients.findById(first.getId()).getClientName());
    assertEquals(
        authorization.getId(),
        authorizations.findByToken("test-access-token", OAuth2TokenType.ACCESS_TOKEN).getId());
    assertEquals(
        authorization.getId(),
        authorizations.findByToken("test-refresh-token", OAuth2TokenType.REFRESH_TOKEN).getId());
    assertEquals(
        authorization.getId(), authorizations.findByToken("test-refresh-token", null).getId());
    assertEquals(Set.of("openid"), consents.findById(first.getId(), "alice").getScopes());
    consents.save(OAuth2AuthorizationConsent.from(consent).scope("profile").build());
    assertEquals(
        Set.of("openid", "profile"), consents.findById(first.getId(), "alice").getScopes());
    tx.executeWithoutResult(
        status -> {
          clients.save(client("rolled-back", encoded));
          consents.remove(consent);
          authorizations.remove(authorization);
          status.setRollbackOnly();
        });
    assertNull(clients.findByClientId("rolled-back"));
    assertNotNull(authorizations.findById(authorization.getId()));
    assertNotNull(consents.findById(first.getId(), "alice"));
    // Calls intentionally have no caller transaction: the actual adapters own it.
    consents.remove(consent);
    authorizations.remove(authorization);
    assertNull(consents.findById(first.getId(), "alice"));
    assertNull(authorizations.findByToken("test-access-token", OAuth2TokenType.ACCESS_TOKEN));
  }

  private void verifyEnrollment(
      AnnotationConfigApplicationContext context,
      EntityManager users,
      TransactionTemplate identityTx,
      TransactionTemplate oidcTx,
      boolean split,
      AuthenticationSecretCipher cipher,
      PasswordEncoder passwords) {
    var principal = new User();
    principal.setId("alice");
    principal.setUsername("alice");
    principal.setPassword(passwords.encode("fixture-password"));
    identityTx.executeWithoutResult(status -> users.persist(principal));
    var enrollment = context.getBean(TwoFactorSetupService.class);
    identityTx.executeWithoutResult(
        status -> {
          enrollment.enable(principal);
          status.setRollbackOnly();
        });
    assertNull(users.find(User.class, "alice").getTwoFactorSecret());
    // Separate stores commit independently; a shared store participates in the same transaction.
    oidcTx.executeWithoutResult(
        status -> {
          enrollment.enable(principal);
          status.setRollbackOnly();
        });
    assertEquals(split, users.find(User.class, "alice").getTwoFactorSecret() != null);
    var pending = enrollment.enable(principal);
    var persisted = users.find(User.class, "alice");
    assertNotEquals(pending.secret(), persisted.getTwoFactorSecret());
    assertEquals(pending.secret(), cipher.decrypt("alice", persisted.getTwoFactorSecret()));
    assertFalse(Boolean.TRUE.equals(persisted.getTwoFactorEnabled()));
    assertTrue(enrollment.confirm(principal, "123456"));
    assertTrue(users.find(User.class, "alice").getTwoFactorEnabled());
    assertEquals(1L, users.find(User.class, "alice").getAuthorizationVersion());
    assertThrows(
        BadCredentialsException.class,
        () -> enrollment.disable(principal, "wrong-password", "123456"));
    assertTrue(users.find(User.class, "alice").getTwoFactorEnabled());
    enrollment.disable(principal, "fixture-password", "123456");
    assertNull(users.find(User.class, "alice").getTwoFactorSecret());
    assertEquals(2L, users.find(User.class, "alice").getAuthorizationVersion());
  }

  private static RegisteredClient client(String id, String secret) {
    return RegisteredClient.withId(id)
        .clientId(id)
        .clientName(id)
        .clientIdIssuedAt(ISSUED)
        .clientSecret(secret)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope("openid")
        .build();
  }

  private AuthenticationSecretCipher cipher() throws Exception {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    Path keyFile = temporary.resolve("key-ring.json");
    Files.writeString(
        keyFile,
        "{\"activeKeyId\":\"test\",\"keys\":{\"test\":\""
            + Base64.getEncoder().encodeToString(key)
            + "\"}}");
    return new AuthenticationSecretCipher(keyFile.toString());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
