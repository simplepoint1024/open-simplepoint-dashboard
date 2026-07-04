package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientCredentialsServiceRouterAccessTokenProviderTest {

  @Test
  void getAccessTokenRequestsAndCachesClientCredentialsToken() {
    RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ServiceRouterProperties.InternalAuth.Oauth2 properties =
        new ServiceRouterProperties.InternalAuth.Oauth2();
    properties.setTokenUri("http://authorization/oauth2/token");
    properties.setClientId("simplepoint-service-common");
    properties.setClientSecret("secret");
    properties.setScopes(List.of("service-router.invoke"));
    ClientCredentialsServiceRouterAccessTokenProvider provider =
        new ClientCredentialsServiceRouterAccessTokenProvider(
            builder.build(),
            properties,
            Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC)
        );

    server.expect(once(), requestTo("http://authorization/oauth2/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Basic c2ltcGxlcG9pbnQtc2VydmljZS1jb21tb246c2VjcmV0"))
        .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().string("grant_type=client_credentials&scope=service-router.invoke"))
        .andRespond(withSuccess(
            "{\"access_token\":\"jwt-token\",\"token_type\":\"Bearer\",\"expires_in\":300}",
            MediaType.APPLICATION_JSON
        ));

    assertThat(provider.getAccessToken()).isEqualTo("jwt-token");
    assertThat(provider.getAccessToken()).isEqualTo("jwt-token");
    server.verify();
  }
}
