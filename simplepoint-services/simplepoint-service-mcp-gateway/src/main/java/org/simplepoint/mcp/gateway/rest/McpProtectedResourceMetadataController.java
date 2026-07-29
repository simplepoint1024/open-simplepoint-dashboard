package org.simplepoint.mcp.gateway.rest;

import java.util.List;
import java.util.Map;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RFC 9728 metadata for each canonical MCP publication resource.
 */
@RestController
public class McpProtectedResourceMetadataController {

  private final McpPublicationRegistry registry;

  /**
   * Creates the protected-resource metadata controller.
   */
  public McpProtectedResourceMetadataController(
      final McpPublicationRegistry registry
  ) {
    this.registry = registry;
  }

  /**
   * Returns path-specific protected resource metadata.
   */
  @GetMapping("/.well-known/oauth-protected-resource/mcp/{code}")
  public Map<String, Object> protectedResourceMetadata(
      @PathVariable("code") final String code
  ) {
    McpPublicationManifest manifest;
    try {
      manifest = registry.manifest(code);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "MCP publication does not exist",
          ex
      );
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "MCP publication metadata is unavailable",
          ex
      );
    }
    return Map.of(
        "resource", manifest.canonicalResourceUri(),
        "authorization_servers", List.of(manifest.authorizationServerUri()),
        "scopes_supported", manifest.requiredScopes(),
        "bearer_methods_supported", List.of("header"),
        "resource_name", manifest.name()
    );
  }
}
