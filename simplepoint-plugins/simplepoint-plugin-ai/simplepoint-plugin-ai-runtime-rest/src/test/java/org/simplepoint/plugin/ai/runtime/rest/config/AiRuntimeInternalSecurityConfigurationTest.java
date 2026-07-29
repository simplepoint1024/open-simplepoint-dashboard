package org.simplepoint.plugin.ai.runtime.rest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class AiRuntimeInternalSecurityConfigurationTest {

  @Test
  void shouldRejectMissingControlToken() throws Exception {
    final AiRuntimeProperties properties = properties();
    final var filter =
        new AiRuntimeInternalSecurityConfiguration.RuntimeInternalTokenFilter(
            properties
        );
    final MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/runtime/nodes/node-a/heartbeat"
    );
    final MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
  }

  @Test
  void shouldAuthenticateMatchingControlTokenAndClearContext() throws Exception {
    final AiRuntimeProperties properties = properties();
    final var filter =
        new AiRuntimeInternalSecurityConfiguration.RuntimeInternalTokenFilter(
            properties
        );
    final MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/runtime/nodes/node-a/heartbeat"
    );
    request.addHeader(
        properties.getControlInternalHeader(),
        properties.getControlInternalToken()
    );
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final AtomicReference<Authentication> observed = new AtomicReference<>();
    final FilterChain chain =
        (servletRequest, servletResponse) ->
            observed.set(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(request, response, chain);

    assertNotNull(observed.get());
    assertEquals(
        "ROLE_TOOL_RUNTIME_NODE",
        observed.get().getAuthorities().iterator().next().getAuthority()
    );
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void shouldBindMutualTlsIdentityToRequestedNodeId() throws Exception {
    final AiRuntimeProperties properties = properties();
    properties.setMtlsEnabled(true);
    final var filter =
        new AiRuntimeInternalSecurityConfiguration.RuntimeInternalTokenFilter(
            properties
        );
    final X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
        List.of(
            6,
            "spiffe://open-simplepoint/runtime-node/node-a"
        )
    ));
    final MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/runtime/nodes/node-a/heartbeat"
    );
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate",
        new X509Certificate[]{certificate}
    );
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final AtomicReference<Authentication> observed = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) ->
            observed.set(
                SecurityContextHolder.getContext().getAuthentication()
            )
    );

    assertNotNull(observed.get());
    assertEquals("node-a", observed.get().getPrincipal());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void shouldRejectCertificateForDifferentNodeId() throws Exception {
    final AiRuntimeProperties properties = properties();
    properties.setMtlsEnabled(true);
    final var filter =
        new AiRuntimeInternalSecurityConfiguration.RuntimeInternalTokenFilter(
            properties
        );
    final X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
        List.of(
            6,
            "spiffe://open-simplepoint/runtime-node/node-b"
        )
    ));
    final MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/runtime/nodes/node-a/heartbeat"
    );
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate",
        new X509Certificate[]{certificate}
    );
    final MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
  }

  private AiRuntimeProperties properties() {
    final AiRuntimeProperties properties = new AiRuntimeProperties();
    properties.setControlInternalHeader(
        "X-SimplePoint-Tool-Runtime-Control-Token"
    );
    properties.setControlInternalToken("test-control-token-0123456789");
    return properties;
  }
}
