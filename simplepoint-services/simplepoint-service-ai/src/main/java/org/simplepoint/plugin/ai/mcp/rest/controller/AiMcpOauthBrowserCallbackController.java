package org.simplepoint.plugin.ai.mcp.rest.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthCallbackCommand;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated browser landing endpoint for external MCP OAuth providers. */
@Hidden
@Slf4j
@RestController
@RequestMapping(AiMcpPaths.BROWSER_OAUTH_CALLBACK)
public class AiMcpOauthBrowserCallbackController {

  private static final String HOST_MCP_PATH = "/#/ai/workbench/mcp-servers";

  private static final String SUCCESS = "SUCCESS";

  private static final String ACCESS_DENIED = "AI_MCP_OAUTH_ACCESS_DENIED";

  private static final String AUTHORIZATION_EXPIRED =
      "AI_MCP_OAUTH_AUTHORIZATION_EXPIRED";

  private static final String AUTHORIZATION_FAILED =
      "AI_MCP_OAUTH_AUTHORIZATION_FAILED";

  private final AiMcpServerDefinitionService service;

  /** Creates the authenticated MCP OAuth browser callback controller. */
  public AiMcpOauthBrowserCallbackController(
      final AiMcpServerDefinitionService service
  ) {
    this.service = service;
  }

  /** Completes the code exchange and returns the browser to the host entry page. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.authorize')"
  )
  public ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) final String code,
      @RequestParam(name = "state") final String state,
      @RequestParam(name = "error", required = false) final String error,
      @RequestParam(name = "error_description", required = false)
      final String errorDescription
  ) {
    try {
      service.completeOauthAuthorization(new McpOauthCallbackCommand(
          code,
          state,
          error,
          errorDescription
      ));
      if (error != null && !error.isBlank()) {
        return redirect("access_denied".equalsIgnoreCase(error)
            ? ACCESS_DENIED : AUTHORIZATION_FAILED);
      }
      return redirect(SUCCESS);
    } catch (AccessDeniedException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      log.warn("MCP browser OAuth callback authorization is invalid", ex);
      return redirect(AUTHORIZATION_EXPIRED);
    } catch (RuntimeException ex) {
      log.warn("MCP browser OAuth callback failed", ex);
      return redirect(AUTHORIZATION_FAILED);
    }
  }

  private static ResponseEntity<Void> redirect(final String result) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(HOST_MCP_PATH + "?mcpOauthResult=" + result))
        .build();
  }
}
