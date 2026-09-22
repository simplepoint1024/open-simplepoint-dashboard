package org.simplepoint.plugin.auditing.redis.service.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntrySummary;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.springframework.security.access.AccessDeniedException;

/** Keeps legacy summary, detail and command policies restrictive across every entry point. */
public final class RedisEntryFieldPolicy {
  private RedisEntryFieldPolicy() {}

  private static List<String> sources(String field) {
    return switch (field) {
      case "persistent" -> List.of("persistent", "ttlSeconds");
      case "value" -> List.of("value", "valuePreview", "type");
      case "sampled", "truncated", "valueEncoding" ->
          List.of(field, "value", "valuePreview", "type");
      case "editable" ->
          List.of("editable", "type", "value", "valuePreview", "ttlSeconds", "persistent");
      default -> List.of(field);
    };
  }

  private static String access(String field) {
    String result = null;
    for (String source : sources(field)) {
      for (Class<?> owner :
          List.of(RedisEntrySummary.class, RedisEntryDetail.class, RedisValueUpsertCommand.class)) {
        String next = FieldPermissionPolicy.access(owner, source);
        if (FieldPermissionPolicy.hidden(next)) {
          return "HIDDEN";
        }
        if ("MASKED".equals(next)) {
          result = "MASKED";
        } else if ("VISIBLE".equals(next) && result == null) {
          result = "VISIBLE";
        }
      }
    }
    return result;
  }

  /** Checks all canonical and legacy sources before reading a field. */
  public static boolean readable(String field) {
    String access = access(field);
    return !FieldPermissionPolicy.hidden(access) && !"MASKED".equals(access);
  }

  /** Prevents operations against hidden or masked key identities. */
  public static void requireIdentity() {
    if (!readable("key")) {
      throw new AccessDeniedException("Redis key identity is not readable");
    }
  }

  /** Read-only type metadata does not prevent updating a string value of that type. */
  public static boolean writable(String field) {
    List<String> sources =
        "value".equals(field) ? List.of("value", "valuePreview") : sources(field);
    for (String source : sources) {
      for (Class<?> owner :
          List.of(RedisEntrySummary.class, RedisEntryDetail.class, RedisValueUpsertCommand.class)) {
        if (!FieldPermissionPolicy.writable(owner, source)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Requires readable identity/value plus writable value and lifetime. */
  public static boolean canEdit() {
    return readable("key")
        && readable("type")
        && readable("value")
        && writable("value")
        && writable("ttlSeconds")
        && writable("persistent");
  }

  /** A new key additionally requires write access to its identity field. */
  public static void requireWrite(boolean creating) {
    requireIdentity();
    if (!canEdit() || creating && !writable("key")) {
      throw new AccessDeniedException("Redis fields are not writable");
    }
  }

  /** Explicit projection also works with Jackson 3 and never relies on a DTO serializer module. */
  public static Map<String, Object> project(RedisEntryDetail detail) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key", detail.getKey());
    values.put("type", detail.getType());
    values.put("ttlSeconds", detail.getTtlSeconds());
    values.put("persistent", detail.getPersistent());
    values.put("size", detail.getSize());
    values.put("editable", detail.getEditable());
    values.put("value", detail.getValue());
    values.put("sampled", detail.getSampled());
    values.put("truncated", detail.getTruncated());
    values.put("valueEncoding", detail.getValueEncoding());
    Map<String, Object> result = new LinkedHashMap<>();
    values.forEach(
        (field, value) -> {
          String policy = access(field);
          if (!FieldPermissionPolicy.hidden(policy)) {
            result.put(field, "MASKED".equals(policy) ? "***" : value);
          }
        });
    return result;
  }
}
