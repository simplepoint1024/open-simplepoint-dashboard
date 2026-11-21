package org.simplepoint.plugin.workflow.api.model;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Canonical personal-entry links; identifiers are opaque query values, never route fragments. */
public final class WorkflowLinks {
  private WorkflowLinks() {}

  public static String task(String instanceId, String taskId) {
    return "/my/todos?task=" + encode(taskId) + "&instance=" + encode(instanceId);
  }

  public static String matter(String instanceId) {
    return "/my/items?instance=" + encode(instanceId);
  }

  public static String copied(String instanceId) {
    return matter(instanceId) + "&view=copied";
  }

  public static String taskPattern(String instanceId) {
    return "/my/todos?task=%&instance=" + encode(instanceId)
        .replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** Recognizes the persisted workflow notification link without interpreting unrelated URLs. */
  public static String instance(String link) {
    if (link == null) return null;
    String encoded;
    if (link.startsWith("/my/items?instance=")) {
      encoded = link.substring("/my/items?instance=".length()).split("&", 2)[0];
    } else if (link.startsWith("/my/todos?task=") && link.contains("&instance=")) {
      encoded = link.substring(link.indexOf("&instance=") + "&instance=".length());
    } else {
      return null;
    }
    try {
      String id = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
      if (id.isBlank() || id.length() > 64) return null;
      if (link.equals(matter(id)) || link.equals(copied(id))) return id;
      return link.startsWith("/my/todos?task=") && link.endsWith("&instance=" + encode(id)) ? id : null;
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
