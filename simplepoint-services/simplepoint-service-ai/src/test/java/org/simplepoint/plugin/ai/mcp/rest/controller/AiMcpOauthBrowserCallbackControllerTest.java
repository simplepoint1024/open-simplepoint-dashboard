package org.simplepoint.plugin.ai.mcp.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthCallbackCommand;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiMcpOauthBrowserCallbackControllerTest {

  private AiMcpServerDefinitionService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiMcpServerDefinitionService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiMcpOauthBrowserCallbackController(service)
    ).build();
  }

  @Test
  void browserOauthCallbackCompletesConnectionAndReturnsToHost() {
    AiMcpOauthBrowserCallbackController controller =
        new AiMcpOauthBrowserCallbackController(service);

    var response = controller.callback(
        "authorization-code",
        "oauth-state",
        null,
        null
    );

    ArgumentCaptor<McpOauthCallbackCommand> command =
        ArgumentCaptor.forClass(McpOauthCallbackCommand.class);
    verify(service).completeOauthAuthorization(command.capture());
    assertThat(command.getValue().code()).isEqualTo("authorization-code");
    assertThat(command.getValue().state()).isEqualTo("oauth-state");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(
        "/#/ai/workbench/mcp-servers?mcpOauthResult=SUCCESS"
    ));
  }

  @Test
  void bindsOauthQueryParametersWithoutJavaParameterMetadata() throws Exception {
    mockMvc.perform(get(AiMcpPaths.BROWSER_OAUTH_CALLBACK)
            .queryParam("code", "authorization-code")
            .queryParam("state", "oauth-state"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(
            "/#/ai/workbench/mcp-servers?mcpOauthResult=SUCCESS"
        ));

    ArgumentCaptor<McpOauthCallbackCommand> command =
        ArgumentCaptor.forClass(McpOauthCallbackCommand.class);
    verify(service).completeOauthAuthorization(command.capture());
    assertThat(command.getValue().code()).isEqualTo("authorization-code");
    assertThat(command.getValue().state()).isEqualTo("oauth-state");
  }

  @Test
  void providerDenialReturnsToWorkbenchWithStableCode() throws Exception {
    mockMvc.perform(get(AiMcpPaths.BROWSER_OAUTH_CALLBACK)
            .queryParam("error", "access_denied")
            .queryParam("error_description", "private provider prose")
            .queryParam("state", "oauth-state"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(
            "/#/ai/workbench/mcp-servers?mcpOauthResult=AI_MCP_OAUTH_ACCESS_DENIED"
        ));
  }

  @Test
  void expiredStateReturnsToWorkbenchWithoutPrivateProse() {
    doThrow(new IllegalArgumentException("private state details"))
        .when(service).completeOauthAuthorization(any());
    AiMcpOauthBrowserCallbackController controller =
        new AiMcpOauthBrowserCallbackController(service);

    ResponseEntity<Void> response = controller.callback(
        "authorization-code", "expired-state", null, null
    );

    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(
        "/#/ai/workbench/mcp-servers?mcpOauthResult="
            + "AI_MCP_OAUTH_AUTHORIZATION_EXPIRED"
    ));
    assertThat(response.getHeaders().getLocation().toString())
        .doesNotContain("private state details");
  }

  @Test
  void tokenFailureReturnsToWorkbenchWithoutPrivateProse() {
    doThrow(new IllegalStateException("private token response"))
        .when(service).completeOauthAuthorization(any());
    AiMcpOauthBrowserCallbackController controller =
        new AiMcpOauthBrowserCallbackController(service);

    ResponseEntity<Void> response = controller.callback(
        "authorization-code", "oauth-state", null, null
    );

    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(
        "/#/ai/workbench/mcp-servers?mcpOauthResult="
            + "AI_MCP_OAUTH_AUTHORIZATION_FAILED"
    ));
    assertThat(response.getHeaders().getLocation().toString())
        .doesNotContain("private token response");
  }

  @Test
  void accessDeniedStillUsesTheSecurityErrorContract() {
    doThrow(new AccessDeniedException("forbidden"))
        .when(service).completeOauthAuthorization(any());
    AiMcpOauthBrowserCallbackController controller =
        new AiMcpOauthBrowserCallbackController(service);

    assertThrows(
        AccessDeniedException.class,
        () -> controller.callback(
            "authorization-code", "oauth-state", null, null
        )
    );
  }
}
