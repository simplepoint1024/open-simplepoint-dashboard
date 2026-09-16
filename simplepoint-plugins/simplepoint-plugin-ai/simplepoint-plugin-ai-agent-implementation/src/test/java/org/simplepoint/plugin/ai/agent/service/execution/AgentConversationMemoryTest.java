package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;

class AgentConversationMemoryTest {

  @Test
  void compactsOlderMessagesIntoBoundedSystemSummary() {
    List<Message> conversation = List.of(
        text(MessageRole.USER, "request"),
        text(MessageRole.ASSISTANT, "first answer"),
        text(MessageRole.USER, "follow up"),
        text(MessageRole.ASSISTANT, "second answer"),
        text(MessageRole.USER, "latest")
    );

    AgentConversationMemory.Compaction result =
        AgentConversationMemory.compact(
            conversation,
            true,
            3,
            1024
        );

    assertThat(result.messages()).hasSize(3);
    assertThat(result.messages().getFirst().role())
        .isEqualTo(MessageRole.SYSTEM);
    assertThat(result.summary())
        .startsWith(AgentConversationMemory.SUMMARY_PREFIX)
        .contains("request")
        .contains("first answer")
        .contains("follow up");
    assertThat(result.compactedMessages()).isEqualTo(3);
    assertThat(result.messages().get(1).content().getFirst().text())
        .isEqualTo("second answer");
  }

  @Test
  void keepsAssistantToolCallWithAllFollowingToolResults() {
    Message toolCall = new Message(
        MessageRole.ASSISTANT,
        List.of(new ContentBlock(
            ContentType.TOOL_CALL,
            null,
            null,
            null,
            "call-1",
            "skill",
            "{}"
        ))
    );
    List<Message> conversation = List.of(
        text(MessageRole.USER, "request"),
        text(MessageRole.ASSISTANT, "old"),
        toolCall,
        toolResult("call-1", "one"),
        toolResult("call-2", "two")
    );

    AgentConversationMemory.Compaction result =
        AgentConversationMemory.compact(
            conversation,
            true,
            3,
            1024
        );

    assertThat(result.messages()).hasSize(4);
    assertThat(result.messages().get(1)).isEqualTo(toolCall);
    assertThat(result.messages().subList(2, 4))
        .allMatch(message -> message.role() == MessageRole.TOOL);
    assertThat(result.compactedMessages()).isEqualTo(2);
  }

  @Test
  void leavesConversationUnchangedWhenShortTermMemoryIsDisabled() {
    List<Message> conversation = List.of(
        text(MessageRole.USER, "one"),
        text(MessageRole.ASSISTANT, "two"),
        text(MessageRole.USER, "three")
    );

    AgentConversationMemory.Compaction result =
        AgentConversationMemory.compact(
            conversation,
            false,
            1,
            1024
        );

    assertThat(result.messages()).isEqualTo(conversation);
    assertThat(result.compactedMessages()).isZero();
    assertThat(result.summary()).isNull();
  }

  private static Message text(
      final MessageRole role,
      final String value
  ) {
    return new Message(
        role,
        List.of(new ContentBlock(
            ContentType.TEXT,
            value,
            null,
            null,
            null,
            null,
            null
        ))
    );
  }

  private static Message toolResult(
      final String callId,
      final String value
  ) {
    return new Message(
        MessageRole.TOOL,
        List.of(new ContentBlock(
            ContentType.TOOL_RESULT,
            value,
            null,
            "application/json",
            callId,
            "skill",
            null
        ))
    );
  }
}
