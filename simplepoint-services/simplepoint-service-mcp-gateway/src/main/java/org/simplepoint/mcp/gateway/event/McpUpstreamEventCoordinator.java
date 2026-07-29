package org.simplepoint.mcp.gateway.event;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.mcp.gateway.publication.McpPublicationControlPlaneClient;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayEventType;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.springframework.stereotype.Component;

/**
 * Validates upstream events through the control plane before cluster broadcast.
 */
@Component
public class McpUpstreamEventCoordinator {

  private final McpPublicationControlPlaneClient controlPlaneClient;

  private final McpGatewayClusterEventBus eventBus;

  /**
   * Creates the upstream event coordinator.
   */
  public McpUpstreamEventCoordinator(
      final McpPublicationControlPlaneClient controlPlaneClient,
      final McpGatewayClusterEventBus eventBus
  ) {
    this.controlPlaneClient = controlPlaneClient;
    this.eventBus = eventBus;
  }

  /**
   * Persists a list or resource change and broadcasts affected publications.
   */
  public void publishChange(
      final String connectionId,
      final Set<McpGatewayEventType> eventTypes,
      final List<String> resourceUris,
      final McpGatewayDiscoveryResult discovery
  ) {
    McpPublicationChangeEvent change = controlPlaneClient.upstreamEvent(
        new McpGatewayUpstreamEvent(
            connectionId,
            eventTypes,
            resourceUris == null ? List.of() : resourceUris,
            discovery
        )
    );
    if (change == null || change.publicationCodes() == null
        || change.publicationCodes().isEmpty()) {
      return;
    }
    eventBus.publish(new McpGatewayClusterEvent(
        McpGatewayClusterEvent.Kind.PUBLICATION_CHANGE,
        change.publicationCodes(),
        change.eventTypes(),
        change.resourceUris(),
        null,
        null,
        null,
        null,
        Map.of(),
        Instant.now()
    ));
  }

  /**
   * Broadcasts an upstream progress notification to the originating replica.
   */
  public void publishProgress(final McpSchema.ProgressNotification notification) {
    if (notification == null || notification.progressToken() == null) {
      return;
    }
    String operationId = notification.progressToken().toString();
    if (!operationId.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )) {
      return;
    }
    eventBus.publish(new McpGatewayClusterEvent(
        McpGatewayClusterEvent.Kind.PROGRESS,
        List.of(),
        Set.of(),
        List.of(),
        operationId,
        notification.progress(),
        notification.total(),
        truncate(notification.message()),
        notification.meta() == null ? Map.of() : notification.meta(),
        Instant.now()
    ));
  }

  private static String truncate(final String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 1024 ? value : value.substring(0, 1024);
  }
}
