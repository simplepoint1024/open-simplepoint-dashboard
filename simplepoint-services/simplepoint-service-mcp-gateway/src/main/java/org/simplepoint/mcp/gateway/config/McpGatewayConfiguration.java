package org.simplepoint.mcp.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.simplepoint.mcp.gateway.event.McpGatewayClusterEventBus;
import org.simplepoint.mcp.gateway.publication.McpPublicationControlPlaneClient;
import org.simplepoint.mcp.gateway.publication.McpPublicationDispatcherServlet;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Enables MCP Gateway configuration properties.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayConfiguration {

  /**
   * Registers the northbound Streamable HTTP MCP publication dispatcher.
   */
  @Bean
  public ServletRegistrationBean<McpPublicationDispatcherServlet>
      mcpPublicationServlet(
          final McpPublicationRegistry registry,
          final McpCancellationRegistry cancellationRegistry,
          final McpPublicationControlPlaneClient controlPlaneClient,
          final ObjectMapper objectMapper,
          final McpGatewayProperties properties
  ) {
    ServletRegistrationBean<McpPublicationDispatcherServlet> registration =
        new ServletRegistrationBean<>(
            new McpPublicationDispatcherServlet(
                registry,
                cancellationRegistry,
                controlPlaneClient,
                objectMapper,
                properties
            ),
            "/mcp/*"
        );
    registration.setName("mcpPublicationServlet");
    registration.setAsyncSupported(true);
    registration.setLoadOnStartup(1);
    return registration;
  }

  /**
   * Subscribes each Gateway replica to the shared MCP event channel.
   */
  @Bean
  public RedisMessageListenerContainer mcpGatewayEventListener(
      final RedisConnectionFactory connectionFactory,
      final McpGatewayClusterEventBus eventBus
  ) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(
        (message, pattern) -> eventBus.receive(message.getBody()),
        new ChannelTopic(eventBus.channel())
    );
    return container;
  }
}
