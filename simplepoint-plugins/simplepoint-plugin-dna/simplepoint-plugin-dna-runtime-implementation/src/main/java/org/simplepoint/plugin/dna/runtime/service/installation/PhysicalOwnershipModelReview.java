package org.simplepoint.plugin.dna.runtime.service.installation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Complete offline immutable definitions and successful publication evidence, never adoption. */
public record PhysicalOwnershipModelReview(
    int version,
    PhysicalBindingInventory inventory,
    List<Definition> definitions,
    List<Publication> publications) {
  public static final int MAX_ENTRIES = 100000;
  public static final int MAX_SCHEMA_BYTES = 1024 * 1024;
  public static final int MAX_TOTAL_SCHEMA_BYTES = 16 * 1024 * 1024;
  private static final PhysicalSchemaCodec SCHEMA =
      new PhysicalSchemaCodec(
          new ObjectMapper()
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));

  /** Verifies complete retained histories and rejects unresolved or inconsistent migrations. */
  public PhysicalOwnershipModelReview {
    if (version != 1
        || inventory == null
        || definitions == null
        || publications == null
        || definitions.size() > MAX_ENTRIES
        || publications.size() > MAX_ENTRIES) {
      throw reject("LIMIT");
    }
    var models = new HashMap<String, PhysicalBindingInventory.Binding>();
    for (var binding : inventory.bindings()) {
      var state = binding.model().status();
      if (state != PhysicalModelStatus.DRAFT
              && state != PhysicalModelStatus.ACTIVE
              && state != PhysicalModelStatus.RETIRED
          || state == PhysicalModelStatus.ACTIVE && binding.model().activeVersion() == null) {
        throw reject("UNSETTLED_MODEL");
      }
      models.put(binding.modelId(), binding);
    }
    var versions = new HashMap<String, Map<Integer, Definition>>();
    var ids = new HashSet<String>();
    int bytes = 0;
    for (var definition : definitions) {
      var binding = models.get(definition.modelId());
      if (binding == null
          || !binding.tenantId().equals(definition.tenantId())
          || definition.number() > binding.model().latestVersion()
          || !ids.add(definition.id())
          || versions
                  .computeIfAbsent(definition.modelId(), ignored -> new HashMap<>())
                  .putIfAbsent(definition.number(), definition)
              != null) {
        throw reject("DEFINITION_IDENTITY");
      }
      bytes += definition.schemaJson().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_TOTAL_SCHEMA_BYTES) {
        throw reject("LIMIT");
      }
    }
    var published = new HashMap<String, Map<Integer, Publication>>();
    ids.clear();
    for (var publication : publications) {
      var binding = models.get(publication.modelId());
      var definition =
          versions.getOrDefault(publication.modelId(), Map.of()).get(publication.targetVersion());
      if (binding == null
          || !binding.tenantId().equals(publication.tenantId())
          || definition == null
          || !definition.checksum().equals(publication.schemaChecksum())
          || binding.model().activeVersion() == null
          || publication.targetVersion() > binding.model().activeVersion()
          || !ids.add(publication.id())
          || published
                  .computeIfAbsent(publication.modelId(), ignored -> new HashMap<>())
                  .putIfAbsent(publication.targetVersion(), publication)
              != null) {
        throw reject("PUBLICATION_IDENTITY");
      }
    }
    for (var binding : inventory.bindings()) {
      var history = versions.getOrDefault(binding.modelId(), Map.of());
      Integer active = binding.model().activeVersion();
      if (binding.cleanupEvidence() != null) {
        if (!history.isEmpty() || published.containsKey(binding.modelId())) {
          throw reject("CLEANUP_HISTORY");
        }
        continue;
      }
      if (history.size() != binding.model().latestVersion()
          || !history.containsKey(binding.model().latestVersion())
          || active != null
              && (!history.containsKey(active)
                  || !published.getOrDefault(binding.modelId(), Map.of()).containsKey(active))) {
        throw reject("INCOMPLETE_HISTORY");
      }
    }
    definitions = definitions.stream().sorted(Comparator.comparing(Definition::id)).toList();
    publications = publications.stream().sorted(Comparator.comparing(Publication::id)).toList();
  }

  /**
   * Hashes complete retained evidence; schema bytes are already authenticated by their checksum.
   */
  public String fingerprint() {
    var values = new StringBuilder("dna-ownership-model-review-v1");
    append(values, inventory.fingerprint());
    for (var d : definitions) {
      for (String value :
          new String[] {
            d.id(),
            d.modelId(),
            d.tenantId(),
            Integer.toString(d.number()),
            d.contractVersion(),
            d.checksum(),
            instant(d.deletedAt())
          }) {
        append(values, value);
      }
    }
    for (var p : publications) {
      for (String value :
          new String[] {
            p.id(),
            p.modelId(),
            p.tenantId(),
            p.fromVersion() == null ? null : p.fromVersion().toString(),
            Integer.toString(p.targetVersion()),
            p.schemaChecksum(),
            p.physicalChecksum(),
            p.dialect(),
            Long.toString(p.revision()),
            instant(p.finishedAt()),
            instant(p.deletedAt())
          }) {
        append(values, value);
      }
    }
    return digest(values.toString());
  }

  /** Returns the already authenticated active definition without looking up mutable metadata. */
  public Definition active(String modelId) {
    var binding =
        inventory.bindings().stream()
            .filter(b -> b.modelId().equals(modelId))
            .findFirst()
            .orElseThrow(() -> reject("UNKNOWN_MODEL"));
    Integer active = binding.model().activeVersion();
    return active == null || binding.cleanupEvidence() != null
        ? null
        : definitions.stream()
            .filter(d -> d.modelId().equals(modelId) && d.number() == active)
            .findFirst()
            .orElseThrow();
  }

  /** Original version identity, authenticated JSON and retained lifecycle metadata. */
  public record Definition(
      String id,
      String modelId,
      String tenantId,
      int number,
      String contractVersion,
      String schemaJson,
      String checksum,
      Instant deletedAt) {
    /** Authenticates original immutable JSON and validates it with the owning domain codec. */
    public Definition {
      identity(id, modelId, tenantId);
      if (number < 1
          || schemaJson == null
          || schemaJson.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES
          || !digest(schemaJson).equals(checksum)) {
        throw reject("SCHEMA_CHECKSUM");
      }
      try {
        if (!SCHEMA.decode(schemaJson).contractVersion().equals(contractVersion)) {
          throw reject("SCHEMA_CONTRACT");
        }
      } catch (RuntimeException invalid) {
        // Do not expose JSON fragments/default values from parser diagnostics in installer logs.
        throw reject("SCHEMA_CONTRACT");
      }
    }

    /** Decodes the immutable definition through the same domain contract as publication. */
    public PhysicalSchemaDefinition schema() {
      return SCHEMA.decode(schemaJson);
    }
  }

  /** Only fully completed migrations may certify an existing physical structure. */
  public record Publication(
      String id,
      String modelId,
      String tenantId,
      Integer fromVersion,
      int targetVersion,
      String schemaChecksum,
      String physicalChecksum,
      String dialect,
      long revision,
      Instant finishedAt,
      Instant deletedAt) {
    /** Validates successful publication coordinates without inventing missing history. */
    public Publication {
      identity(id, modelId, tenantId);
      if (targetVersion < 1
          || fromVersion != null && (fromVersion < 1 || fromVersion >= targetVersion)
          || schemaChecksum == null
          || !schemaChecksum.matches("[0-9a-f]{64}")
          || physicalChecksum == null
          || !physicalChecksum.matches("[0-9a-f]{64}")
          || dialect == null
          || dialect.isBlank()
          || dialect.length() > 64
          || revision < 0
          || finishedAt == null) {
        throw reject("PUBLICATION_STATE");
      }
    }
  }

  static IllegalStateException reject(String code) {
    return new IllegalStateException("DNA_OWNERSHIP_MODEL_" + code);
  }

  private static void identity(String id, String model, String tenant) {
    if (id == null
        || id.isBlank()
        || id.length() > 36
        || model == null
        || model.isBlank()
        || model.length() > 36
        || tenant == null
        || tenant.isBlank()
        || tenant.length() > 255
        || id.indexOf('\0') >= 0
        || model.indexOf('\0') >= 0
        || tenant.indexOf('\0') >= 0) {
      throw reject("IDENTITY");
    }
  }

  private static String instant(Instant value) {
    return value == null ? null : value.toString();
  }

  private static void append(StringBuilder target, String value) {
    target.append(value == null ? -1 : value.length()).append(':');
    if (value != null) {
      target.append(value);
    }
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
