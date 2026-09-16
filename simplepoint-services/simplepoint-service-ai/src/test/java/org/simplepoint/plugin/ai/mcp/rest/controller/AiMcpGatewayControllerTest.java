package org.simplepoint.plugin.ai.mcp.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayStatus;
import org.springframework.security.access.prepost.PreAuthorize;

class AiMcpGatewayControllerTest {

  @Test
  void statusIsAvailableFromBothLegacyGatewayAndMcpCenterPermissions() throws Exception {
    Method method = AiMcpGatewayController.class.getMethod("status");
    PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value())
        .contains("ai.workbench.mcp-servers.view")
        .contains("ai.workbench.mcp-gateway.view");
  }

  @Test
  void statusDelegatesToGatewayOperations() {
    McpGatewayOperations gatewayOperations = mock(McpGatewayOperations.class);
    McpGatewayStatus status = new McpGatewayStatus(
        "mcp-gateway",
        "UP",
        "2025-11-25",
        "1.0.0",
        "gateway-1",
        Instant.parse("2026-08-07T00:00:00Z")
    );
    when(gatewayOperations.status()).thenReturn(status);

    var response = new AiMcpGatewayController(gatewayOperations).status();

    assertThat(response.getBody()).isEqualTo(status);
    verify(gatewayOperations).status();
  }
}
