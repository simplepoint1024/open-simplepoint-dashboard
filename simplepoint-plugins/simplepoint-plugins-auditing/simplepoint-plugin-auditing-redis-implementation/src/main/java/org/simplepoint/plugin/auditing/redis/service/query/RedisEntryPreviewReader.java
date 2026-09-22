package org.simplepoint.plugin.auditing.redis.service.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Bounds network/application allocations before decoding any key value. */
@Component
public final class RedisEntryPreviewReader {
  public static final int MAX_VALUE_BYTES = 262144;
  private static final int MAX_ITEMS = 100;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final byte[] script;

  /** Loads the reviewed script once; callers cannot supply executable code. */
  public RedisEntryPreviewReader(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
    try (var input = new ClassPathResource("redis/entry-preview.lua").getInputStream()) {
      script = input.readAllBytes();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Redis preview script is unavailable", ex);
    }
  }

  /** The caller decides value visibility before Redis executes a value-reading command. */
  public RedisEntryDetail read(String key, boolean includeValue) {
    List<?> result =
        redis.execute(
            (RedisCallback<List<?>>)
                connection ->
                    connection
                        .scriptingCommands()
                        .eval(
                            script,
                            ReturnType.MULTI,
                            1,
                            key.getBytes(StandardCharsets.UTF_8),
                            bytes(includeValue ? "1" : "0"),
                            bytes(String.valueOf(MAX_VALUE_BYTES)),
                            bytes(String.valueOf(MAX_ITEMS))));
    if (result == null || result.isEmpty()) {
      throw new NoSuchElementException("Redis key does not exist");
    }
    if (result.size() != 6) {
      throw new IllegalStateException("Invalid Redis preview response");
    }
    RedisEntryType type =
        RedisEntryType.fromCode(new String((byte[]) result.get(0), StandardCharsets.UTF_8));
    final long ttl = ((Number) result.get(1)).longValue();
    final long size = ((Number) result.get(2)).longValue();
    boolean sampled = ((Number) result.get(3)).intValue() == 1;
    boolean truncated = ((Number) result.get(4)).intValue() == 1;
    String value = null;
    String encoding = null;
    boolean editable = false;
    if (includeValue) {
      if (type == RedisEntryType.STRING) {
        byte[] raw = (byte[]) result.get(5);
        Object decoded = decode(raw);
        encoding = decoded instanceof String ? "UTF-8" : "base64";
        value = decoded instanceof String text ? text : Base64.getEncoder().encodeToString(raw);
        editable = !truncated && decoded instanceof String;
      } else {
        encoding = "json";
        value =
            json(
                Map.of(
                    "sampled",
                    sampled,
                    "truncated",
                    truncated,
                    "values",
                    decodeTree(result.get(5))));
      }
    }
    RedisEntryDetail detail = new RedisEntryDetail();
    detail.setKey(key);
    detail.setType(type);
    detail.setTtlSeconds(ttl < 0 ? null : ttl);
    detail.setPersistent(ttl == -1);
    detail.setSize(size < 0 ? null : size);
    detail.setEditable(editable);
    detail.setValue(value);
    detail.setSampled(sampled);
    detail.setTruncated(truncated);
    detail.setValueEncoding(encoding);
    return detail;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private Object decodeTree(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(this::decodeTree).toList();
    }
    return value instanceof byte[] raw ? decode(raw) : value;
  }

  private static Object decode(byte[] raw) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(raw))
          .toString();
    } catch (CharacterCodingException ex) {
      return Map.of("encoding", "base64", "value", Base64.getEncoder().encodeToString(raw));
    }
  }

  private String json(Object value) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid Redis preview payload", ex);
    }
  }
}
