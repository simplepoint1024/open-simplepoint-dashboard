package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;

/** Collection payload only; callers must verify the operation actor, key and fingerprint first. */
final class RecordCollectionReceipt {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxNumberLength(20)
                          .maxStringLength(128)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private RecordCollectionReceipt() {}

  static Entry accepted(RecordCollectionBinding binding, CollectionRecordResult result) {
    return new Entry(
        binding.source().physicalModelId(),
        binding.target().physicalModelId(),
        binding.relationId(),
        result);
  }

  static String encode(Entry entry) {
    var result = entry.result();
    var root = JSON.createObjectNode();
    root.put("contractVersion", result.contractVersion());
    root.put("sourceModelId", entry.sourceModelId());
    root.put("targetModelId", entry.targetModelId());
    root.put("relationId", entry.relationId());
    root.put("sourceRecordId", result.sourceRecordId());
    root.put("sourceVersion", result.sourceVersion());
    root.put("evaluatedAt", result.evaluatedAt().toString());
    var edges = root.putArray("edges");
    result
        .edges()
        .forEach(
            edge ->
                edges
                    .addObject()
                    .put("targetRecordId", edge.targetRecordId())
                    .put("targetVersion", edge.targetVersion())
                    .put("edgeVersion", edge.edgeVersion())
                    .put("linked", edge.linked())
                    .put("changed", edge.changed()));
    String json = root.toString();
    bounded(json);
    return json;
  }

  static Entry decode(String json) {
    bounded(json);
    try {
      var root = JSON.readTree(json);
      RecordOperationRejection.requireSuccess(root);
      shape(
          root,
          Set.of(
              "contractVersion",
              "sourceModelId",
              "targetModelId",
              "relationId",
              "sourceRecordId",
              "sourceVersion",
              "evaluatedAt",
              "edges"));
      var entries = root.get("edges");
      if (!entries.isArray() || entries.isEmpty() || entries.size() > 100) {
        throw invalid(null);
      }
      var edges = new java.util.ArrayList<CollectionRecordResult.Edge>();
      for (var entry : entries) {
        shape(entry, Set.of("targetRecordId", "targetVersion", "edgeVersion", "linked", "changed"));
        edges.add(
            new CollectionRecordResult.Edge(
                text(entry, "targetRecordId"),
                number(entry, "targetVersion"),
                number(entry, "edgeVersion"),
                flag(entry, "linked"),
                flag(entry, "changed")));
      }
      var result =
          new CollectionRecordResult(
              text(root, "contractVersion"),
              text(root, "sourceRecordId"),
              number(root, "sourceVersion"),
              edges,
              Instant.parse(text(root, "evaluatedAt")));
      return new Entry(
          text(root, "sourceModelId"),
          text(root, "targetModelId"),
          text(root, "relationId"),
          result);
    } catch (JsonProcessingException
        | IllegalArgumentException
        | java.time.DateTimeException failure) {
      throw invalid(failure);
    }
  }

  /** Validate the exact accepted command before returning a persisted collection outcome. */
  static void requireCommand(CollectionRecordCommand command, Entry entry) {
    var result = entry.result();
    boolean changed = result.edges().stream().anyMatch(CollectionRecordResult.Edge::changed);
    if (!entry.sourceModelId().equals(command.sourceModelId())
        || !entry.relationId().equals(command.relationId())
        || !result.sourceRecordId().equals(command.sourceRecordId())
        || result.sourceVersion() != command.expectedSourceVersion() + (changed ? 1 : 0)
        || !result.edges().stream()
            .map(CollectionRecordResult.Edge::targetRecordId)
            .toList()
            .equals(command.mutations().stream().map(value -> value.targetRecordId()).toList())) {
      throw invalid(null);
    }
    for (int i = 0; i < command.mutations().size(); i++) {
      var request = command.mutations().get(i);
      var outcome = result.edges().get(i);
      boolean sameRoot =
          entry.sourceModelId().equals(entry.targetModelId())
              && command.sourceRecordId().equals(request.targetRecordId());
      if (sameRoot && !request.expectedTargetVersion().equals(command.expectedSourceVersion())
          || request.expectedEdgeVersion() == -1
              && request.mode() == Mode.UNLINK
              && outcome.changed()
          || outcome.linked() != (request.mode() == Mode.LINK)
          || outcome.edgeVersion() != request.expectedEdgeVersion() + (outcome.changed() ? 1 : 0)
          || outcome.targetVersion()
              != request.expectedTargetVersion()
                  + (outcome.changed() || sameRoot && changed ? 1 : 0)) {
        throw invalid(null);
      }
    }
  }

  private static void bounded(String json) {
    if (json == null
        || json.getBytes(StandardCharsets.UTF_8).length
            > RecordOperationReceiptStore.MAX_PAYLOAD_BYTES) {
      throw invalid(null);
    }
  }

  private static void shape(JsonNode value, Set<String> fields) {
    if (value == null
        || !value.isObject()
        || value.size() != fields.size()
        || fields.stream().anyMatch(field -> !value.has(field))) {
      throw invalid(null);
    }
  }

  private static String text(JsonNode value, String key) {
    if (!value.get(key).isTextual()) {
      throw invalid(null);
    }
    return value.get(key).textValue();
  }

  private static long number(JsonNode value, String key) {
    if (!value.get(key).isIntegralNumber() || !value.get(key).canConvertToLong()) {
      throw invalid(null);
    }
    return value.get(key).longValue();
  }

  private static boolean flag(JsonNode value, String key) {
    if (!value.get(key).isBoolean()) {
      throw invalid(null);
    }
    return value.get(key).booleanValue();
  }

  private static DnaRuntimeException invalid(Throwable failure) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Collection operation receipt is invalid", failure);
  }

  /** Server-resolved identities allow replay after the current relation changes or goes offline. */
  record Entry(
      String sourceModelId,
      String targetModelId,
      String relationId,
      CollectionRecordResult result) {
    Entry {
      for (var identity : new String[] {sourceModelId, targetModelId}) {
        if (identity == null
            || identity.isBlank()
            || identity.length() > 64
            || !identity.equals(identity.trim())) {
          throw invalid(null);
        }
      }
      if (relationId == null
          || !relationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
          || result == null) {
        throw invalid(null);
      }
    }
  }
}
