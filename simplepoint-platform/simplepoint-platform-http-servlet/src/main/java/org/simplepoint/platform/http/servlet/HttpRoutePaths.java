package org.simplepoint.platform.http.servlet;

import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleHttpPlan;

/** Conservative subtree ownership; method/query conditions never partition a routing boundary. */
final class HttpRoutePaths {
  private HttpRoutePaths() {}

  static String literalRoot(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.length() > 1024
        || path.contains("${")
        || path.contains("#{")
        || path.contains("//")) {
      throw new ModuleCompositionException("INVALID_CONTROLLER_HTTP_ROOT", "Nonliteral HTTP root");
    }
    String[] segments = path.substring(1).split("/");
    for (String segment : segments) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new ModuleCompositionException("INVALID_CONTROLLER_HTTP_ROOT", "Dot segment");
      }
    }
    var root = new StringBuilder();
    for (String segment : segments) {
      if (segment.isEmpty()
          || segment.contains("{")
          || segment.contains("*")
          || segment.contains("?")) {
        break;
      }
      if (!segment.equals(".well-known") && !segment.matches("[a-zA-Z0-9_~][a-zA-Z0-9_.~-]*")) {
        throw new ModuleCompositionException("INVALID_CONTROLLER_HTTP_ROOT", "Nonliteral segment");
      }
      root.append('/').append(segment);
    }
    return root.isEmpty() ? "/" : root.toString();
  }

  static boolean captures(String root, String pattern) {
    if (ModuleHttpPlan.contains(root, pattern)) {
      return true;
    }
    boolean dynamic = pattern.contains("{") || pattern.contains("*") || pattern.contains("?");
    if (!dynamic) {
      return false;
    }
    String literal = literalRoot(pattern);
    return literal.equals("/") || ModuleHttpPlan.contains(literal, root);
  }
}
