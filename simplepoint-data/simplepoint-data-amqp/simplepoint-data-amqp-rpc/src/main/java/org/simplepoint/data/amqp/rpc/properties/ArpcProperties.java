package org.simplepoint.data.amqp.rpc.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for AMQP RPC (Remote Procedure Call) settings.
 * This class holds properties such as application ID, prefix, exchange name,
 * and queue names, and provides methods to apply these properties to the environment.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = ArpcProperties.PREFIX)
public class ArpcProperties {

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

  private Map<String, String> providers = new LinkedHashMap<>();

  private Publisher publisher = new Publisher();

  private DeadLetter deadLetter = new DeadLetter();

  private Listener listener = new Listener();

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
   * Resolves the fully qualified RPC exchange name.
   *
   * @return the prefixed exchange name
   */
  public String exchange() {
    return prefix(exchangeName);
  }

  /**
   * Resolves the logical RPC exchange name without the transport-type suffix.
   * This keeps derived infrastructure names stable when the runtime exchange is
   * switched between variants such as {@code exchange.direct}.
   *
   * @return the prefixed logical exchange name
   */
  public String logicalExchange() {
    return prefix(stripExchangeTypeSuffix(exchangeName));
  }

  /**
   * Resolves the fully qualified RPC request queue name.
   *
   * @return the prefixed request queue name
   */
  public String requestQueue() {
    return prefix(requestQueueName);
  }

  /**
   * Resolves the routing key used for RPC requests.
   *
   * @return the request routing key
   */
  public String requestRoutingKey() {
    return requestQueue();
  }

  /**
   * Resolves the dead-letter exchange name for RPC requests.
   *
   * @return the dead-letter exchange name
   */
  public String deadLetterExchange() {
    return logicalExchange() + deadLetter.getExchangeSuffix();
  }

  /**
   * Resolves the dead-letter queue name for RPC requests.
   *
   * @return the dead-letter queue name
   */
  public String deadLetterQueue() {
    return requestQueue() + deadLetter.getQueueSuffix();
  }

  /**
   * Resolves the dead-letter routing key for RPC requests.
   *
   * @return the dead-letter routing key
   */
  public String deadLetterRoutingKey() {
    return deadLetterQueue();
  }

  private String stripExchangeTypeSuffix(final String name) {
    if (name == null || name.isBlank()) {
      return name;
    }
    for (String suffix : new String[] {".direct", ".topic", ".fanout", ".headers"}) {
      if (name.endsWith(suffix)) {
        return name.substring(0, name.length() - suffix.length());
      }
    }
    return name;
  }

  /**
   * Publisher confirm tuning defaults.
   */
  @Data
  public static class Publisher {

    private long confirmTimeout = 5000L;
  }

  /**
   * Dead-letter queue naming defaults.
   */
  @Data
  public static class DeadLetter {

    private String exchangeSuffix = ".dlx";

    private String queueSuffix = ".dlq";
  }

  /**
   * RPC listener tuning defaults.
   */
  @Data
  public static class Listener {

    private int concurrency = 2;

    private int maxConcurrency = 8;

    private int prefetch = 16;
  }
}
