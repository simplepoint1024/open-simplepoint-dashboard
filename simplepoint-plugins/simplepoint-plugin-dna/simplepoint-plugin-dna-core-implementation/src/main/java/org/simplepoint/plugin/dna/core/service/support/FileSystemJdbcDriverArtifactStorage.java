package org.simplepoint.plugin.dna.core.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.properties.DnaProperties;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDriverArtifactStorage;
import org.springframework.stereotype.Component;

/**
 * Filesystem-based storage for downloaded or uploaded JDBC driver artifacts.
 */
@Component
public class FileSystemJdbcDriverArtifactStorage implements JdbcDriverArtifactStorage {

  private final DnaProperties properties;

  /**
   * Creates a filesystem-based storage implementation.
   *
   * @param properties DNA properties
   */
  public FileSystemJdbcDriverArtifactStorage(final DnaProperties properties) {
    this.properties = properties;
  }

  /** {@inheritDoc} */
  @Override
  public Path store(
      final JdbcDriverDefinition driver,
      final URI downloadUri,
      final InputStream inputStream
  ) throws IOException {
    return store(driver, extractSourceName(downloadUri), inputStream);
  }

  /** {@inheritDoc} */
  @Override
  public Path store(
      final JdbcDriverDefinition driver,
      final String sourceName,
      final InputStream inputStream
  ) throws IOException {
    Path target = resolveTarget(driver, sourceName).toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /** {@inheritDoc} */
  @Override
  public Path resolve(final String location) {
    if (location == null || location.isBlank()) {
      throw new IllegalArgumentException("驱动文件路径不能为空");
    }
    return Path.of(location).toAbsolutePath().normalize();
  }

  private Path resolveTarget(final JdbcDriverDefinition driver, final String sourceName) {
    String basePath = properties.getDriverStoragePath();
    if (basePath == null || basePath.isBlank()) {
      throw new IllegalStateException("simplepoint.dna.driver-storage-path 未配置");
    }
    return Path.of(basePath)
        .resolve(sanitizeSegment(driver.getCode(), "driver"))
        .resolve(resolveFileName(driver, sourceName));
  }

  private static String resolveFileName(final JdbcDriverDefinition driver, final String sourceName) {
    String candidate = trimToNull(sourceName);
    if (candidate == null || candidate.isBlank()) {
      String version = driver.getVersion() == null || driver.getVersion().isBlank() ? "latest" : driver.getVersion().trim();
      candidate = sanitizeSegment(driver.getCode(), "driver") + '-' + sanitizeSegment(version, "latest") + ".jar";
    }
    if (!candidate.endsWith(".jar")) {
      candidate = candidate + ".jar";
    }
    return sanitizeSegment(candidate, "driver.jar");
  }

  private static String extractSourceName(final URI downloadUri) {
    if (downloadUri == null) {
      return null;
    }
    String path = downloadUri.getPath();
    if (path == null || path.isBlank()) {
      return null;
    }
    int index = path.lastIndexOf('/');
    return index >= 0 ? path.substring(index + 1) : path;
  }

  private static String sanitizeSegment(final String value, final String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String sanitized = value.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
    return sanitized.isBlank() ? fallback : sanitized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
