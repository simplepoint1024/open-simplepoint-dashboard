package org.simplepoint.mcp.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import org.junit.jupiter.api.Test;

class McpOauthProxySelectorTest {

  @Test
  void proxiesRemoteOauthAndBypassesLoopbackAndNoProxyHosts() {
    ProxySelector selector = McpOauthProxySelector.create(
        "http://proxy.example:3128",
        "localhost,127.0.0.1,.internal.example,api.example:8443"
    ).orElseThrow();

    assertThat(selector.select(URI.create("https://github.com/login/oauth")))
        .extracting(Proxy::type)
        .containsExactly(Proxy.Type.HTTP);
    assertThat(selector.select(URI.create("http://127.0.0.1:9000/oauth")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("https://auth.internal.example/oauth")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("https://api.example:8443/oauth")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("https://api.example/oauth")))
        .extracting(Proxy::type)
        .containsExactly(Proxy.Type.HTTP);
  }

  @Test
  void rejectsCredentialBearingOrNonHttpProxyUrls() {
    assertThatThrownBy(() -> McpOauthProxySelector.create(
        "http://user:secret@proxy.example:3128",
        null
    )).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpOauthProxySelector.create(
        "https://proxy.example:3128",
        null
    )).isInstanceOf(IllegalArgumentException.class);
  }
}
