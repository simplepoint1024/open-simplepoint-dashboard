package org.simplepoint.mcp.gateway.rest;

import java.util.Map;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishes the Gateway's OAuth Client ID Metadata Document.
 */
@RestController
public class McpOauthClientMetadataController {

  private final McpOauthClientMetadataDocument metadataDocument;

  /**
   * Creates the client metadata controller.
   *
   * @param metadataDocument validated metadata renderer
   */
  public McpOauthClientMetadataController(
      final McpOauthClientMetadataDocument metadataDocument
  ) {
    this.metadataDocument = metadataDocument;
  }

  /**
   * Returns the configured OAuth public-client metadata.
   *
   * @return metadata response
   */
  @GetMapping(McpOauthClientMetadataDocument.PATH)
  public ResponseEntity<Map<String, Object>> metadata() {
    if (!metadataDocument.configured()) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND,
          "OAuth client metadata document is not configured"
      );
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .body(metadataDocument.document());
  }
}
