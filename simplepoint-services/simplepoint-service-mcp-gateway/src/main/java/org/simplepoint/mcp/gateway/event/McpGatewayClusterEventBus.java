package org.simplepoint.mcp.gateway.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes protocol events through Redis and re-emits received events locally.
 */
@Component
public class McpGatewayClusterEventBus {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(McpGatewayClusterEventBus.class);

  private final StringRedisTemplate redisTemplate;

  private final ObjectMapper objectMapper;

  private final ApplicationEventPublisher applicationEventPublisher;

  private final McpGatewayProperties properties;

  /**
   * Creates the cluster event bus.
   */
  public McpGatewayClusterEventBus(
      final StringRedisTemplate redisTemplate,
      final ObjectMapper objectMapper,
      final ApplicationEventPublisher applicationEventPublisher,
      final McpGatewayProperties properties
  ) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.applicationEventPublisher = applicationEventPublisher;
    this.properties = properties;
  }

  /**
   * Broadcasts one event to all live Gateway replicas.
   */
  public void publish(final McpGatewayClusterEvent event) {
    if (event == null) {
      return;
    }
    try {
      redisTemplate.convertAndSend(channel(), objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Unable to serialize MCP cluster event", ex);
    } catch (RuntimeException ex) {
      LOGGER.warn("Unable to broadcast MCP cluster event: {}", ex.getMessage());
    }
  }

  /**
   * Decodes one Redis message and publishes it to local Spring listeners.
   */
  public void receive(final byte[] body) {
    if (body == null || body.length == 0) {
      return;
    }
    try {
      McpGatewayClusterEvent event = objectMapper.readValue(
          new String(body, StandardCharsets.UTF_8),
          McpGatewayClusterEvent.class
      );
      applicationEventPublisher.publishEvent(event);
    } catch (JsonProcessingException ex) {
      LOGGER.warn("Ignoring malformed MCP cluster event");
    }
  }

  /**
   * Returns the validated Redis channel name.
   */
  public String channel() {
    String value = properties.getEventChannel();
    if (value == null || value.isBlank() || value.length() > 256
        || value.contains("\r") || value.contains("\n")) {
      throw new IllegalStateException("MCP Gateway event channel is invalid");
    }
    return value.trim();
  }
}
