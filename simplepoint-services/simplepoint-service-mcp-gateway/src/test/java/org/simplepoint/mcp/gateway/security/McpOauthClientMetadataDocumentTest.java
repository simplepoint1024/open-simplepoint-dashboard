package org.simplepoint.mcp.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;

class McpOauthClientMetadataDocumentTest {

  @Test
  void rendersStandardsCompliantPublicClientDocument() {
    McpGatewayProperties properties = new McpGatewayProperties();
    properties.setOauthClientMetadataDocumentUri(
        "https://gateway.example" + McpOauthClientMetadataDocument.PATH
    );
    properties.setOauthClientName("SimplePoint Test Gateway");
    properties.setOauthClientRedirectUris(List.of(
        "https://gateway.example/ai/workbench/mcp-servers",
        "http://127.0.0.1:8080/callback"
    ));
    McpOauthClientMetadataDocument metadata =
        new McpOauthClientMetadataDocument(properties);

    Map<String, Object> document = metadata.document();

    assertThat(document)
        .containsEntry("client_id",
            "https://gateway.example" + McpOauthClientMetadataDocument.PATH)
        .containsEntry("client_name", "SimplePoint Test Gateway")
        .containsEntry("token_endpoint_auth_method", "none");
    assertThat(document.get("redirect_uris")).isEqualTo(
        properties.getOauthClientRedirectUris()
    );
  }

  @Test
  void rejectsWrongDocumentPathAndNonLoopbackHttpRedirect() {
    McpGatewayProperties properties = new McpGatewayProperties();
    properties.setOauthClientMetadataDocumentUri(
        "https://gateway.example/wrong-path"
    );
    properties.setOauthClientRedirectUris(List.of(
        "http://client.example/callback"
    ));
    McpOauthClientMetadataDocument metadata =
        new McpOauthClientMetadataDocument(properties);

    assertThat(metadata.configured()).isFalse();
    assertThatThrownBy(metadata::document)
        .isInstanceOf(IllegalStateException.class);
  }
}
