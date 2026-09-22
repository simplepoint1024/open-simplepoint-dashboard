package org.simplepoint.plugin.auditing.redis.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode.NodeType;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

class RedisConnectionMetadataScannerTest {
  private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
  private final RedisConnection metadata = mock(RedisConnection.class);
  private final RedisScriptingCommands scripting = mock(RedisScriptingCommands.class);
  private final RedisConnectionMetadataScanner scanner =
      new RedisConnectionMetadataScanner(factory);

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Cursor<byte[]> cursor(List<byte[]> values) {
    Cursor<byte[]> cursor = mock(Cursor.class);
    var iterator = values.iterator();
    when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
    when(cursor.next()).thenAnswer(call -> iterator.next());
    return cursor;
  }

  private RedisConnection standalone(Cursor<byte[]> cursor) {
    RedisConnection keys = mock(RedisConnection.class);
    when(factory.getConnection()).thenReturn(keys, metadata);
    when(keys.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(metadata.scriptingCommands()).thenReturn(scripting);
    return keys;
  }

  @Test
  void boundedPipelinesUseSingleKeyReadOnlyScriptsAndNeverReadValues() {
    List<byte[]> keys = new ArrayList<>();
    List<Object> first = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      keys.add(bytes("key-" + i));
      first.add(List.of(bytes("string"), -1L, (long) i));
    }
    keys.add(bytes("expires-before-metadata"));
    keys.add(bytes("unknown-module-type"));
    var cursor = cursor(keys);
    var connection = standalone(cursor);
    when(metadata.closePipeline())
        .thenReturn(first, List.of(List.of(), List.of(bytes("module"), 60L, -1L)));
    List<RedisEntryMetadata> rows = new ArrayList<>();
    AtomicInteger checkpoints = new AtomicInteger();
    scanner.scan("key-*", rows::add, checkpoints::incrementAndGet);
    assertThat(rows).hasSize(101);
    assertThat(rows.getFirst().persistent()).isTrue();
    assertThat(rows.getFirst().ttlSeconds()).isNull();
    assertThat(rows.getLast().type()).isEqualTo(RedisEntryType.UNKNOWN);
    assertThat(rows.getLast().size()).isNull();
    assertThat(rows.getLast().editable()).isFalse();
    assertThat(checkpoints.get()).isGreaterThan(100);
    verify(metadata, times(2)).openPipeline();
    verify(metadata, times(2)).closePipeline();
    var script = ArgumentCaptor.forClass(byte[].class);
    verify(scripting, times(102))
        .eval(script.capture(), any(ReturnType.class), anyInt(), any(byte[].class));
    assertThat(new String(script.getValue(), StandardCharsets.UTF_8))
        .doesNotContain("'GET'", "'HGETALL'", "'SMEMBERS'", "'SET'")
        .contains("'TYPE'", "'TTL'", "'STRLEN'", "'XLEN'");
    verify(cursor).close();
    verify(connection).close();
    verify(metadata).close();
  }

  @Test
  void invalidBinaryIdentityFailsBeforeSendingItAsADifferentUtf8Key() {
    var cursor = cursor(List.of(new byte[] {(byte) 0xff}));
    var connection = standalone(cursor);
    assertThatThrownBy(() -> scanner.scan("*", row -> {}, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UTF-8");
    verify(metadata, never()).openPipeline();
    verify(connection).close();
    verify(metadata).close();
    verify(cursor).close();
  }

  @Test
  void incompletePipelineFailsInsteadOfOmittingRowsAndPretendingScanIsComplete() {
    var connection = standalone(cursor(List.of(bytes("a"), bytes("b"))));
    when(metadata.closePipeline()).thenReturn(List.of(List.of(bytes("string"), -1L, 1L)));
    assertThatThrownBy(() -> scanner.scan("*", row -> {}, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Incomplete");
    verify(connection).close();
    verify(metadata).close();
  }

  @Test
  void cancellationClosesCursorAndBothConnections() {
    var cursor = cursor(List.of(bytes("a")));
    var connection = standalone(cursor);
    when(metadata.closePipeline()).thenReturn(List.of(List.of(bytes("string"), -1L, 1L)));
    assertThatThrownBy(
            () ->
                scanner.scan(
                    "*",
                    row -> {
                      throw new IllegalStateException("cancelled");
                    },
                    () -> {}))
        .hasMessage("cancelled");
    verify(cursor).close();
    verify(connection).close();
    verify(metadata).close();
  }

  private RedisClusterNode node(String id, NodeType type, int from, int to) {
    return RedisClusterNode.newRedisClusterNode()
        .withId(id)
        .listeningAt(id + ".test", 6379)
        .promotedAs(type)
        .serving(new RedisClusterNode.SlotRange(from, to))
        .linkState(RedisClusterNode.LinkState.CONNECTED)
        .withFlags(Set.of())
        .build();
  }

  @Test
  void clusterScansAllPrimariesOnceNotReplicasAndRejectsTopologyChanges() {
    var cluster = mock(RedisClusterConnection.class);
    when(factory.getConnection()).thenReturn(cluster, metadata);
    when(metadata.scriptingCommands()).thenReturn(scripting);
    var first = node("a", NodeType.MASTER, 0, 8191);
    var second = node("b", NodeType.MASTER, 8192, 16383);
    var replica = node("c", NodeType.REPLICA, 0, 8191);
    when(cluster.clusterGetNodes()).thenReturn(List.of(first, second, replica));
    when(cluster.scan(any(RedisClusterNode.class), any(ScanOptions.class)))
        .thenAnswer(
            call -> cursor(List.of(bytes(call.getArgument(0, RedisClusterNode.class).getId()))));
    when(metadata.closePipeline()).thenReturn(List.of(List.of(bytes("string"), -1L, 1L)));
    List<RedisEntryMetadata> rows = new ArrayList<>();
    scanner.scan("*", rows::add, () -> {});
    assertThat(rows).extracting(RedisEntryMetadata::key).containsExactly("a", "b");
    verify(cluster, never()).scan(org.mockito.ArgumentMatchers.eq(replica), any(ScanOptions.class));
    verify(cluster, times(2)).clusterGetNodes();
    // A new owner of the same slots is not accepted as a complete scan of the old topology.
    when(factory.getConnection()).thenReturn(cluster, metadata);
    when(cluster.clusterGetNodes())
        .thenReturn(
            List.of(first, second),
            List.of(first, node("replacement", NodeType.MASTER, 8192, 16383)));
    assertThatThrownBy(() -> scanner.scan("*", row -> {}, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("topology changed");
  }

  @Test
  void unavailableOrIncompleteClusterCannotBecomeASuccessfulPartialResult() {
    var cluster = mock(RedisClusterConnection.class);
    when(factory.getConnection()).thenReturn(cluster, metadata);
    when(cluster.clusterGetNodes()).thenReturn(List.of(node("a", NodeType.MASTER, 0, 8191)));
    assertThatThrownBy(() -> scanner.scan("*", row -> {}, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coverage");
    verify(cluster, never()).scan(any(RedisClusterNode.class), any(ScanOptions.class));
    verify(cluster).close();
    verify(metadata).close();
  }
}
