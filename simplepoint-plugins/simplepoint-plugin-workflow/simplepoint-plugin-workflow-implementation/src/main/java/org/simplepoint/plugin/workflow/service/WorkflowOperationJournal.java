package org.simplepoint.plugin.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.entity.WorkflowOperationReceiptEntity;

/** Exact-request replay inside the same JPA/Flowable transaction as the engine transition. */
final class WorkflowOperationJournal {
  private final EntityManager entities;
  private final ObjectMapper json;

  WorkflowOperationJournal(EntityManager entities, ObjectMapper json) {
    this.entities = entities;
    this.json =
        json.copy()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  ProcessInstanceSummary execute(
      String tenant,
      String actor,
      String action,
      String target,
      String key,
      Object command,
      Runnable lock,
      Supplier<ProcessInstanceSummary> operation) {
    if (key == null) {
      return operation.get();
    }
    if (key.isBlank() || key.length() > 128 || !key.equals(key.trim())) {
      throw new WorkflowException(Code.VALIDATION, "流程操作幂等键无效");
    }
    String id = hash(encode(List.of(tenant, action, target, key)));
    String fingerprint = hash(encode(canonical(json.valueToTree(command))));
    ProcessInstanceSummary existing = read(id, tenant, actor, fingerprint);
    if (existing != null) {
      return existing;
    }
    // Every writer for this target takes its definition/instance lock. Recheck after waiting.
    lock.run();
    existing = read(id, tenant, actor, fingerprint);
    if (existing != null) {
      return existing;
    }
    ProcessInstanceSummary result = operation.get();
    var receipt = new WorkflowOperationReceiptEntity();
    receipt.setId(id);
    receipt.setTenantId(tenant);
    receipt.setActorId(actor);
    receipt.setRequestFingerprint(fingerprint);
    receipt.setResultJson(encode(result));
    receipt.setCreatedAt(Instant.now());
    entities.persist(receipt);
    entities.flush();
    return result;
  }

  private ProcessInstanceSummary read(String id, String tenant, String actor, String fingerprint) {
    var receipt = entities.find(WorkflowOperationReceiptEntity.class, id);
    if (receipt == null) {
      return null;
    }
    if (!tenant.equals(receipt.getTenantId())
        || !actor.equals(receipt.getActorId())
        || !fingerprint.equals(receipt.getRequestFingerprint())) {
      throw new WorkflowException(Code.CONFLICT, "该幂等键已用于其他流程操作内容或操作者");
    }
    try {
      return json.readValue(receipt.getResultJson(), ProcessInstanceSummary.class);
    } catch (JsonProcessingException failure) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "流程操作回执无法读取", failure);
    }
  }

  private String encode(Object value) {
    try {
      String encoded = json.writeValueAsString(value);
      if (encoded.getBytes(StandardCharsets.UTF_8).length > 160 * 1024) {
        throw new WorkflowException(Code.VALIDATION, "流程操作内容超过容量限制");
      }
      return encoded;
    } catch (JsonProcessingException failure) {
      throw new WorkflowException(Code.VALIDATION, "流程操作内容无法编码", failure);
    }
  }

  private JsonNode canonical(JsonNode value) {
    if (value.isArray()) {
      var result = json.createArrayNode();
      value.forEach(item -> result.add(canonical(item)));
      return result;
    }
    if (!value.isObject()) {
      return value;
    }
    ObjectNode result = json.createObjectNode();
    var names = new TreeSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    names.forEach(name -> result.set(name, canonical(value.get(name))));
    return result;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }
}
