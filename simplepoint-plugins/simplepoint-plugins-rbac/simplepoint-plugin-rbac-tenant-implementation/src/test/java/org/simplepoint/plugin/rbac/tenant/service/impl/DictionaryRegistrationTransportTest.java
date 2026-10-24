package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationReceipt;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationService;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationProvider;
import org.simplepoint.security.oauth2.resourceserver.ServiceRouterLocalAuthentication;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Real signed tokens, isolation, JSON dispatcher, database application and durable retries. */
class DictionaryRegistrationTransportTest extends DictionaryPersistenceTestSupport {
  private static RSAKey key;

  @BeforeAll
  static void key() throws Exception {
    key = new RSAKeyGenerator(2048).generate();
  }

  @Test
  void localIsolationAndJsonDispatchShareTheSameDurableCompletionAndAuthorization()
      throws Exception {
    var declaration = declaration("Transport", "ok");
    UUID operation = UUID.randomUUID();
    String client = ModuleClientPlan.deploymentClientId("module-test", operation);
    registrations.install(
        List.of(new DictionaryInstallation(operation, 0, "test", client, declaration)));
    var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.example"));
    var revocations = mock(TokenRevocationService.class);
    when(revocations.isRevoked("revoked")).thenReturn(true);
    var converter =
        new JwtAuthenticationConverterDelegate(claims -> List.of(), revocations, "simplepoint-api");
    final var tokens = new AtomicReference<>(sign(claims(client), key));
    var browser = new TestingAuthenticationToken(client, null, "ROLE_Administrator");
    SecurityContextHolder.getContext().setAuthentication(browser);
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, "browser-context");
    assertThrows(DictionaryRegistrationException.class, () -> provider.apply(declaration));
    try {
      new ApplicationContextRunner()
          .withUserConfiguration(Router.class)
          .withPropertyValues("simplepoint.service-router.consumer.local-isolation=false")
          .withBean(DictionaryRegistrationProvider.class, () -> provider)
          .withBean(
              ServiceRouterLocalAuthentication.class,
              () ->
                  new ServiceRouterLocalAuthentication(
                      (ServiceRouterAccessTokenProvider) tokens::get, decoder, converter))
          .run(
              context -> {
                org.junit.jupiter.api.Assertions.assertNull(context.getStartupFailure());
                var routed = context.getBean(DictionaryRegistrationService.class);
                var local = routed.apply(declaration);
                assertEquals(operation, local.operationId());
                assertEquals(1, dictionaryCount());
                assertSame(browser, SecurityContextHolder.getContext().getAuthentication());
                assertEquals(
                    "browser-context",
                    RequestContextHolder.getContext(
                        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, Object.class));
                SecurityContextHolder.getContext()
                    .setAuthentication(converter.convert(decoder.decode(tokens.get())));
                var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
                var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
                var response =
                    codec.decodeResponse(
                        dispatcher.dispatchJson(
                            codec.encode(
                                new RemoteRequest(
                                    "security.dictionary-registration",
                                    "1",
                                    "apply",
                                    List.of(declaration),
                                    "dictionary-remote"))));
                assertTrue(response.success());
                var remote =
                    context
                        .getBean(ObjectMapper.class)
                        .convertValue(response.data(), DictionaryRegistrationReceipt.class);
                assertEquals(local, remote);
                var denied =
                    codec.decodeResponse(
                        dispatcher.dispatchJson(
                            codec.encode(
                                new RemoteRequest(
                                    "security.dictionary-registration",
                                    "1",
                                    "install",
                                    List.of(),
                                    "no-administration"))));
                assertFalse(denied.success());
                SecurityContextHolder.getContext().setAuthentication(browser);
                for (String invalid :
                    List.of(
                        sign(claims(client), new RSAKeyGenerator(2048).generate()),
                        sign(claims(client).claim("grant_type", "authorization_code"), key),
                        sign(claims(client).claim("scope", "openid"), key),
                        sign(claims(client).audience("other-api"), key),
                        sign(claims(client).jwtID("revoked"), key),
                        sign(claims("foreign-client"), key))) {
                  tokens.set(invalid);
                  assertThrows(RuntimeException.class, () -> routed.apply(declaration));
                  assertSame(browser, SecurityContextHolder.getContext().getAuthentication());
                }
                assertEquals(1, dictionaryCount());
              });
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private static JWTClaimsSet.Builder claims(String client) {
    return new JWTClaimsSet.Builder()
        .issuer("https://issuer.example")
        .subject(client)
        .audience("simplepoint-api")
        .claim("client_id", client)
        .claim("grant_type", "client_credentials")
        .claim("scope", "service-router.invoke")
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(120)));
  }

  private static String sign(JWTClaimsSet.Builder claims, RSAKey signingKey) throws Exception {
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.platform.dictionary.registration")
  static class Router {}
}
