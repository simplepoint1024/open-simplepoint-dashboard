package org.simplepoint.plugin.dna.core.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.properties.DnaProperties;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores external dialect jars and discovers JDBC dialect implementations from jars or the classpath.
 */
@Component
public class JdbcDialectSourceArtifactManager {

  private final DnaProperties properties;

  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  /**
   * Creates a dialect artifact manager.
   *
   * @param properties DNA properties
   */
  public JdbcDialectSourceArtifactManager(final DnaProperties properties) {
    this.properties = properties;
  }

  /**
   * Downloads a dialect jar for a URL source definition.
   *
   * @param source source definition
   * @return updated source definition
   */
  public JdbcDialectSourceDefinition downloadDraft(final JdbcDialectSourceDefinition source) {
    JdbcDialectSourceDefinition current = requireSource(source);
    URI sourceUri = parseSourceUri(current.getSourceUrl());
    HttpRequest request = HttpRequest.newBuilder(sourceUri).GET().build();
    try {
      HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("方言下载失败，HTTP状态码: " + response.statusCode());
      }
      try (InputStream inputStream = response.body()) {
        Path storedPath = store(current, extractFileName(sourceUri), inputStream);
        current.setLocalJarPath(storedPath.toString());
        current.setLoadedAt(Instant.now());
        current.setLastLoadMessage("加载成功");
        discoverFromJar(current).toString();
        return current;
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("方言下载被中断", ex);
    } catch (Exception ex) {
      throw new IllegalStateException("方言下载失败: " + ex.getMessage(), ex);
    }
  }

  /**
   * Stores an uploaded dialect jar.
   *
   * @param source source definition
   * @param file uploaded jar
   * @return updated source definition
   */
  public JdbcDialectSourceDefinition uploadDraft(
      final JdbcDialectSourceDefinition source,
      final MultipartFile file
  ) {
    JdbcDialectSourceDefinition current = requireSource(source);
    MultipartFile uploadFile = requireFile(file);
    try (InputStream inputStream = uploadFile.getInputStream()) {
      Path storedPath = store(current, resolveUploadedFileName(uploadFile), inputStream);
      current.setLocalJarPath(storedPath.toString());
      current.setLoadedAt(Instant.now());
      current.setLastLoadMessage("加载成功");
      discoverFromJar(current).toString();
      return current;
    } catch (Exception ex) {
      throw new IllegalStateException("方言上传失败: " + ex.getMessage(), ex);
    }
  }

  /**
   * Returns dialect descriptors discovered from the application classpath.
   *
   * @return classpath dialects
   */
  public List<LoadedDialect> discoverClasspathDialects() {
    return discover(
        Thread.currentThread().getContextClassLoader() == null
            ? JdbcDialectSourceArtifactManager.class.getClassLoader()
            : Thread.currentThread().getContextClassLoader(),
        JdbcMetadataModels.SourceType.CLASSPATH,
        null,
        "Classpath",
        null,
        null,
        true,
        "类路径方言",
        null
    );
  }

  /**
   * Returns dialect descriptors discovered from a stored external source.
   *
   * @param source source definition
   * @return discovered dialects
   */
  public List<LoadedDialect> discoverFromJar(final JdbcDialectSourceDefinition source) {
    JdbcDialectSourceDefinition current = requireSource(source);
    Path artifactPath = resolveStoredPath(current);
    try (ApplicationClassLoader classLoader = new ApplicationClassLoader(
        new URL[]{artifactPath.toUri().toURL()},
        resolveParentClassLoader()
    )) {
      return discover(
          classLoader,
          resolveSourceType(current),
          current.getId(),
          current.getName(),
          current.getSourceUrl(),
          artifactPath.toString(),
          !Boolean.FALSE.equals(current.getEnabled()),
          current.getLastLoadMessage(),
          current.getLoadedAt()
      );
    } catch (IOException ex) {
      throw new IllegalStateException("加载方言 JAR 失败: " + artifactPath, ex);
    }
  }

  private List<LoadedDialect> discover(
      final ClassLoader classLoader,
      final JdbcMetadataModels.SourceType sourceType,
      final String sourceId,
      final String sourceName,
      final String sourceUrl,
      final String localJarPath,
      final boolean enabled,
      final String lastLoadMessage,
      final Instant loadedAt
  ) {
    Map<String, LoadedDialect> discovered = new LinkedHashMap<>();
    ServiceLoader<JdbcDatabaseDialect> loader = ServiceLoader.load(JdbcDatabaseDialect.class, classLoader);
    for (ServiceLoader.Provider<JdbcDatabaseDialect> provider : loader.stream().toList()) {
      try {
        JdbcDatabaseDialect dialect = provider.get();
        discovered.putIfAbsent(dialect.code() + "::" + dialect.getClass().getName(), new LoadedDialect(
            dialect,
            sourceType,
            sourceId,
            sourceName,
            sourceUrl,
            localJarPath,
            enabled,
            lastLoadMessage,
            loadedAt
        ));
      } catch (ServiceConfigurationError ex) {
        throw new IllegalStateException("发现方言实现失败: " + ex.getMessage(), ex);
      }
    }
    return List.copyOf(discovered.values());
  }

