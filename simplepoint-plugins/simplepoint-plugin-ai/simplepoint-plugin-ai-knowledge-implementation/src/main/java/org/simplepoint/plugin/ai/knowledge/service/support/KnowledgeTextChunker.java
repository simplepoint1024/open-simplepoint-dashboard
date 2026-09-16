package org.simplepoint.plugin.ai.knowledge.service.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Paragraph-aware character chunker with deterministic overlap.
 */
@Component
public class KnowledgeTextChunker {

  /**
   * Splits normalized text while preferring paragraph and sentence boundaries.
   */
  public List<String> split(final String text, final int chunkSize, final int overlap) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    if (chunkSize < 100) {
      throw new IllegalArgumentException("分块大小不能小于 100");
    }
    if (overlap < 0 || overlap >= chunkSize) {
      throw new IllegalArgumentException("分块重叠必须大于等于 0 且小于分块大小");
    }
    String normalized = normalize(text);
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < normalized.length()) {
      int hardEnd = Math.min(start + chunkSize, normalized.length());
      int end = hardEnd == normalized.length()
          ? hardEnd
          : findBoundary(normalized, start, hardEnd, chunkSize);
      if (end <= start) {
        end = hardEnd;
      }
      String chunk = normalized.substring(start, end).trim();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }
      if (end >= normalized.length()) {
        break;
      }
      int next = Math.max(start + 1, end - overlap);
      while (next < end && Character.isWhitespace(normalized.charAt(next))) {
        next++;
      }
      start = next;
    }
    return chunks;
  }

  private static int findBoundary(
      final String text,
      final int start,
      final int hardEnd,
      final int chunkSize
  ) {
    int minimum = start + Math.max(50, chunkSize / 2);
    int paragraph = text.lastIndexOf("\n\n", hardEnd);
    if (paragraph >= minimum) {
      return paragraph + 2;
    }
    for (int index = hardEnd - 1; index >= minimum; index--) {
      char value = text.charAt(index);
      if (value == '。' || value == '！' || value == '？'
          || value == '.' || value == '!' || value == '?' || value == '\n') {
        return index + 1;
      }
    }
    int whitespace = text.lastIndexOf(' ', hardEnd);
    return whitespace >= minimum ? whitespace + 1 : hardEnd;
  }

  private static String normalize(final String text) {
    return text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u0000', ' ')
        .replaceAll("[\\t\\x0B\\f ]+", " ")
        .replaceAll(" *\\n *", "\n")
        .replaceAll("\\n{3,}", "\n\n")
        .trim();
  }
}
