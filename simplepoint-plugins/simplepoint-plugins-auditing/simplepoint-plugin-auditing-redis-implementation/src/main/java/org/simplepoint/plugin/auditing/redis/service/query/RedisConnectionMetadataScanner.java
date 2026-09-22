package org.simplepoint.plugin.auditing.redis.service.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/** SCAN plus bounded pipelines of atomic, single-key, read-only metadata scripts. */
@Component
public final class RedisConnectionMetadataScanner implements RedisMetadataScanner {
  private static final int BATCH_SIZE = 100;
  // No GET/HGETALL/SMEMBERS: values are neither read nor retained in the metadata index.
  private static final byte[] METADATA =
      """
      local t = redis.call('TYPE', KEYS[1]).ok
      if t == 'none' then return {} end
      local ttl = redis.call('TTL', KEYS[1])
      local commands = {string='STRLEN',hash='HLEN',list='LLEN',set='SCARD',zset='ZCARD',stream='XLEN'}
      local size = -1
      if commands[t] then size = redis.call(commands[t], KEYS[1]) end
      return {t,ttl,size}
      """
          .getBytes(StandardCharsets.UTF_8);
  private final RedisConnectionFactory connections;

  /** Uses independent connection wrappers for scan cursors and pipelined metadata reads. */
  public RedisConnectionMetadataScanner(RedisConnectionFactory connections) {
    this.connections = connections;
  }

  @Override
  public void scan(String pattern, Consumer<RedisEntryMetadata> accept, Runnable checkpoint) {
    ScanOptions options = ScanOptions.scanOptions().match(pattern).count(BATCH_SIZE).build();
    try (RedisConnection keys = connections.getConnection();
        RedisConnection metadata = connections.getConnection()) {
      if (keys instanceof RedisClusterConnection cluster) {
        List<RedisClusterNode> primaries = primaries(cluster);
        Map<String, String> before = topology(primaries);
        for (RedisClusterNode node : primaries) {
          checkpoint.run();
          try (Cursor<byte[]> cursor = cluster.scan(node, options)) {
            read(cursor, metadata, accept, checkpoint);
          }
        }
        if (!before.equals(topology(primaries(cluster)))) {
          throw new IllegalStateException("Redis cluster topology changed during scan");
        }
      } else {
        try (Cursor<byte[]> cursor = keys.scan(options)) {
          read(cursor, metadata, accept, checkpoint);
        }
      }
    }
  }

  private void read(
      Cursor<byte[]> cursor,
      RedisConnection metadata,
      Consumer<RedisEntryMetadata> accept,
      Runnable checkpoint) {
    List<byte[]> batch = new ArrayList<>(BATCH_SIZE);
    while (true) {
      checkpoint.run();
      if (!cursor.hasNext()) {
        break;
      }
      byte[] key = cursor.next();
      // The management API addresses UTF-8 keys. Never operate on a lossy decoded binary key.
      if (!Arrays.equals(
          key, new String(key, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalStateException("Redis key cannot be represented as UTF-8");
      }
      batch.add(key);
      if (batch.size() == BATCH_SIZE) {
        readBatch(batch, metadata, accept, checkpoint);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      readBatch(batch, metadata, accept, checkpoint);
    }
  }

  private void readBatch(
      List<byte[]> batch,
      RedisConnection connection,
      Consumer<RedisEntryMetadata> accept,
      Runnable checkpoint) {
    checkpoint.run();
    final Instant observedAt = Instant.now();
    connection.openPipeline();
    for (byte[] key : batch) {
      connection.scriptingCommands().eval(METADATA, ReturnType.MULTI, 1, key);
    }
    List<Object> results = connection.closePipeline();
    checkpoint.run();
    if (results.size() != batch.size()) {
      throw new IllegalStateException("Incomplete Redis metadata pipeline");
    }
    for (int index = 0; index < results.size(); index++) {
      if (!(results.get(index) instanceof List<?> values)) {
        throw new IllegalStateException("Invalid Redis metadata result");
      }
      if (values.isEmpty()) {
        continue; // Expired between SCAN and the atomic metadata observation.
      }
      if (values.size() != 3
          || !(values.get(0) instanceof byte[] typeBytes)
          || !(values.get(1) instanceof Number ttlValue)
          || !(values.get(2) instanceof Number sizeValue)) {
        throw new IllegalStateException("Invalid Redis metadata result");
      }
      RedisEntryType type = RedisEntryType.fromCode(new String(typeBytes, StandardCharsets.UTF_8));
      if (type == null) {
        throw new IllegalStateException("Invalid Redis metadata type");
      }
      long ttl = ttlValue.longValue();
      long size = sizeValue.longValue();
      accept.accept(
          new RedisEntryMetadata(
              new String(batch.get(index), StandardCharsets.UTF_8),
              type,
              ttl < 0 ? null : ttl,
              ttl == -1,
              size < 0 ? null : size,
              type == RedisEntryType.STRING,
              observedAt));
    }
  }

  private List<RedisClusterNode> primaries(RedisClusterConnection connection) {
    List<RedisClusterNode> nodes = new ArrayList<>();
    for (RedisClusterNode node : connection.clusterGetNodes()) {
      if (node.isMaster()) {
        if (!node.isConnected() || node.isMarkedAsFail()) {
          throw new IllegalStateException("Redis cluster primary is unavailable");
        }
        nodes.add(node);
      }
    }
    nodes.sort(Comparator.comparing(RedisClusterNode::getId));
    long slots =
        nodes.stream().flatMap(node -> node.getSlotRange().getSlots().stream()).distinct().count();
    if (slots != 16384) {
      throw new IllegalStateException("Redis cluster slot coverage is incomplete");
    }
    return nodes;
  }

  private Map<String, String> topology(List<RedisClusterNode> nodes) {
    Map<String, String> topology = new TreeMap<>();
    for (RedisClusterNode node : nodes) {
      topology.put(node.getId(), node.getSlotRange().toString());
    }
    return topology;
  }
}
