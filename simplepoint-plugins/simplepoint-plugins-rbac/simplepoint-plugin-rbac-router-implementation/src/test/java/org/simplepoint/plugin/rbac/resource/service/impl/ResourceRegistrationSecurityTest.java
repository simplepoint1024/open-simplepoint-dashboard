package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.oauth2.resourceserver.ServiceRouterLocalAuthentication;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.simplepoint.security.service.ResourceService;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Real signed credentials, local isolation, JSON dispatcher and provider policy; domain writes are
 * observed.
 */
class ResourceRegistrationSecurityTest {
  private static RSAKey key;
  private JwtDecoder decoder;
  private TokenRevocationService revocations;
  private JwtAuthenticationConverterDelegate converter;
  private ResourceService resources;

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
    resources = mock(ResourceService.class);
    doAnswer(
            invocation -> {
              legacyPolicy()
                  .requireConfiguredCaller(invocation.getArgument(0), invocation.getArgument(2));
              return null;
            })
        .when(resources)
        .syncRegistered(anyString(), any(), anyString());
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void legacyHostCannotDisableContractIsolationOrBorrowBrowserAuthority() throws Exception {
    final var callerThread = Thread.currentThread();
    var browser = new TestingAuthenticationToken("browser", null, "ROLE_Administrator");
    SecurityContextHolder.getContext().setAuthentication(browser);
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, "browser-policy");
    doAnswer(
            invocation -> {
              legacyPolicy()
                  .requireConfiguredCaller(invocation.getArgument(0), invocation.getArgument(2));
              assertThat(invocation.<String>getArgument(2)).isEqualTo("workflow-client");
              assertThat(Thread.currentThread()).isNotSameAs(callerThread);
              assertThat(
                      ServiceClientIdentity.authenticatedClientId(
                          SecurityContextHolder.getContext().getAuthentication()))
                  .contains("workflow-client");
              assertThat(
                      RequestContextHolder.getContext(
                          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, Object.class))
                  .isNull();
              return null;
            })
        .when(resources)
        .syncRegistered(anyString(), any(), anyString());
    var tokens = new AtomicReference<>(sign(claims("workflow-client"), key));
    runner(tokens, true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registration = context.getBean(ResourceRegistrationService.class);
              registration.sync("workflow", Set.of(declaration()));
              assertThatThrownBy(() -> registration.sync("common", Set.of()))
                  .isInstanceOfSatisfying(
                      ResourceRegistrationException.class,
                      exception ->
                          assertThat(exception.code())
                              .isEqualTo(ResourceRegistrationException.Code.ACCESS_DENIED));
              tokens.set(sign(claims("foreign-client"), key));
              assertThatThrownBy(() -> registration.sync("workflow", Set.of()))
                  .isInstanceOf(ResourceRegistrationException.class);
              assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(browser);
              assertThat(
                      (Object)
                          RequestContextHolder.getContext(
                              RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, Object.class))
                  .isEqualTo("browser-policy");
              verify(resources, times(1))
                  .syncRegistered(eq("workflow"), any(), eq("workflow-client"));
              assertThat(
                      context
                          .getBean(LocalServiceRegistry.class)
                          .find("security.resource", "4")
                          .orElseThrow()
                          .bean())
                  .isInstanceOf(ResourceRegistrationProvider.class);
            });
  }

  @Test
  void signatureGrantScopeAudienceAndRevocationFailuresNeverReachDomainWrites() throws Exception {
    when(revocations.isRevoked("revoked-token")).thenReturn(true);
    var invalid =
        List.of(
            sign(claims("workflow-client"), new RSAKeyGenerator(2048).generate()),
            sign(claims("workflow-client").claim("grant_type", "authorization_code"), key),
            sign(claims("workflow-client").claim("scope", "openid"), key),
            sign(claims("workflow-client").audience("other-api"), key),
            sign(claims("workflow-client").jwtID("revoked-token"), key));
    var tokens = new AtomicReference<>(invalid.getFirst());
    runner(tokens, true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              for (String token : invalid) {
                tokens.set(token);
                assertThatThrownBy(
                        () ->
                            context
                                .getBean(ResourceRegistrationService.class)
                                .sync("workflow", Set.of(declaration())))
                    .isInstanceOf(RuntimeException.class);
              }
              verifyNoInteractions(resources);
            });
  }

  @Test
  void missingLocalAuthenticationContributionFailsClosedEvenWithCallerServiceContext()
      throws Exception {
    String token = sign(claims("workflow-client"), key);
    SecurityContextHolder.getContext().setAuthentication(converter.convert(decoder.decode(token)));
    runner(new AtomicReference<>(token), false)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(ResourceRegistrationService.class)
                              .sync("workflow", Set.of(declaration())))
                  .isInstanceOf(ResourceRegistrationException.class);
              verifyNoInteractions(resources);
            });
  }

  @Test
  void inboundJsonUsesTheSameOwnerPolicyAndDoesNotExportLocalManagement() throws Exception {
    String token = sign(claims("workflow-client"), key);
    runner(new AtomicReference<>(token), true)
        .run(
            context -> {
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              SecurityContextHolder.getContext()
                  .setAuthentication(converter.convert(decoder.decode(token)));
              var allowed =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "sync",
                                  List.of("workflow", Set.of(declaration())),
                                  "allowed"))));
              assertThat(allowed.success()).isTrue();
              var denied =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "sync",
                                  List.of("common", Set.of(declaration())),
                                  "denied"))));
              assertThat(denied.errorCode()).isEqualTo("RESOURCE_REGISTRATION:ACCESS_DENIED");
              var management =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "security.resource",
                                  "4",
                                  "removeAll",
                                  List.of(),
                                  "management"))));
              assertThat(management.success()).isFalse();
              verify(resources, times(1))
                  .syncRegistered(eq("workflow"), any(), eq("workflow-client"));
            });
  }

  private static ResourceRegistrationCallerPolicy legacyPolicy() {
    var properties = new ResourceRegistrationProperties();
    properties.setOwnerClients(Map.of("workflow", "workflow-client"));
    return new ResourceRegistrationCallerPolicy(properties);
  }

  private ApplicationContextRunner runner(AtomicReference<String> tokens, boolean authenticated) {
    var properties = new ResourceRegistrationProperties();
    properties.setOwnerClients(Map.of("workflow", "workflow-client"));
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(Router.class)
            .withPropertyValues("simplepoint.service-router.consumer.local-isolation=false")
            .withBean(ResourceService.class, () -> resources)
            .withBean(
                ResourceRegistrationCallerPolicy.class,
                () -> new ResourceRegistrationCallerPolicy(properties))
            .withBean(ResourceRegistrationProvider.class);
    if (authenticated) {
      ServiceRouterAccessTokenProvider provider = tokens::get;
      runner =
          runner.withBean(
              ServiceRouterLocalAuthentication.class,
              () -> new ServiceRouterLocalAuthentication(provider, decoder, converter));
    }
    return runner;
  }

  private static ResourceDeclaration declaration() {
    var declaration = new ResourceDeclaration();
    declaration.setCode("workflow.view");
    return declaration;
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
  @EnableServiceRouter(basePackages = "org.simplepoint.security.service")
  static class Router {}
}
