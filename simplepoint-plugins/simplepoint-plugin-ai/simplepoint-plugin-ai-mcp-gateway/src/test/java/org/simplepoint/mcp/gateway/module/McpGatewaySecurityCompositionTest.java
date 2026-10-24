package org.simplepoint.mcp.gateway.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.publication.McpPublicationRateLimiter;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.mcp.gateway.rest.McpGatewayInternalController;
import org.simplepoint.mcp.gateway.security.McpGatewaySecurityConfiguration;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpGatewaySecurityCompositionTest {
  private final ModuleCatalog catalog = ModuleCatalog.discover(getClass().getClassLoader());
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withUserConfiguration(
              Security.class,
              McpGatewaySecurityConfiguration.class,
              McpGatewayInternalController.class)
          .withBean("defaultRemoteMcpGatewayOperations", McpGatewayOperations.class, () -> mock(McpGatewayOperations.class))
          .withBean("httpMcpGatewayOperations", McpGatewayOperations.class, () -> mock(McpGatewayOperations.class))
          .withBean(McpPublicationRegistry.class, () -> mock(McpPublicationRegistry.class))
          .withBean(McpPublicationRateLimiter.class, () -> mock(McpPublicationRateLimiter.class))
          .withBean(
              McpGatewayProperties.class,
              () -> {
                var properties = new McpGatewayProperties();
                properties.setInternalToken("test-internal-token");
                return properties;
              });

  @Test
  void gatewayChainsLeaveAllOtherModulesAndInfrastructureToTheirOwners() {
    runner
        .withBean(ModulePlan.class, () -> catalog.plan(List.of("mcp-gateway")))
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(JwtDecoder.class);
              var gateway =
                  context.getBean("mcpGatewaySecurityFilterChain", SecurityFilterChain.class);
              var metadata =
                  context.getBean(
                      "mcpProtectedResourceMetadataSecurityFilterChain", SecurityFilterChain.class);
              for (String path :
                  List.of(
                      "/mcp/demo", "/internal/mcp/status", McpOauthClientMetadataDocument.PATH)) {
                assertThat(matches(gateway, path)).as(path).isTrue();
              }
              assertThat(matches(metadata, "/.well-known/oauth-protected-resource/mcp/demo"))
                  .isTrue();
              for (String path :
                  List.of(
                      "/ai/internal/mcp/publications/snapshot",
                      "/common/users",
                      "/app/forms",
                      "/actuator/health",
                      "/error",
                      "/oauth2/jwks",
                      "/.well-known/openid-configuration",
                      "/.well-known/oauth-protected-resource/other",
                      "/internal/foreign",
                      "/mcp-foreign/demo")) {
                assertThat(matches(gateway, path)).as(path).isFalse();
                assertThat(matches(metadata, path)).as(path).isFalse();
              }
            });
  }

  @Test
  void privateOperationsRequireTheirOwnCredentialEvenWithServletContext() {
    runner
        .withBean(ModulePlan.class, () -> catalog.plan(List.of("mcp-gateway")))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(context.getBean(FilterChainProxy.class))
                      .build();
              for (String contextPath : List.of("", "/platform")) {
                String path = contextPath + "/internal/mcp/status";
                mvc.perform(get(path).contextPath(contextPath))
                    .andExpect(status().isUnauthorized());
                mvc.perform(
                        get(path)
                            .contextPath(contextPath)
                            .header("X-SimplePoint-MCP-Gateway-Token", "wrong"))
                    .andExpect(status().isUnauthorized());
                mvc.perform(
                        get(path)
                            .contextPath(contextPath)
                            .header("X-SimplePoint-MCP-Gateway-Token", "test-internal-token"))
                    .andExpect(status().isOk());
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
              }
            });
  }

  @Test
  void legacyHostRetainsItsCatchAllBoundaryAndCannotPublishGlobalJwtDecoder() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(JwtDecoder.class);
          assertThat(
                  matches(
                      context.getBean("mcpGatewaySecurityFilterChain", SecurityFilterChain.class),
                      "/foreign"))
              .isTrue();
        });
  }

  @Test
  void accidentallyImportedSecurityWithoutItsSelectedModuleFailsClosed() {
    runner
        .withBean(ModulePlan.class, () -> catalog.plan(List.of()))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("UNOWNED_HTTP_ADAPTER"));
  }

  private boolean matches(SecurityFilterChain chain, String path) {
    var request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    return chain.matches(request);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @EnableMethodSecurity
  static class Security {}
}
