package org.simplepoint.plugin.ai.knowledge.api.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;

class AiKnowledgeErrorCodeTest {

  @Test
  void serializesIndexDiagnosticAsStableCode() throws Exception {
    String privateProse = "Embedding endpoint returned private host details";
    AiKnowledgeDocument document = new AiKnowledgeDocument();
    document.setErrorMessage(privateProse);

    String json = new ObjectMapper().writeValueAsString(document);

    assertTrue(json.contains("\"errorMessage\":\"AI_KNOWLEDGE_INDEX_FAILED\""));
    assertFalse(json.contains(privateProse));
  }
}
