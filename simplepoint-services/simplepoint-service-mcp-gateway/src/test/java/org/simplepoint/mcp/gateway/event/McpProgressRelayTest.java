package org.simplepoint.mcp.gateway.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpProgressRelayTest {

  @Test
  void restoresTheClientProgressTokenOnClusterProgress() {
    McpProgressRelay relay = new McpProgressRelay();
    McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    String operationId = UUID.randomUUID().toString();
    relay.register(
        operationId,
        exchange,
        Map.of("progressToken", "client-progress-7")
    );

    assertNotNull(operationId);
    relay.onClusterEvent(new McpGatewayClusterEvent(
        McpGatewayClusterEvent.Kind.PROGRESS,
        List.of(),
        Set.of(),
        List.of(),
        operationId,
        4.0,
        10.0,
        "Running",
        Map.of(),
        Instant.now()
    ));

    verify(exchange).progressNotification(argThat(notification ->
        "client-progress-7".equals(notification.progressToken())
            && notification.progress() == 4.0
            && notification.total() == 10.0
    ));
  }
}
