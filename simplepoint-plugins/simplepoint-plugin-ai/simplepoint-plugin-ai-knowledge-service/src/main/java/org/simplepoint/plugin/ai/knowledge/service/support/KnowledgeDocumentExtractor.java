package org.simplepoint.plugin.ai.knowledge.service.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Extracts text from common office, PDF, web and plain-text document formats.
 */
@Component
public class KnowledgeDocumentExtractor {

  private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
      "txt", "md", "markdown", "csv", "json", "xml", "html", "htm",
      "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf",
      "odt", "ods", "odp", "epub"
  );

  private final AiKnowledgeProperties properties;

  /**
   * Creates the extractor.
   */
  public KnowledgeDocumentExtractor(final AiKnowledgeProperties properties) {
    this.properties = properties;
  }

  /**
   * Validates upload metadata without parsing the document body.
   */
  public UploadDescriptor validate(final MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("上传文档不能为空");
    }
    return validate(file.getOriginalFilename(), file.getContentType(), file.getSize());
  }

  private UploadDescriptor validate(
      final String originalFileName,
      final String contentType,
      final long fileSize
  ) {
    if (fileSize <= 0) {
      throw new IllegalArgumentException("上传文档不能为空");
    }
    if (fileSize > properties.getMaxUploadBytes()) {
      throw new IllegalArgumentException(
          "文档大小不能超过 " + properties.getMaxUploadBytes() / 1024 / 1024 + " MB"
      );
    }
    String fileName = normalizeFileName(originalFileName);
    String extension = extension(fileName);
    if (!SUPPORTED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("暂不支持此文档类型: " + extension);
    }
    return new UploadDescriptor(fileName, contentType, fileSize);
  }

  /**
   * Parses one uploaded document. Kept for non-queued callers and focused tests.
   */
  public ExtractedDocument extract(final MultipartFile file) {
    UploadDescriptor descriptor = validate(file);
    try (InputStream input = file.getInputStream()) {
      return extract(descriptor, input);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("文档解析失败: " + ex.getMessage(), ex);
    }
  }

  /**
   * Parses content downloaded by a background worker.
   */
  public ExtractedDocument extract(
      final String fileName,
      final String contentType,
      final byte[] content
  ) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("上传文档不能为空");
    }
    UploadDescriptor descriptor = validate(fileName, contentType, content.length);
    try (InputStream input = new ByteArrayInputStream(content)) {
      return extract(descriptor, input);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("文档解析失败: " + ex.getMessage(), ex);
    }
  }

  private ExtractedDocument extract(
      final UploadDescriptor descriptor,
      final InputStream input
  ) {
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, descriptor.fileName());
    if (descriptor.mimeType() != null) {
      metadata.set("Content-Type", descriptor.mimeType());
    }
    BodyContentHandler handler = new BodyContentHandler(properties.getMaxExtractedCharacters());
    try {
      new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
      String content = handler.toString().trim();
      if (content.isBlank()) {
        throw new IllegalArgumentException("文档中未提取到可索引文本");
      }
      String mimeType = metadata.get("Content-Type");
      return new ExtractedDocument(
          descriptor.fileName(),
          mimeType == null ? descriptor.mimeType() : mimeType,
          descriptor.fileSize(),
          content
      );
    } catch (WriteLimitReachedException ex) {
      throw new IllegalArgumentException("文档文本超过允许的最大字符数", ex);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("文档解析失败: " + ex.getMessage(), ex);
    }
  }

  private static String normalizeFileName(final String value) {
    if (value == null || value.isBlank()) {
      return "document.txt";
    }
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return (separator >= 0 ? normalized.substring(separator + 1) : normalized).trim();
  }

  private static String extension(final String fileName) {
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      throw new IllegalArgumentException("文档文件名缺少受支持的扩展名");
    }
    return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * Validated upload metadata used before the source object is persisted.
   */
  public record UploadDescriptor(
      String fileName,
      String mimeType,
      long fileSize
  ) {
  }

  /**
   * Parsed upload payload.
   */
  public record ExtractedDocument(
      String fileName,
      String mimeType,
      long fileSize,
      String content
  ) {
  }
}
