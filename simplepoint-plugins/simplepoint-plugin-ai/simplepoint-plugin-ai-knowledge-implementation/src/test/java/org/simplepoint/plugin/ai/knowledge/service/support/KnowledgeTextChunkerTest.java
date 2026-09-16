package org.simplepoint.plugin.ai.knowledge.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeTextChunkerTest {

  private final KnowledgeTextChunker chunker = new KnowledgeTextChunker();

  @Test
  void splitsAtNaturalBoundariesAndKeepsOverlap() {
    String text = "第一段内容用于验证自然边界。\n\n"
        + "第二段包含更多字符，用于确保内容会被拆分成多个可检索分块。"
        + "第三句继续补充测试文本并验证重叠行为。"
        + "第四句让总长度稳定超过单个分块限制。"
        + "第五句继续增加长度并保持完整的句子边界。"
        + "第六句用于确认最后一个分块仍能保留文档结尾。";

    List<String> chunks = chunker.split(text, 100, 20);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> !chunk.isBlank() && chunk.length() <= 100);
    assertThat(chunks.getFirst()).contains("第一段内容");
    assertThat(chunks.getLast()).contains("第六句");
  }

  @Test
  void rejectsOverlapThatIsNotSmallerThanChunkSize() {
    assertThatThrownBy(() -> chunker.split("有效内容", 100, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重叠");
  }
}
