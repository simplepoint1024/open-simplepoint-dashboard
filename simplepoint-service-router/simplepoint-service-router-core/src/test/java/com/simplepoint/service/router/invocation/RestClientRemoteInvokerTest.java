package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientRemoteInvokerTest {

  @Test
  void invokeAddsInternalAuthHeaderWhenTokenConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("shared-token");
    properties.getInternalAuth().setToken("secret-token");
    RestClientRemoteInvoker invoker = new RestClientRemoteInvoker(builder.build(), properties);

    server.expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-SimplePoint-Service-Router-Token", "secret-token"))
        .andRespond(withSuccess(
            "{\"success\":true,\"data\":\"ok\",\"errorCode\":null,\"message\":null}",
            MediaType.APPLICATION_JSON
        ));

    RemoteResponse response = invoker.invoke(
        new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
        new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1")
    );

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo("ok");
    server.verify();
  }

  @Test
  void invokeOmitsInternalAuthHeaderWhenTokenNotConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    RestClientRemoteInvoker invoker = new RestClientRemoteInvoker(builder.build(), properties);

    server.expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("X-SimplePoint-Service-Router-Token"))
        .andRespond(withSuccess(
            "{\"success\":true,\"data\":null,\"errorCode\":null,\"message\":null}",
            MediaType.APPLICATION_JSON
        ));

    RemoteResponse response = invoker.invoke(
        new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
        new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1")
    );

    assertThat(response.success()).isTrue();
    server.verify();
  }

  @Test
  void invokeAddsBearerTokenWhenOauth2ModeConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    RestClientRemoteInvoker invoker = new RestClientRemoteInvoker(
        builder.build(),
        properties,
        () -> "jwt-token"
    );

    server.expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer jwt-token"))
        .andExpect(headerDoesNotExist("X-SimplePoint-Service-Router-Token"))
        .andRespond(withSuccess(
            "{\"success\":true,\"data\":\"ok\",\"errorCode\":null,\"message\":null}",
            MediaType.APPLICATION_JSON
        ));

    RemoteResponse response = invoker.invoke(
        new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
        new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1")
    );

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo("ok");
    server.verify();
  }
}
