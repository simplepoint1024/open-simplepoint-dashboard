package org.simplepoint.plugin.dna.runtime.service.record;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Framed, domain-separated UUIDv8 identity, unchanged by unlink/relink, labels or table renames.
 */
record RecordCollectionEdgeKey(String id, String tenantId, String sourceId, String targetId) {
  static RecordCollectionEdgeKey of(
      RecordCollectionBinding binding, String tenant, String sourceId, String targetId) {
    if (tenant == null || tenant.isBlank() || tenant.length() > 255) {
      throw new IllegalArgumentException("A bounded tenant identity is required");
    }
    sourceId = canonical(sourceId);
    targetId = canonical(targetId);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value :
          new String[] {
            "simplepoint.collection.edge.v1",
            tenant,
            binding.edge().physicalModelId(),
            binding.relationId(),
            binding.source().physicalModelId(),
            sourceId,
            binding.target().physicalModelId(),
            targetId
          }) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      byte[] bytes = digest.digest();
      bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80);
      bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
      var buffer = ByteBuffer.wrap(bytes);
      return new RecordCollectionEdgeKey(
          new UUID(buffer.getLong(), buffer.getLong()).toString(), tenant, sourceId, targetId);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  private static String canonical(String value) {
    String canonical = UUID.fromString(value).toString();
    if (!canonical.equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("A canonical saved record identity is required");
    }
    return canonical;
  }
}
