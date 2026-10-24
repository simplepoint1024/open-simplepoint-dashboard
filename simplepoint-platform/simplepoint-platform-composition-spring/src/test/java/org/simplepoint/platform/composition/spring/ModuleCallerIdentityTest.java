package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.simplepoint.composition.fixture.api.CallerIdentity;
import org.simplepoint.composition.fixture.callerone.CallerOneModule;
import org.simplepoint.composition.fixture.callertwo.CallerTwoModule;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.security.oauth2.resourceserver.ServiceRouterLocalAuthentication;
import org.simplepoint.security.oauth2.resourceserver.config.ServiceRouterLocalSecurityAutoConfiguration;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModuleCallerIdentityTest {
  private static RSAKey key;
  private static JwtDecoder decoder;
  private static String one;
  private static String two;

  @BeforeAll
  static void tokens() throws Exception {
    key = new RSAKeyGenerator(2048).generate();
    var jwtDecoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://issuer.example"));
    decoder = jwtDecoder;
    one = token("caller-one");
    two = token("caller-two");
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void bindsConstructorFieldLazyAndDeferredClientsWithoutBusinessAop() {
    runner()
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ServiceRouterLocalAuthentication.class);
              var consumer = context.getBean(CallerOneModule.Consumer.class);
              assertThat(consumer.field()).isEqualTo("caller-one");
              assertThat(consumer.lazy()).isEqualTo("caller-one");
              assertThat(consumer.deferred()).isEqualTo("caller-one");
              assertThat(context.getBean(CallerTwoModule.Consumer.class).identity().caller())
                  .isEqualTo("caller-two");
              assertThatThrownBy(
                      () -> context.getBean(ModuleServiceCredentials.class).getAccessToken())
                  .isInstanceOf(ModuleCompositionException.class)
                  .hasMessageContaining("UNBOUND_MODULE_CALLER");
              assertThatThrownBy(() -> context.getBean(CallerIdentity.class))
                  .isInstanceOf(RuntimeException.class);
            });
  }

  @Test
  void nestedCallUsesTheCalleeModulesClientAndRestoresIncomingIdentity() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("browser", null, "ROLE_Administrator"));
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CallerOneModule.Consumer.class).chain())
                  .containsExactly("caller-one", "caller-two", "caller-one");
              assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                  .isEqualTo("browser");
            });
  }

  @Test
  void concurrentBackgroundCallsKeepTheirInjectedModuleAndDoNotLeakThreadState() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var first = context.getBean(CallerOneModule.Consumer.class);
              var second = context.getBean(CallerTwoModule.Consumer.class);
              try (var executor = Executors.newFixedThreadPool(4)) {
                var tasks =
                    IntStream.range(0, 32)
                        .mapToObj(
                            index ->
                                (Callable<Boolean>)
                                    () -> {
                                      boolean correct =
                                          (index % 2 == 0
                                                  ? first.deferred()
                                                  : second.identity().caller())
                                              .equals(index % 2 == 0 ? "caller-one" : "caller-two");
                                      return correct
                                          && SecurityContextHolder.getContext().getAuthentication()
                                              == null;
                                    })
                        .toList();
                for (var result : executor.invokeAll(tasks)) {
                  assertThat(result.get()).isTrue();
                }
              }
            });
  }

  @Test
  void remoteTransportReceivesTheSameModuleCredentialBinding() {
    var credentials = credentials();
    runner(credentials)
        .withPropertyValues("simplepoint.service-router.consumer.local-first=false")
        .withBean(
            ServiceDiscovery.class,
            () ->
                capability ->
                    List.of(
                        new ServiceRoute(
                            "remote", "instance", URI.create("http://unused"), Map.of())))
        .withBean(
            RemoteInvoker.class,
            () ->
                (route, request) ->
                    RemoteResponse.success(
                        decoder.decode(credentials.getAccessToken()).getClaimAsString("client_id")))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CallerOneModule.Consumer.class).field())
                  .isEqualTo("caller-one");
              assertThat(context.getBean(CallerTwoModule.Consumer.class).identity().caller())
                  .isEqualTo("caller-two");
              assertThatThrownBy(credentials::getAccessToken)
                  .isInstanceOf(ModuleCompositionException.class);
            });
  }

  @Test
  void unknownOwnersAndUndeclaredConsumersCannotBorrowAnAvailableModuleCredential() {
    runner()
        .withUserConfiguration(Unowned.class)
        .run(context -> assertFailure(context.getStartupFailure(), "UNOWNED_MODULE_CLIENT"));
    runner()
        .withPropertyValues("simplepoint.fixture.undeclared-consumer=true")
        .run(context -> assertFailure(context.getStartupFailure(), "UNDECLARED_MODULE_CONSUMER"));
  }

  @Test
  void failedRemoteInvocationStillClearsTheSelectedCredential() {
    var credentials = credentials();
    runner(credentials)
        .withPropertyValues("simplepoint.service-router.consumer.local-first=false")
        .withBean(
            ServiceDiscovery.class,
            () ->
                capability ->
                    List.of(
                        new ServiceRoute(
                            "remote", "instance", URI.create("http://unused"), Map.of())))
        .withBean(
            RemoteInvoker.class,
            () ->
                (route, request) -> {
                  assertThat(
                          decoder
                              .decode(credentials.getAccessToken())
                              .getClaimAsString("client_id"))
                      .isEqualTo("caller-one");
                  throw new org.springframework.web.client.ResourceAccessException(
                      "fixture unavailable");
                })
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThatThrownBy(() -> context.getBean(CallerOneModule.Consumer.class).deferred())
                  .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
              assertThatThrownBy(credentials::getAccessToken)
                  .isInstanceOf(ModuleCompositionException.class);
            });
  }

  @Test
  void defaultConfigurationFetchesSeparateCredentialsAndAuthenticatesThemLocally() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    expectToken(server, "caller-one", one);
    expectToken(server, "caller-two", two);
    baseRunner()
        .withPropertyValues(
            "simplepoint.service-router.internal-auth.oauth2.token-uri=https://issuer.example/token",
            "simplepoint.modules.credentials.caller-one.client-id=caller-one",
            "simplepoint.modules.credentials.caller-one.client-secret=fixture-secret",
            "simplepoint.modules.credentials.caller-two.client-id=caller-two",
            "simplepoint.modules.credentials.caller-two.client-secret=fixture-secret")
        .withBean(RestClient.class, builder::build)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var consumer = context.getBean(CallerOneModule.Consumer.class);
              assertThat(consumer.field()).isEqualTo("caller-one");
              assertThat(consumer.chain())
                  .containsExactly("caller-one", "caller-two", "caller-one");
              assertThat(consumer.deferred()).isEqualTo("caller-one");
              server.verify();
            });
  }

  private void expectToken(MockRestServiceServer server, String client, String token) {
    server
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "https://issuer.example/token"))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                "Authorization",
                "Basic "
                    + java.util.Base64.getEncoder()
                        .encodeToString(
                            (client + ":fixture-secret")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"access_token\":\"" + token + "\",\"expires_in\":300}",
                org.springframework.http.MediaType.APPLICATION_JSON));
  }

  private ApplicationContextRunner runner() {
    return runner(credentials());
  }

  private ApplicationContextRunner runner(ModuleServiceCredentials credentials) {
    return baseRunner()
        .withBean(
            ModuleServiceCredentials.class,
            () -> credentials,
            definition -> definition.setPrimary(true));
  }

  private ApplicationContextRunner baseRunner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(Host.class)
        .withConfiguration(AutoConfigurations.of(ServiceRouterLocalSecurityAutoConfiguration.class))
        .withPropertyValues(
            "simplepoint.service-router.internal-auth.mode=oauth2",
            "simplepoint.service-router.http.read-timeout=5s")
        .withBean(JwtDecoder.class, () -> decoder)
        .withBean(TokenRevocationService.class, () -> mock(TokenRevocationService.class))
        .withBean(AuthorizationGrantedAuthorityLoader.class, () -> claims -> List.of());
  }

  private ModuleServiceCredentials credentials() {
    return new ModuleServiceCredentials(
        Map.of(
            "caller-one", (ServiceRouterAccessTokenProvider) () -> one,
            "caller-two", (ServiceRouterAccessTokenProvider) () -> two));
  }

  private static String token(String client) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://issuer.example")
            .subject(client)
            .audience("simplepoint-api")
            .claim("client_id", client)
            .claim("grant_type", "client_credentials")
            .claim("scope", "service-router.invoke")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static void assertFailure(Throwable error, String code) {
    assertThat(error).isNotNull();
    while (!(error instanceof ModuleCompositionException) && error.getCause() != null) {
      error = error.getCause();
    }
    assertThat(error).isInstanceOf(ModuleCompositionException.class);
    assertThat(((ModuleCompositionException) error).code()).isEqualTo(code);
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules({"caller-one", "caller-two", "callee"})
  static class Host {}

  @Configuration(proxyBeanMethods = false)
  static class Unowned {
    @Bean
    Object unownedConsumer(CallerIdentity identity) {
      throw new AssertionError("Unowned client must be rejected first");
    }
  }
}
