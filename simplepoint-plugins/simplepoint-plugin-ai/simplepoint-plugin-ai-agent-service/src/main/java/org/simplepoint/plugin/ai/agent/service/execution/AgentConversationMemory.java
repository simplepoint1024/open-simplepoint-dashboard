package org.simplepoint.plugin.ai.agent.service.execution;

import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;

/**
 * Deterministic, bounded short-term conversation compaction.
 *
 * <p>The active Assistant Tool Call and its Tool Results are retained as one
 * coherent group even when that temporarily exceeds the configured message
 * target.</p>
 */
final class AgentConversationMemory {

  static final String SUMMARY_PREFIX =
      "Agent short-term memory summary:\n";

  private AgentConversationMemory() {
  }

  static Compaction compact(
      final List<Message> source,
      final boolean enabled,
      final int maximumMessages,
      final int maximumSummaryCharacters
  ) {
    List<Message> messages = source == null
        ? List.of() : List.copyOf(source);
    if (!enabled || messages.size() <= Math.max(1, maximumMessages)) {
      return new Compaction(messages, summary(messages), 0);
    }

    String previousSummary = summary(messages);
    List<Message> conversation = withoutSummary(messages);
    int target = Math.max(1, maximumMessages - 1);
    int start = Math.max(0, conversation.size() - target);
    start = coherentToolExchangeStart(conversation, start);
    if (start <= 0) {
      return new Compaction(messages, previousSummary, 0);
    }

    StringBuilder body = new StringBuilder();
    if (previousSummary != null) {
      body.append(previousSummary.substring(SUMMARY_PREFIX.length()).trim());
    }
    for (Message message : conversation.subList(0, start)) {
      if (!body.isEmpty()) {
        body.append('\n');
      }
      body.append(render(message));
    }
    String summary = SUMMARY_PREFIX + boundedTail(
        body.toString(),
        Math.max(256, maximumSummaryCharacters - SUMMARY_PREFIX.length())
    );
    List<Message> compacted = new ArrayList<>();
    compacted.add(summaryMessage(summary));
    compacted.addAll(conversation.subList(start, conversation.size()));
    return new Compaction(
        List.copyOf(compacted),
        summary,
        start
    );
  }

  private static int coherentToolExchangeStart(
      final List<Message> messages,
      final int requestedStart
  ) {
    if (requestedStart <= 0
        || messages.get(requestedStart).role() != MessageRole.TOOL) {
      return requestedStart;
    }
    for (int index = requestedStart - 1; index >= 0; index--) {
      Message candidate = messages.get(index);
      if (candidate.role() == MessageRole.ASSISTANT
          && containsToolCall(candidate)) {
        return index;
      }
    }
    return requestedStart;
  }

  private static boolean containsToolCall(final Message message) {
    return message.content() != null && message.content().stream()
        .anyMatch(block -> block != null
            && block.type() == ContentType.TOOL_CALL);
  }

  private static List<Message> withoutSummary(final List<Message> messages) {
    if (summary(messages) == null) {
      return messages;
    }
    return List.copyOf(messages.subList(1, messages.size()));
  }

  private static String summary(final List<Message> messages) {
    if (messages.isEmpty()) {
      return null;
    }
    Message first = messages.getFirst();
    if (first.role() != MessageRole.SYSTEM || first.content() == null) {
      return null;
    }
    return first.content().stream()
        .filter(block -> block != null && block.type() == ContentType.TEXT)
        .map(ContentBlock::text)
        .filter(text -> text != null && text.startsWith(SUMMARY_PREFIX))
        .findFirst()
        .orElse(null);
  }

  private static Message summaryMessage(final String summary) {
    return new Message(
        MessageRole.SYSTEM,
        List.of(new ContentBlock(
            ContentType.TEXT,
            summary,
            null,
            null,
            null,
            null,
            null
        ))
    );
  }

  private static String render(final Message message) {
    StringBuilder rendered = new StringBuilder();
    rendered.append('[')
        .append(message.role() == null ? "UNKNOWN" : message.role().name())
        .append(']');
    if (message.content() == null) {
      return rendered.toString();
    }
    for (ContentBlock block : message.content()) {
      if (block == null || block.type() == null) {
        continue;
      }
      rendered.append(' ');
      switch (block.type()) {
        case TEXT, REFUSAL -> rendered.append(safe(block.text()));
        case IMAGE_URL -> rendered.append("[image]");
        case TOOL_CALL -> rendered.append("[skill ")
            .append(safe(block.toolName()))
            .append(" call ")
            .append(safe(block.argumentsJson()))
            .append(']');
        case TOOL_RESULT -> rendered.append("[skill ")
            .append(safe(block.toolName()))
            .append(" result ")
            .append(safe(block.text()))
            .append(']');
        default -> rendered.append('[').append(block.type()).append(']');
      }
    }
    return rendered.toString();
  }

  private static String safe(final String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.replaceAll("\\s+", " ").trim();
  }

  private static String boundedTail(
      final String value,
      final int maximumCharacters
  ) {
    int codePoints = value.codePointCount(0, value.length());
    if (codePoints <= maximumCharacters) {
      return value;
    }
    String marker = "[older memory omitted]\n";
    int retained = Math.max(
        1,
        maximumCharacters - marker.codePointCount(0, marker.length())
    );
    int start = value.offsetByCodePoints(0, codePoints - retained);
    return marker + value.substring(start);
  }

  record Compaction(
      List<Message> messages,
      String summary,
      int compactedMessages
  ) {
  }
}
