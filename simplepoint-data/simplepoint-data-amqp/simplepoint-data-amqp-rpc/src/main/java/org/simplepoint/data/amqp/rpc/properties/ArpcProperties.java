package org.simplepoint.data.amqp.rpc.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import lombok.Data;
import org.simplepoint.api.environment.EnvironmentConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Configuration properties for AMQP RPC (Remote Procedure Call) settings.
 * This class holds properties such as application ID, prefix, exchange name,
 * and queue names, and provides methods to apply these properties to the environment.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = ArpcProperties.PREFIX)
public class ArpcProperties implements EnvironmentConfiguration {

  /**
   * The prefix used for AMQP RPC properties.
   * This prefix is used to group related properties under a common namespace.
   */
  public static final String PREFIX = "simplepoint.amqp.rpc";

  /**
   * The key for the application ID property.
   * This is used to identify the application in AMQP communications.
   */
  public static final String PREFIX_KEY = "${" + PREFIX + ".prefix}";

  /**
   * The key for the application ID property.
   * This is used to identify the application in AMQP communications.
   */
  public static final String REQUEST_QUEUE_NAME_KEY =
      PREFIX_KEY + "${" + PREFIX + ".request-queue-name}";

  private String appId;

  private String prefix;

  private Integer priority;

  private String exchangeName;

  private String requestQueueName;

  private String responseQueueName;

  private Map<String, String> providers = new LinkedHashMap<>();

  /**
   * Constructs the full prefixed key for a given key.
   *
   * @param key the key to prefix
   * @return the full prefixed key
   */
  public String prefix(String key) {
    return prefix + key;
  }

  /**
   * Applies the AMQP RPC properties to the provided properties map.
   * This method iterates through the properties and adds those that start with "amqp.rpc."
   * to the simplepoint properties map.
   *
   * @param properties  the properties to apply
   * @param simplepoint the map to which properties will be added
   * @param environment the configurable environment
   */
  @Override
  public void apply(Properties properties, Map<String, Object> simplepoint,
                    ConfigurableEnvironment environment) {
    properties.forEach((key, value) -> {
      if (String.valueOf(key).startsWith("amqp.rpc.")) {
        simplepoint.put("simplepoint." + key, value);
      }
    });
  }
}
