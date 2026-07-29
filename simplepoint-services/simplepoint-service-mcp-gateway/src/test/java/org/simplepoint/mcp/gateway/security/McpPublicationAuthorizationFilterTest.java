package org.simplepoint.mcp.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.publication.McpPublicationRateLimiter;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class McpPublicationAuthorizationFilterTest {

  private McpPublicationRegistry registry;

  private McpPublicationRateLimiter rateLimiter;

  private McpPublicationAuthorizationFilter filter;

  @BeforeEach
  void setUp() {
    registry = mock(McpPublicationRegistry.class);
    rateLimiter = mock(McpPublicationRateLimiter.class);
    filter = new McpPublicationAuthorizationFilter(registry, rateLimiter);
    when(registry.manifest("demo")).thenReturn(manifest());
    when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt())).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejectsTokenForDifferentAudienceWithResourceMetadataChallenge()
      throws Exception {
    authenticate(List.of("https://platform.example/mcp/other"), "mcp.invoke");
    MockHttpServletRequest request = request();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertTrue(response.getHeader("WWW-Authenticate")
        .contains("resource_metadata=\"https://platform.example/"
            + ".well-known/oauth-protected-resource/mcp/demo\""));
  }

  @Test
  void rejectsMissingPublicationScope() throws Exception {
    authenticate(List.of("https://platform.example/mcp/demo"), "profile");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request(), response, new MockFilterChain());

    assertEquals(403, response.getStatus());
    assertTrue(response.getHeader("WWW-Authenticate")
        .contains("insufficient_scope"));
  }

  @Test
  void bindsIdentityAndAppliesDistributedRateLimit() throws Exception {
    authenticate(List.of("https://platform.example/mcp/demo"), "mcp.invoke");
    MockHttpServletRequest request = request();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
    assertEquals(
        "user-1",
        request.getAttribute(McpPublicationRegistry.SUBJECT_CONTEXT_KEY)
    );
    assertEquals(
        "client-1",
        request.getAttribute(McpPublicationRegistry.CLIENT_CONTEXT_KEY)
    );
    verify(rateLimiter).tryAcquire("demo", "client-1", 60);
  }

  private static McpPublicationManifest manifest() {
    return new McpPublicationManifest(
        "demo",
        "Demo",
        "Demo publication",
        "https://platform.example/mcp/demo",
        "https://auth.example",
        List.of("mcp.invoke"),
        60,
        "server-1",
        "snapshot-1",
        "2025-11-25",
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/mcp/demo"
    );
    request.setScheme("https");
    request.addHeader("Host", "platform.example");
    return request;
  }

  private static void authenticate(
      final List<String> audiences,
      final String scope
  ) {
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "PS256")
        .subject("user-1")
        .audience(audiences)
        .claim("client_id", "client-1")
        .claim("scope", scope)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(jwt, List.of())
    );
  }
}
