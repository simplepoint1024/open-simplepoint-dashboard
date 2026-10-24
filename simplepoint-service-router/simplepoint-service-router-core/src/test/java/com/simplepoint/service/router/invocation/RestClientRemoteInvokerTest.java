package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-SimplePoint-Service-Router-Token", "secret-token"))
        .andRespond(
            withSuccess(
                "{\"success\":true,\"data\":\"ok\",\"errorCode\":null,\"message\":null}",
                MediaType.APPLICATION_JSON));

    RemoteResponse response =
        invoker.invoke(
            new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
            new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1"));

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

    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(headerDoesNotExist("X-SimplePoint-Service-Router-Token"))
        .andRespond(
            withSuccess(
                "{\"success\":true,\"data\":null,\"errorCode\":null,\"message\":null}",
                MediaType.APPLICATION_JSON));

    RemoteResponse response =
        invoker.invoke(
            new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
            new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1"));

    assertThat(response.success()).isTrue();
    server.verify();
  }

  @Test
  void invokeAddsBearerTokenWhenOauth2ModeConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    RestClientRemoteInvoker invoker =
        new RestClientRemoteInvoker(builder.build(), properties, () -> "jwt-token");

    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer jwt-token"))
        .andExpect(headerDoesNotExist("X-SimplePoint-Service-Router-Token"))
        .andRespond(
            withSuccess(
                "{\"success\":true,\"data\":\"ok\",\"errorCode\":null,\"message\":null}",
                MediaType.APPLICATION_JSON));

    RemoteResponse response =
        invoker.invoke(
            new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of()),
            new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1"));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo("ok");
    server.verify();
  }

  @Test
  void rejectedCredentialRefreshesOnceBeforeRpcDispatch() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    ServiceRouterAccessTokenProvider provider = mock(ServiceRouterAccessTokenProvider.class);
    when(provider.getAccessToken()).thenReturn("old-token", "new-token");
    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(header("Authorization", "Bearer old-token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andExpect(header("Authorization", "Bearer new-token"))
        .andExpect(
            content()
                .json(
                    "{\"service\":\"sample.Service\",\"version\":\"1.0\",\"method\":\"sync\",\"args\":[],\"traceId\":\"trace-1\"}"))
        .andRespond(withSuccess("{\"success\":true,\"data\":\"ok\"}", MediaType.APPLICATION_JSON));
    RemoteResponse result =
        new RestClientRemoteInvoker(builder.build(), properties, provider)
            .invoke(route(), request());
    assertThat(result.data()).isEqualTo("ok");
    verify(provider).invalidate("old-token");
    verify(provider, times(2)).getAccessToken();
    server.verify();
  }

  @Test
  void repeatedRejectionStopsAfterOneReplacement() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    ServiceRouterAccessTokenProvider provider = mock(ServiceRouterAccessTokenProvider.class);
    when(provider.getAccessToken()).thenReturn("old-token", "new-token");
    for (String token : List.of("old-token", "new-token")) {
      server
          .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
          .andExpect(header("Authorization", "Bearer " + token))
          .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    }
    assertThatThrownBy(
            () ->
                new RestClientRemoteInvoker(builder.build(), properties, provider)
                    .invoke(route(), request()))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
    verify(provider).invalidate("old-token");
    verify(provider, times(2)).getAccessToken();
    server.verify();
  }

  @Test
  void unchangedCredentialIsNotReplayed() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    assertThatThrownBy(
            () ->
                new RestClientRemoteInvoker(builder.build(), properties, () -> "static-token")
                    .invoke(route(), request()))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
    server.verify();
  }

  @Test
  void forbiddenServerFailuresAndTransportFailuresNeverReplay() {
    for (HttpStatus status :
        List.of(
            HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.GATEWAY_TIMEOUT)) {
      assertFailureNotRetried(withStatus(status));
    }
    assertFailureNotRetried(withException(new IOException("connection interrupted")));
  }

  private void assertFailureNotRetried(
      org.springframework.test.web.client.ResponseCreator failure) {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    ServiceRouterAccessTokenProvider provider = mock(ServiceRouterAccessTokenProvider.class);
    when(provider.getAccessToken()).thenReturn("cached-token");
    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andRespond(failure);
    assertThatThrownBy(
            () ->
                new RestClientRemoteInvoker(builder.build(), properties, provider)
                    .invoke(route(), request()))
        .isInstanceOf(org.springframework.web.client.RestClientException.class);
    verify(provider).getAccessToken();
    verifyNoMoreInteractions(provider);
    server.verify();
  }

  @Test
  void sharedTokenRejectionDoesNotInvokeOauthProvider() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("shared-token");
    ServiceRouterAccessTokenProvider provider = mock(ServiceRouterAccessTokenProvider.class);
    server
        .expect(requestTo("http://common/_simplepoint/service-router/invoke"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    assertThatThrownBy(
            () ->
                new RestClientRemoteInvoker(builder.build(), properties, provider)
                    .invoke(route(), request()))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
    verifyNoInteractions(provider);
    server.verify();
  }

  private ServiceRoute route() {
    return new ServiceRoute("common", "common-1", URI.create("http://common"), Map.of());
  }

  private RemoteRequest request() {
    return new RemoteRequest("sample.Service", "1.0", "sync", List.of(), "trace-1");
  }
}
