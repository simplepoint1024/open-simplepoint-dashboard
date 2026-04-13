package org.simplepoint.plugin.dna.jdbc.driver;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses DNA JDBC driver URLs and connection properties.
 */
final class DnaJdbcUrlParser {

  static final String URL_PREFIX = "jdbc:simplepoint:dna:";

  private DnaJdbcUrlParser() {
  }

  static boolean accepts(final String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  static DnaJdbcModels.ConnectionConfig parse(final String url, final Properties properties) throws SQLException {
    if (!accepts(url)) {
      throw new SQLException("不支持的 DNA JDBC URL: " + url);
    }
    String rawEndpoint = url.substring(URL_PREFIX.length());
    URI endpointUri;
    try {
      endpointUri = URI.create(rawEndpoint.startsWith("//") ? "tcp:" + rawEndpoint : rawEndpoint);
    } catch (IllegalArgumentException ex) {
      throw new SQLException("DNA JDBC URL 格式不正确", ex);
    }
    if (endpointUri.getScheme() == null || endpointUri.getHost() == null) {
      throw new SQLException("DNA JDBC URL 需要包含 //host:port 地址");
    }
    final URI baseUri = normalizeBaseUri(endpointUri);
    Map<String, String> queryValues = parseQuery(endpointUri.getRawQuery());
    String loginSubject = firstNonBlank(
        properties == null ? null : properties.getProperty("user"),
        properties == null ? null : properties.getProperty("username"),
        queryValues.get("user")
    );
    String password = firstNonBlank(
        properties == null ? null : properties.getProperty("password"),
        queryValues.get("password")
    );
    String catalogCode = firstNonBlank(
        properties == null ? null : properties.getProperty("catalogCode"),
        queryValues.get("catalogCode")
    );
    final String tenantId = firstNonBlank(
        properties == null ? null : properties.getProperty("tenantId"),
        queryValues.get("tenantId")
    );
    final String contextId = firstNonBlank(
        properties == null ? null : properties.getProperty("contextId"),
        queryValues.get("contextId")
    );
    final String schema = firstNonBlank(
        properties == null ? null : properties.getProperty("schema"),
        queryValues.get("schema")
    );
    if (isBlank(loginSubject)) {
      throw new SQLException("DNA JDBC 连接缺少 user");
    }
    if (isBlank(password)) {
      throw new SQLException("DNA JDBC 连接缺少 password");
    }
    Properties mergedProperties = mergeUrlProperties(properties, queryValues);
    return new DnaJdbcModels.ConnectionConfig(
        baseUri,
        url,
        loginSubject.trim(),
        password,
        trimToNull(catalogCode),
        trimToNull(tenantId),
        trimToNull(contextId),
        trimToNull(schema),
        mergedProperties
    );
  }

  /**
   * Merges URL query parameters into the connection properties. Properties passed
   * explicitly via {@link java.sql.DriverManager} take precedence over URL values.
   */
  private static Properties mergeUrlProperties(
      final Properties driverProperties,
      final Map<String, String> queryValues
  ) {
    Properties merged = new Properties();
    for (Map.Entry<String, String> entry : queryValues.entrySet()) {
      merged.setProperty(entry.getKey(), entry.getValue());
    }
    if (driverProperties != null) {
      for (String name : driverProperties.stringPropertyNames()) {
        merged.setProperty(name, driverProperties.getProperty(name));
      }
    }
    return merged;
  }

  private static URI normalizeBaseUri(final URI uri) throws SQLException {
    String scheme = uri.getScheme();
    if (!"tcp".equalsIgnoreCase(scheme)) {
      throw new SQLException("DNA JDBC URL 仅支持 tcp 协议");
    }
    String path = uri.getPath();
    try {
      return new URI(
          uri.getScheme(),
          uri.getUserInfo(),
          uri.getHost(),
          uri.getPort(),
          null,
          null,
          null
      );
    } catch (URISyntaxException ex) {
      throw new SQLException("DNA JDBC URL 基础地址不合法", ex);
    }
  }

  private static Map<String, String> parseQuery(final String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    String[] pairs = rawQuery.split("&");
    for (String pair : pairs) {
      if (pair == null || pair.isBlank()) {
        continue;
      }
      int separator = pair.indexOf('=');
      if (separator < 0) {
        values.put(decode(pair), "");
      } else {
        values.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
      }
    }
    return Map.copyOf(values);
  }

  private static String decode(final String value) {
    return URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String firstNonBlank(final String... values) {
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
