package org.simplepoint.plugin.ai.mcp.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiMcpOauthRedirectUriValidationTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "http://localhost:8080/ai/workbench/mcp-servers",
      "http://dev.localhost:8080/oauth/callback",
      "http://127.0.0.1:8080/ai/workbench/mcp-servers",
      "http://127.42.7.9:8080/oauth/callback",
      "http://[::1]:8080/oauth/callback",
      "https://mcp.example.com/oauth/callback"
  })
  void acceptsHttpsAndHttpLoopbackOauthRedirectUris(final String redirectUri) {
    assertThat(AiMcpServerDefinitionServiceImpl.validateOauthRedirectUri(
        redirectUri
    )).isEqualTo(redirectUri);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://mcp.example.com/oauth/callback",
      "http://127.example.com/oauth/callback",
      "http://127.0.0.1.example.com/oauth/callback",
      "http://128.0.0.1/oauth/callback",
      "http://127.00.0.1/oauth/callback",
      "http://127.0.0.256/oauth/callback",
      "http://user@127.0.0.1/oauth/callback",
      "http://127.0.0.1/oauth/callback#fragment"
  })
  void rejectsInsecureNonLoopbackAndAmbiguousOauthRedirectUris(
      final String redirectUri
  ) {
    assertThatThrownBy(() ->
        AiMcpServerDefinitionServiceImpl.validateOauthRedirectUri(redirectUri)
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must use HTTPS");
  }
}
