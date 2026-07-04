package org.simplepoint.plugin.rbac.resource.service.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds plugin version metadata to frontend remote entries for browser cache isolation.
 */
public final class RemoteEntryVersioner {

  static final String PLUGIN_PARAM = "_sp_plugin";
  static final String VERSION_PARAM = "_sp_v";
  private static final int SHA_TOKEN_LENGTH = 12;

  /**
   * Adds cache-busting query parameters to a plugin remote entry.
   *
   * @param entry                canonical remote entry
   * @param pluginId             owning plugin id
   * @param pluginVersion        owning plugin version
   * @param remoteVersion        frontend remote version
   * @param pluginArtifactSha256 plugin artifact SHA-256 digest
   * @return versioned remote entry
   */
  public String versioned(
      String entry,
      String pluginId,
      String pluginVersion,
      String remoteVersion,
      String pluginArtifactSha256
  ) {
    String canonicalEntry = trimToNull(entry);
    String owner = trimToNull(pluginId);
    String token = versionToken(pluginVersion, remoteVersion, pluginArtifactSha256);
    if (canonicalEntry == null || owner == null || token == null) {
      return canonicalEntry;
    }
    String fragment = "";
    String entryWithoutFragment = canonicalEntry;
    int fragmentIndex = canonicalEntry.indexOf('#');
    if (fragmentIndex >= 0) {
      fragment = canonicalEntry.substring(fragmentIndex);
      entryWithoutFragment = canonicalEntry.substring(0, fragmentIndex);
    }

    String path = entryWithoutFragment;
    List<String> queryParts = new ArrayList<>();
    int queryIndex = entryWithoutFragment.indexOf('?');
    if (queryIndex >= 0) {
      path = entryWithoutFragment.substring(0, queryIndex);
      String query = entryWithoutFragment.substring(queryIndex + 1);
      for (String part : query.split("&")) {
        if (part.isBlank() || isManagedParam(part)) {
          continue;
        }
        queryParts.add(part);
      }
    }
    queryParts.add(PLUGIN_PARAM + "=" + encode(owner));
    queryParts.add(VERSION_PARAM + "=" + encode(token));
    return path + "?" + String.join("&", queryParts) + fragment;
  }

  private boolean isManagedParam(String queryPart) {
    return queryPart.startsWith(PLUGIN_PARAM + "=") || queryPart.startsWith(VERSION_PARAM + "=");
  }

  private String versionToken(String pluginVersion, String remoteVersion, String pluginArtifactSha256) {
    List<String> values = new ArrayList<>();
    addIfPresent(values, "remote", remoteVersion);
    addIfPresent(values, "plugin", pluginVersion);
    String sha256 = trimToNull(pluginArtifactSha256);
    if (sha256 != null) {
      values.add("sha:" + sha256.substring(0, Math.min(SHA_TOKEN_LENGTH, sha256.length())));
    }
    if (values.isEmpty()) {
      return null;
    }
    return String.join(":", values);
  }

  private void addIfPresent(List<String> values, String label, String value) {
    String text = trimToNull(value);
    if (text != null) {
      values.add(label + ":" + text);
    }
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
