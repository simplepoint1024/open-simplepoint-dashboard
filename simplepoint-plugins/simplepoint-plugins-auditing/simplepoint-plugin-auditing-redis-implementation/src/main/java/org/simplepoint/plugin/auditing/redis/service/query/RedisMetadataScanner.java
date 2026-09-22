package org.simplepoint.plugin.auditing.redis.service.query;

import java.util.function.Consumer;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;

/**
 * Streams metadata from the whole selected keyspace without storing values or request authority.
 */
@FunctionalInterface
public interface RedisMetadataScanner {
  /** Checkpoints enforce the caller's cooperative time and cancellation budgets. */
  void scan(String pattern, Consumer<RedisEntryMetadata> accept, Runnable checkpoint);
}
