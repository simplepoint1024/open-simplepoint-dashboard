package org.simplepoint.plugin.dna.core.service.support;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.properties.DnaProperties;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDriverArtifactDownloader;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDriverArtifactStorage;
import org.simplepoint.plugin.dna.core.service.support.JdbcDriverArtifactMetadataResolver.JdbcDriverArtifactMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Coordinates driver downloads and artifact resolution.
 */
@Component
public class JdbcDriverArtifactManager {

  private final JdbcDriverDefinitionRepository repository;

  private final List<JdbcDriverArtifactDownloader> downloaders;

  private final JdbcDriverArtifactStorage storage;

  private final JdbcDriverArtifactMetadataResolver metadataResolver;

  private final DnaProperties properties;

  /**
   * Creates a driver artifact manager.
   *
   * @param repository repository
   * @param downloaders available downloaders
   * @param storage storage strategy
   * @param metadataResolver metadata resolver
   * @param properties DNA properties
   */
  public JdbcDriverArtifactManager(
      final JdbcDriverDefinitionRepository repository,
      final List<JdbcDriverArtifactDownloader> downloaders,
      final JdbcDriverArtifactStorage storage,
      final JdbcDriverArtifactMetadataResolver metadataResolver,
      final DnaProperties properties
  ) {
    this.repository = repository;
    this.downloaders = downloaders == null ? List.of() : List.copyOf(downloaders);
    this.storage = storage;
    this.metadataResolver = metadataResolver;
    this.properties = properties;
  }

  /**
   * Downloads a driver artifact and updates download metadata.
   *
   * @param driver driver definition
   * @return updated driver definition
   */
  public JdbcDriverDefinition download(final JdbcDriverDefinition driver) {
    return downloadInternal(driver, true);
  }

  /**
   * Downloads a driver artifact for a not-yet-persisted definition.
   *
   * @param driver driver definition draft
   * @return updated draft
   */
  public JdbcDriverDefinition downloadDraft(final JdbcDriverDefinition driver) {
    return downloadInternal(driver, false);
  }

  /**
   * Uploads a driver artifact and updates metadata.
   *
   * @param driver driver definition
   * @param file uploaded driver jar
   * @return updated driver definition
   */
  public JdbcDriverDefinition upload(final JdbcDriverDefinition driver, final MultipartFile file) {
    return uploadInternal(driver, file, true);
  }

  /**
   * Uploads a driver artifact for a not-yet-persisted definition.
   *
   * @param driver driver definition draft
   * @param file uploaded driver jar
   * @return updated draft
   */
  public JdbcDriverDefinition uploadDraft(final JdbcDriverDefinition driver, final MultipartFile file) {
    return uploadInternal(driver, file, false);
  }

  /**
   * Resolves driver metadata from an already downloaded local artifact.
   *
   * @param driver driver definition
   * @return updated driver definition
   */
  public JdbcDriverDefinition resolveLocalMetadata(final JdbcDriverDefinition driver) {
    JdbcDriverDefinition current = requireDriver(driver);
    Path artifactPath = resolveLocalArtifact(current);
    JdbcDriverArtifactMetadata metadata = metadataResolver.resolve(artifactPath, current);
    applyMetadata(current, metadata);
    current.setLocalJarPath(artifactPath.toString());
    return current;
  }

