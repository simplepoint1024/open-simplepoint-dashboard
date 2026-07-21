package org.simplepoint.plugin.ai.knowledge.service.support;

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
   * Parses one uploaded document.
   */
  public ExtractedDocument extract(final MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("上传文档不能为空");
    }
    if (file.getSize() > properties.getMaxUploadBytes()) {
      throw new IllegalArgumentException(
          "文档大小不能超过 " + properties.getMaxUploadBytes() / 1024 / 1024 + " MB"
      );
    }
    String fileName = normalizeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    if (!SUPPORTED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("暂不支持此文档类型: " + extension);
    }
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
    if (file.getContentType() != null) {
      metadata.set("Content-Type", file.getContentType());
    }
    BodyContentHandler handler = new BodyContentHandler(properties.getMaxExtractedCharacters());
    try (InputStream input = file.getInputStream()) {
      new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
      String content = handler.toString().trim();
      if (content.isBlank()) {
        throw new IllegalArgumentException("文档中未提取到可索引文本");
      }
      String mimeType = metadata.get("Content-Type");
      return new ExtractedDocument(
          fileName,
          mimeType == null ? file.getContentType() : mimeType,
          file.getSize(),
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
