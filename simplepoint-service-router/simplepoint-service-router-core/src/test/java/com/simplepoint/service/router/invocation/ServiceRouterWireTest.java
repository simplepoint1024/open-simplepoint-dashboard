package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.proxy.ServiceRouterInvocationHandler;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceRoute;
import com.simplepoint.service.router.web.ServiceRouterController;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class ServiceRouterWireTest {

  private static final String PATH = "/_simplepoint/service-router/invoke";

  @Test
  void httpRoundTripPreservesPreciseValuesAndResolvesInheritedGenericTypes() {
    try (var fixture = new Fixture()) {
      Value expected = new Value(
          Map.of("decimal", new BigDecimal("12345678901234567890.1234567890123456789"),
              "integer", new BigInteger("1234567890123456789012345678901234567890")),
          new BigDecimal("98765432109876543210.9876543210987654321"), Long.MAX_VALUE,
          LocalDate.of(2026, 10, 20), LocalTime.of(12, 23, 34, 567891234),
          Instant.parse("2026-10-20T12:23:34.567891234Z"), "客户・顧客・🙂");
      Value result = fixture.proxy.echo(expected);
      assertThat(result).isEqualTo(expected).isNotSameAs(expected);
      assertThat(fixture.provider.last).isEqualTo(expected).isNotSameAs(expected);
      assertThat(fixture.proxy.find(true)).contains(expected);
      assertThat(fixture.proxy.find(false)).isEmpty();
      assertThat(fixture.proxy.echo(null)).isNull();
      assertThat(fixture.mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
    }
  }

  @Test
  void invalidAndTrailingJsonNeverReachTheProviderOrLeakSubmittedValues() throws Exception {
    try (var fixture = new Fixture()) {
      for (String request : List.of(
          "null", "{\"service\":\"sensitive-submitted-value\"", "{} {}", "[]",
          "{\"service\":\"wire\",\"version\":\"1\",\"method\":\"echo\",\"args\":[]}")) {
        byte[] body = fixture.mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(fixture.codec.decodeResponse(body).success()).isFalse();
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("sensitive-submitted-value");
      }
      assertThat(fixture.provider.calls).hasValue(0);
    }
  }

  @Test
  void unexpectedProviderFailureDoesNotExposeImplementationOrSubmittedValues() throws Exception {
    try (var fixture = new Fixture()) {
      byte[] request = fixture.codec.encode(new RemoteRequest("wire", "1", "fail", List.of(), "trace"));
      byte[] body = fixture.mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
      RemoteResponse response = fixture.codec.decodeResponse(body);
      assertThat(response.success()).isFalse();
      assertThat(response.errorCode()).isEqualTo("REMOTE_SERVICE_ERROR");
      assertThat(response.message()).isEqualTo("Remote service invocation failed");
      assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("sensitive-provider-detail");
    }
  }

  @Test
  void reactiveHttpEntryAlsoKeepsUntypedDecimalsExact() {
    try (var fixture = new Fixture()) {
      var dispatcher = new ServiceInvocationDispatcher(
          new Registry(new LocalRoutedService(
              RoutedServiceMetadataResolver.resolve(Values.class).orElseThrow(), () -> fixture.provider, Values.class)),
          fixture.mapper);
      var client = WebTestClient.bindToController(new ServiceRouterController(dispatcher)).build();
      String request = """
          {"service":"wire","version":"1","method":"echo","traceId":"reactive","args":[{
            "values":{"decimal":12345678901234567890.1234567890123456789},
            "amount":98765432109876543210.9876543210987654321,"largeInteger":9223372036854775807
          }]}
          """;
      byte[] body = client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
          .bodyValue(request.getBytes(StandardCharsets.UTF_8)).exchange()
          .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
          .expectBody().returnResult().getResponseBody();
      RemoteResponse response = fixture.codec.decodeResponse(body);
      assertThat(response.success()).isTrue();
      assertThat(fixture.provider.last.values().get("decimal"))
          .isEqualTo(new BigDecimal("12345678901234567890.1234567890123456789"));
      assertThat(response.data()).isInstanceOfSatisfying(Map.class, data -> {
        assertThat(data.get("amount")).isEqualTo(new BigDecimal("98765432109876543210.9876543210987654321"));
        assertThat(data.get("largeInteger")).isEqualTo(Long.MAX_VALUE);
      });
    }
  }

  public interface GenericService<T> {
    T echo(T value);

    Optional<T> find(boolean present);
  }

  @RemoteContract(name = "wire")
  public interface Values extends GenericService<Value> {
    String fail();
  }

  public record Value(Map<String, Object> values, BigDecimal amount, long largeInteger,
                      LocalDate date, LocalTime time, Instant instant, String text) {
  }

  private static class Provider implements Values {
    Value last;
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public Value echo(Value value) {
      calls.incrementAndGet();
      last = value;
      return value;
    }

    @Override
    public Optional<Value> find(boolean present) {
      return present ? Optional.ofNullable(last) : Optional.empty();
    }

    @Override
    public String fail() {
      throw new IllegalStateException("sensitive-provider-detail");
    }
  }

  private static class Fixture implements AutoCloseable {
    final ObjectMapper mapper = new ObjectMapper();
    final ServiceRouterJsonCodec codec = new ServiceRouterJsonCodec(mapper);
    final StaticApplicationContext context = new StaticApplicationContext();
    final Provider provider = new Provider();
    final MockMvc mvc;
    final Values proxy;

    Fixture() {
      var metadata = RoutedServiceMetadataResolver.resolve(Values.class).orElseThrow();
      var registry = new Registry(new LocalRoutedService(metadata, () -> provider, Values.class));
      var dispatcher = new ServiceInvocationDispatcher(registry, mapper);
      mvc = MockMvcBuilders.standaloneSetup(new ServiceRouterController(dispatcher)).build();
      var client = RestClient.builder().requestFactory(new MockMvcClientHttpRequestFactory(mvc)).build();
      var properties = new ServiceRouterProperties();
      properties.getConsumer().setLocalFirst(false);
      var invoker = new RestClientRemoteInvoker(client, properties, () -> null, mapper);
      var handler = new ServiceRouterInvocationHandler(Values.class, metadata, registry,
          ignored -> List.of(new ServiceRoute("wire", "one", URI.create("http://wire"), Map.of())),
          new RoundRobinLoadBalancer(), invoker, mapper, properties, context);
      proxy = (Values) Proxy.newProxyInstance(Values.class.getClassLoader(), new Class<?>[] {Values.class}, handler);
    }

    @Override
    public void close() {
      context.close();
    }
  }

  private record Registry(LocalRoutedService local) implements LocalServiceRegistry {
    @Override
    public Optional<LocalRoutedService> find(String name, String version) {
      return local.metadata().name().equals(name) && local.metadata().version().equals(version)
          ? Optional.of(local) : Optional.empty();
    }

    @Override
    public Collection<LocalRoutedService> all() {
      return List.of(local);
    }
  }
}