  private Path store(
      final JdbcDialectSourceDefinition source,
      final String fileName,
      final InputStream inputStream
  ) throws IOException {
    Path target = resolveTarget(source, fileName).toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  private Path resolveTarget(final JdbcDialectSourceDefinition source, final String fileName) {
    String basePath = properties.getDialectStoragePath();
    if (basePath == null || basePath.isBlank()) {
      throw new IllegalStateException("simplepoint.dna.dialect-storage-path 未配置");
    }
    return Path.of(basePath)
        .resolve(sanitizeSegment(source.getName(), "dialect"))
        .resolve(resolveFileName(source, fileName));
  }

  private static String resolveFileName(final JdbcDialectSourceDefinition source, final String fileName) {
    String candidate = trimToNull(fileName);
    if (candidate == null) {
      candidate = sanitizeSegment(source.getName(), "dialect") + ".jar";
    }
    if (!candidate.endsWith(".jar")) {
      candidate = candidate + ".jar";
    }
    return sanitizeSegment(candidate, "dialect.jar");
  }

  private static JdbcDialectSourceDefinition requireSource(final JdbcDialectSourceDefinition source) {
    if (source == null) {
      throw new IllegalArgumentException("方言源不能为空");
    }
    return source;
  }

  private static MultipartFile requireFile(final MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("方言JAR不能为空");
    }
    return file;
  }

  private Path resolveStoredPath(final JdbcDialectSourceDefinition source) {
    String localJarPath = trimToNull(source.getLocalJarPath());
    if (localJarPath == null) {
      throw new IllegalStateException("方言源尚未加载本地 JAR");
    }
    Path path = Path.of(localJarPath).toAbsolutePath().normalize();
    if (!Files.exists(path)) {
      throw new IllegalStateException("方言源文件不存在: " + path);
    }
    return path;
  }

  private static JdbcMetadataModels.SourceType resolveSourceType(final JdbcDialectSourceDefinition source) {
    String value = trimToNull(source.getSourceType());
    if (value == null) {
      return JdbcMetadataModels.SourceType.UPLOAD;
    }
    return JdbcMetadataModels.SourceType.valueOf(value.toUpperCase());
  }

  private static URI parseSourceUri(final String sourceUrl) {
    String normalized = trimToNull(sourceUrl);
    if (normalized == null) {
      throw new IllegalArgumentException("方言远程地址不能为空");
    }
    try {
      URI uri = URI.create(normalized);
      String scheme = uri.getScheme();
      if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException("方言远程地址仅支持 HTTP 或 HTTPS");
      }
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("方言远程地址格式不正确: " + normalized, ex);
    }
  }

  private static String extractFileName(final URI sourceUri) {
    String path = sourceUri.getPath();
    if (path == null || path.isBlank()) {
      return null;
    }
    int index = path.lastIndexOf('/');
    return index >= 0 ? path.substring(index + 1) : path;
  }

  private static String resolveUploadedFileName(final MultipartFile file) {
    String originalFileName = trimToNull(file.getOriginalFilename());
    if (originalFileName != null) {
      return originalFileName;
    }
    return trimToNull(file.getName());
  }

  private static ClassLoader resolveParentClassLoader() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context == null ? JdbcDialectSourceArtifactManager.class.getClassLoader() : context;
  }

  private static String sanitizeSegment(final String value, final String fallback) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return fallback;
    }
    String sanitized = normalized.replaceAll("[^a-zA-Z0-9._-]", "-");
    return sanitized.isBlank() ? fallback : sanitized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Runtime-loaded dialect descriptor.
   *
   * @param dialect dialect implementation
   * @param sourceType source type
   * @param sourceId source id
   * @param sourceName source name
   * @param sourceUrl source URL
   * @param localJarPath local jar path
   * @param enabled enabled flag
   * @param lastLoadMessage last load message
   * @param loadedAt last load time
   */
  public record LoadedDialect(
      JdbcDatabaseDialect dialect,
      JdbcMetadataModels.SourceType sourceType,
      String sourceId,
      String sourceName,
      String sourceUrl,
      String localJarPath,
      boolean enabled,
      String lastLoadMessage,
      Instant loadedAt
  ) {
  }
}
