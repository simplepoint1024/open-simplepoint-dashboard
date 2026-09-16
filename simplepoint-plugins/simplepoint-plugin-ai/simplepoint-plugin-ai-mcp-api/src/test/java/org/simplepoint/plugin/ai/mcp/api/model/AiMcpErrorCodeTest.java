package org.simplepoint.plugin.ai.mcp.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;

class AiMcpErrorCodeTest {

  private static final String PRIVATE_PROSE =
      "Unable to discover through MCP Gateway: HTTP 500 secret-host";

  @Test
  void classifiesLegacyOauthDiagnostics() {
    assertThat(AiMcpErrorCode.fromOauthDiagnostic("access_denied: user cancelled"))
        .isEqualTo(AiMcpErrorCode.AI_MCP_OAUTH_ACCESS_DENIED);
    assertThat(AiMcpErrorCode.fromOauthDiagnostic("invalid_grant"))
        .isEqualTo(AiMcpErrorCode.AI_MCP_OAUTH_AUTHORIZATION_EXPIRED);
    assertThat(AiMcpErrorCode.fromOauthDiagnostic("provider details"))
        .isEqualTo(AiMcpErrorCode.AI_MCP_OAUTH_AUTHORIZATION_FAILED);
  }

  @Test
  void serializesServerDiagnosticAsStableCode() throws Exception {
    AiMcpServerDefinition server = new AiMcpServerDefinition();
    server.setLastError(PRIVATE_PROSE);

    String json = new ObjectMapper().writeValueAsString(server);

    assertThat(json).contains("\"lastError\":\"AI_MCP_DISCOVERY_FAILED\"");
    assertThat(json).doesNotContain(PRIVATE_PROSE);
  }

  @Test
  void serializesOauthDiagnosticAsStableCode() throws Exception {
    AiMcpProviderConnection connection = new AiMcpProviderConnection();
    connection.setLastError("access_denied: sensitive provider prose");

    String json = new ObjectMapper().writeValueAsString(connection);

    assertThat(json).contains("\"lastError\":\"AI_MCP_OAUTH_ACCESS_DENIED\"");
    assertThat(json).doesNotContain("sensitive provider prose");
  }
}
