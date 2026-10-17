package org.simplepoint.plugin.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

/**
 * The same versioned resource drives the browser's modeling controls and authoritative validation.
 */
record BpmnModelingContract(
    int schemaVersion,
    int maxProcesses,
    int maxParticipants,
    String annotationFormat,
    List<String> prohibitedElements,
    List<String> unsupportedCollaborationElements,
    List<String> diagramTypes,
    List<String> replacementEntries) {
  static final BpmnModelingContract INSTANCE = load();

  private static BpmnModelingContract load() {
    try (var input =
        BpmnModelingContract.class.getResourceAsStream(
            "/META-INF/simplepoint/workflow/modeling.json")) {
      if (input == null) {
        throw new IllegalStateException("Missing workflow modeling contract");
      }
      var contract = new ObjectMapper().readValue(input, BpmnModelingContract.class);
      if (contract.schemaVersion != 1
          || contract.maxProcesses != 1
          || contract.maxParticipants != 1
          || !"text/plain".equals(contract.annotationFormat)) {
        throw new IllegalStateException("Unsupported workflow modeling contract");
      }
      return contract;
    } catch (IOException exception) {
      throw new IllegalStateException("Invalid workflow modeling contract", exception);
    }
  }
}
