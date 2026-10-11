package org.simplepoint.plugin.workflow.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Canonical personal-entry links; identifiers are opaque query values, never route fragments. */
final class WorkflowLinks {
  private WorkflowLinks() {}

  static String task(String instanceId, String taskId) {
    return "/my/todos?task=" + encode(taskId) + "&instance=" + encode(instanceId);
  }

  static String matter(String instanceId) {
    return "/my/items?instance=" + encode(instanceId);
  }

  static String copied(String instanceId) {
    return matter(instanceId) + "&view=copied";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