  /**
   * Returns whether the driver already has a local artifact on disk.
   *
   * @param driver driver definition
   * @return true when a local artifact exists
   */
  public boolean hasStoredArtifact(final JdbcDriverDefinition driver) {
    if (driver == null) {
      return false;
    }
    String localJarPath = trimToNull(driver.getLocalJarPath());
    if (localJarPath == null) {
      return false;
    }
    try {
      return Files.exists(storage.resolve(localJarPath));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /**
   * Ensures the driver artifact exists locally.
   *
   * @param driver driver definition
   * @return driver definition with local artifact
   */
  public JdbcDriverDefinition ensureDownloaded(final JdbcDriverDefinition driver) {
    if (driver == null) {
      throw new IllegalArgumentException("驱动定义不能为空");
    }
    String localJarPath = trimToNull(driver.getLocalJarPath());
    if (localJarPath != null && Files.exists(storage.resolve(localJarPath))) {
      return driver;
    }
    if (!Boolean.TRUE.equals(properties.getAutoDownloadDriver())) {
      throw new IllegalStateException("驱动尚未下载: " + driver.getCode());
    }
    return download(driver);
  }

  /**
   * Resolves runtime artifact URLs for the given driver.
   *
   * @param driver driver definition
   * @return artifact URLs
   */
  public URL[] resolveArtifactUrls(final JdbcDriverDefinition driver) {
    JdbcDriverDefinition resolved = ensureDownloaded(driver);
    try {
      return new URL[]{storage.resolve(resolved.getLocalJarPath()).toUri().toURL()};
    } catch (Exception ex) {
      throw new IllegalStateException("无法解析驱动文件路径: " + resolved.getLocalJarPath(), ex);
    }
  }

  private JdbcDriverDefinition downloadInternal(
      final JdbcDriverDefinition driver,
      final boolean persist
  ) {
    JdbcDriverDefinition current = resolveCurrent(driver);
    URI downloadUri = parseDownloadUri(current.getDownloadUrl());
    JdbcDriverArtifactDownloader downloader = resolveDownloader(downloadUri);
    try (InputStream inputStream = downloader.download(current, downloadUri)) {
      Path storedPath = storage.store(current, downloadUri, inputStream);
      JdbcDriverArtifactMetadata metadata = metadataResolver.resolve(storedPath, current);
      applyMetadata(current, metadata);
      current.setLocalJarPath(storedPath.toString());
      current.setDownloadedAt(Instant.now());
      current.setLastDownloadMessage("下载成功，已自动识别驱动元数据");
      return persist ? repository.save(current) : current;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      current.setLastDownloadMessage("下载失败: 下载任务被中断");
      if (persist) {
        repository.save(current);
      }
      throw new IllegalStateException("驱动下载被中断", ex);
    } catch (Exception ex) {
      current.setLastDownloadMessage("下载失败: " + ex.getMessage());
      if (persist) {
        repository.save(current);
      }
      throw new IllegalStateException("驱动下载失败: " + ex.getMessage(), ex);
    }
  }

  private JdbcDriverDefinition uploadInternal(
      final JdbcDriverDefinition driver,
      final MultipartFile file,
      final boolean persist
  ) {
    JdbcDriverDefinition current = resolveCurrent(driver);
    MultipartFile uploadFile = requireUploadFile(file);
    try (InputStream inputStream = uploadFile.getInputStream()) {
      Path storedPath = storage.store(current, resolveUploadedFileName(uploadFile), inputStream);
      JdbcDriverArtifactMetadata metadata = metadataResolver.resolve(storedPath, current);
      applyMetadata(current, metadata);
      current.setLocalJarPath(storedPath.toString());
      current.setDownloadedAt(Instant.now());
      current.setLastDownloadMessage("上传成功，已自动识别驱动元数据");
      return persist ? repository.save(current) : current;
    } catch (Exception ex) {
      current.setLastDownloadMessage("上传失败: " + ex.getMessage());
      if (persist) {
        repository.save(current);
      }
      throw new IllegalStateException("驱动上传失败: " + ex.getMessage(), ex);
    }
  }

  private JdbcDriverDefinition resolveCurrent(final JdbcDriverDefinition driver) {
    JdbcDriverDefinition current = requireDriver(driver);
    String id = trimToNull(current.getId());
    return id == null ? current : repository.findActiveById(id).orElse(current);
  }

  private static JdbcDriverDefinition requireDriver(final JdbcDriverDefinition driver) {
    if (driver == null) {
      throw new IllegalArgumentException("驱动定义不能为空");
    }
    return driver;
  }

  private Path resolveLocalArtifact(final JdbcDriverDefinition driver) {
    String localJarPath = trimToNull(driver.getLocalJarPath());
    if (localJarPath == null) {
      throw new IllegalStateException("驱动尚未下载: " + driver.getCode());
    }
    Path artifactPath = storage.resolve(localJarPath);
    if (!Files.exists(artifactPath)) {
      throw new IllegalStateException("驱动文件不存在: " + artifactPath);
    }
    return artifactPath;
  }

  private static void applyMetadata(
      final JdbcDriverDefinition driver,
      final JdbcDriverArtifactMetadata metadata
  ) {
    driver.setDriverClassName(metadata.driverClassName());
    driver.setJdbcUrlPattern(metadata.jdbcUrlPattern());
    driver.setVersion(trimToNull(metadata.version()));
  }

  private JdbcDriverArtifactDownloader resolveDownloader(final URI downloadUri) {
    return downloaders.stream()
        .filter(downloader -> downloader.supports(downloadUri))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("未找到可用的驱动下载器: " + downloadUri));
  }

  private static URI parseDownloadUri(final String downloadUrl) {
    String normalized = trimToNull(downloadUrl);
    if (normalized == null) {
      throw new IllegalArgumentException("驱动下载地址不能为空");
    }
    try {
      return URI.create(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("驱动下载地址格式不正确: " + normalized, ex);
    }
  }

  private static MultipartFile requireUploadFile(final MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("驱动JAR不能为空");
    }
    return file;
  }

  private static String resolveUploadedFileName(final MultipartFile file) {
    String originalFileName = trimToNull(file.getOriginalFilename());
    if (originalFileName != null) {
      return originalFileName;
    }
    return trimToNull(file.getName());
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
