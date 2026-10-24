package org.simplepoint.security.oauth2.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.IsolatedLocalInvoker;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.oauth2.resourceserver.config.ServiceRouterLocalSecurityAutoConfiguration;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class ServiceRouterLocalAuthenticationTest {
  private static RSAKey key;
  private JwtDecoder decoder;
  private TokenRevocationService revocations;
  private JwtAuthenticationConverterDelegate converter;

  @BeforeAll
  static void createKey() throws Exception {
    key = new RSAKeyGenerator(2048).generate();
  }

  @BeforeEach
  void setup() throws Exception {
    var jwtDecoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.example"));
    decoder = jwtDecoder;
    revocations = mock(TokenRevocationService.class);
    converter =
        new JwtAuthenticationConverterDelegate(claims -> List.of(), revocations, "simplepoint-api");
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void installsVerifiedServiceIdentityAndRestoresPreviousContext() throws Exception {
    var previous = SecurityContextHolder.createEmptyContext();
    previous.setAuthentication(
        new TestingAuthenticationToken("browser", null, "ROLE_Administrator"));
    SecurityContextHolder.setContext(previous);
    var boundary = boundary(sign(claims()), new AtomicInteger());
    try (var ignored = boundary.open()) {
      assertThat(
              ServiceClientIdentity.authenticatedClientId(
                  SecurityContextHolder.getContext().getAuthentication()))
          .contains("app");
      assertThat(SecurityContextHolder.getContext()).isNotSameAs(previous);
    }
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    assertThat(previous.getAuthentication().getName()).isEqualTo("browser");
  }

  @Test
  void rejectsUserTokensMissingScopeMissingClientAndMissingCredential() throws Exception {
    for (var claims :
        List.of(
            claims().claim("grant_type", "authorization_code"),
            claims().claim("scope", "openid"),
            claims().claim("client_id", ""))) {
      assertThatThrownBy(() -> boundary(sign(claims), new AtomicInteger()).open())
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    assertThatThrownBy(() -> boundary("", new AtomicInteger()).open())
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void rejectsWrongAudienceIssuerExpirySignatureAndRevocation() throws Exception {
    var invalid =
        List.of(
            sign(claims().audience("different-api")),
            sign(claims().issuer("https://untrusted.example")),
            sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(120)))),
            sign(claims(), new RSAKeyGenerator(2048).generate()),
            sign(claims().jwtID("revoked")));
    when(revocations.isRevoked("revoked")).thenReturn(true);
    for (String token : invalid) {
      var refreshed = new AtomicInteger();
      assertThatThrownBy(() -> boundary(token, refreshed).open())
          .isInstanceOf(RuntimeException.class);
      assertThat(refreshed.get()).isEqualTo(1);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
  }

  @Test
  void refreshesRejectedCredentialBeforeOpeningScope() throws Exception {
    String expired = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(120))));
    String fresh = sign(claims());
    var current = new AtomicReference<>(expired);
    var invalidations = new AtomicInteger();
    var tokens =
        new ServiceRouterAccessTokenProvider() {
          @Override
          public String getAccessToken() {
            return current.get();
          }

          @Override
          public void invalidate(String rejected) {
            assertThat(rejected).isEqualTo(expired);
            invalidations.incrementAndGet();
            current.set(fresh);
          }
        };
    try (var ignored = new ServiceRouterLocalAuthentication(tokens, decoder, converter).open()) {
      assertThat(
              ServiceClientIdentity.authenticatedClientId(
                  SecurityContextHolder.getContext().getAuthentication()))
          .contains("app");
    }
    assertThat(invalidations.get()).isEqualTo(1);
  }

  @Test
  void autoConfiguredLocalProxyUsesServiceTokenAndCannotSeeBrowserOrAuthorizationContext()
      throws Exception {
    String token = sign(claims());
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("browser", null, "ROLE_Administrator"));
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, "browser-policy");
    runner(token)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(IsolatedLocalInvoker.class)
                  .hasSingleBean(ServiceRouterLocalAuthentication.class);
              var result = context.getBean(Contract.class).identity();
              assertThat(result).isEqualTo("app");
              assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                  .isEqualTo("browser");
              assertThat(
                      RequestContextHolder.getContext(
                          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, String.class))
                  .isEqualTo("browser-policy");
            });
  }

  @Test
  void platformIsolationCannotUseSharedTokenOrSilentlyOmitVerification() throws Exception {
    String token = sign(claims());
    runner(token)
        .withPropertyValues("simplepoint.service-router.internal-auth.mode=shared-token")
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner runner(String token) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withConfiguration(AutoConfigurations.of(ServiceRouterLocalSecurityAutoConfiguration.class))
        .withPropertyValues(
            "simplepoint.service-router.consumer.local-isolation=true",
            "simplepoint.service-router.internal-auth.mode=oauth2")
        .withBean(ServiceRouterAccessTokenProvider.class, () -> () -> token)
        .withBean(JwtDecoder.class, () -> decoder)
        .withBean(TokenRevocationService.class, () -> revocations)
        .withBean(AuthorizationGrantedAuthorityLoader.class, () -> claims -> List.of())
        .withBean(Provider.class);
  }

  private ServiceRouterLocalAuthentication boundary(String token, AtomicInteger invalidations) {
    var tokens =
        new ServiceRouterAccessTokenProvider() {
          @Override
          public String getAccessToken() {
            return token;
          }

          @Override
          public void invalidate(String rejected) {
            invalidations.incrementAndGet();
          }
        };
    return new ServiceRouterLocalAuthentication(tokens, decoder, converter);
  }

  private JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer("https://issuer.example")
        .subject("app")
        .audience("simplepoint-api")
        .claim("client_id", "app")
        .claim("grant_type", "client_credentials")
        .claim("scope", "service-router.invoke")
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plusSeconds(120)));
  }

  private String sign(JWTClaimsSet.Builder claims) throws Exception {
    return sign(claims, key);
  }

  private String sign(JWTClaimsSet.Builder claims, RSAKey signingKey) throws Exception {
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  @RemoteContract(name = "security.local-test")
  public interface Contract {
    String identity();
  }

  @RemoteProvider
  public static class Provider implements Contract {
    @Override
    public String identity() {
      assertThat(
              RequestContextHolder.getContext(
                  RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, String.class))
          .isNull();
      return ServiceClientIdentity.authenticatedClientId(
              SecurityContextHolder.getContext().getAuthentication())
          .orElseThrow();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.security.oauth2.resourceserver")
  static class Router {}
}
