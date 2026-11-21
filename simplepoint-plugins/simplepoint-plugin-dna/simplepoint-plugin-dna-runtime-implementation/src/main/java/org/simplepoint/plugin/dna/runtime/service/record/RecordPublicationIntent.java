package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

/**
 * Frozen server-resolved inputs, without credentials or authorization snapshots. The existing
 * fingerprint service supplies the identity; the storage codec must not recompute it with a
 * different mapper and thereby change the identity of a previously accepted publication.
 */
record RecordPublicationIntent(
    int contractVersion,
    RecordWriteFenceKey key,
    RecordWritePolicyPublicationCommand command,
    String fingerprint,
    String previousFingerprint,
    String actorId,
    String evaluatedAt,
    Map<String, Binding> bindings,
    InstalledCalculation previousCalculation,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Map<String, Resource>> resources,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Set<String>> queryProtection,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Binding> collectionBindings,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Binding> retiringCollectionBindings,
    @JsonInclude(JsonInclude.Include.NON_NULL) Conversion conversion,
    @JsonInclude(JsonInclude.Include.NON_NULL) Rebind rebind) {
  RecordPublicationIntent {
    queryProtection = RecordQueryProtection.immutable(queryProtection);
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(command, "command");
    bindings = Map.copyOf(bindings);
    Instant time = Instant.parse(evaluatedAt);
    if ((contractVersion < 1 || contractVersion > 6)
        || !key.modelId().equals(command.physicalModelId())
        || !checksum(fingerprint)
        || !checksum(previousFingerprint)
        || actorId == null
        || actorId.isBlank()
        || actorId.length() > 64
        || !actorId.equals(actorId.trim())
        || !time.equals(time.truncatedTo(ChronoUnit.MICROS))
        || !time.toString().equals(evaluatedAt)
        || bindings.isEmpty()
        || bindings.size() > 6
        || previousCalculation != null
            && previousCalculation.revision() > command.expectedVersion()) {
      throw new IllegalArgumentException("Invalid frozen publication intent");
    }
    if (contractVersion == 1 && resources != null || contractVersion >= 2 && resources == null) {
      throw new IllegalArgumentException(
          "Publication resources must match the intent contract version");
    }
    if (resources != null) {
      if (!resources.keySet().equals(bindings.keySet())
          || bindings.values().stream().anyMatch(binding -> binding.resourceGeneration() != 0)) {
        throw new IllegalArgumentException(
            "Version two requires explicit resource snapshots and no legacy scalar generation");
      }
      var snapshots = new java.util.LinkedHashMap<String, Map<String, Resource>>();
      resources.forEach(
          (model, items) -> {
            if (items.size() > 1024
                || items.keySet().stream().anyMatch(code -> !identity(code, 160))
                || items.values().stream().map(Resource::bindingId).distinct().count()
                    != items.size()
                || items.values().stream()
                    .anyMatch(
                        resource ->
                            resource.projection() != null
                                && resource.projection().ownerKey() != null
                                && !model.equals(resource.projection().physicalModelId()))) {
              throw new IllegalArgumentException("Invalid frozen publication resource association");
            }
            snapshots.put(model, Map.copyOf(items));
          });
      resources = Map.copyOf(snapshots);
    }
    var root = Objects.requireNonNull(bindings.get(key.modelId()), "root binding");
    if (!root.requestedSchema().equals(command.schemaReference())) {
      throw new IllegalArgumentException("Publication schema differs from the frozen root binding");
    }
    var expected = new HashSet<String>();
    expected.add(key.modelId());
    if (command.calculation() != null) {
      for (var scope : command.calculation().details().values()) {
        expected.add(scope.physicalModelId());
        var binding = bindings.get(scope.physicalModelId());
        if (binding == null || !binding.requestedSchema().equals(scope.schemaReference())) {
          throw new IllegalArgumentException(
              "Publication detail binding is missing or inconsistent");
        }
      }
    }
    if (!expected.equals(bindings.keySet())
        || bindings.values().stream().map(Binding::target).distinct().count() != bindings.size()) {
      throw new IllegalArgumentException(
          "Publication bindings must match the owned models exactly");
    }
    for (var binding : bindings.values()) {
      if (!root.target().dataSourceId().equals(binding.target().dataSourceId())
          || !Objects.equals(root.target().catalogName(), binding.target().catalogName())
          || !Objects.equals(root.target().schemaName(), binding.target().schemaName())) {
        throw new IllegalArgumentException(
            "Publication models must share one physical transaction target");
      }
    }
    collectionBindings =
        RecordPublicationCollectionBindings.validate(
            contractVersion, command, bindings, collectionBindings);
    retiringCollectionBindings =
        RecordPublicationCollectionBindings.validateRetirement(
            contractVersion, command, bindings, collectionBindings, retiringCollectionBindings);
    if ((contractVersion == 5) != (conversion != null)
        || (command.referenceConversion() != null) != (conversion != null)) {
      throw new IllegalArgumentException("Conversion requires version five and frozen storage");
    }
    if (conversion != null) {
      var destination = command.conversionDestination();
      var location = conversion.target().target();
      if (conversion.entrance().projection() == null
          || conversion.entrance().projection().ownerKey() == null
          || !destination
              .targetModelId()
              .equals(conversion.entrance().projection().physicalModelId())
          || !root.target().dataSourceId().equals(location.dataSourceId())
          || !Objects.equals(root.target().catalogName(), location.catalogName())
          || !Objects.equals(root.target().schemaName(), location.schemaName())
          || destination.targetModelId().equals(key.modelId()) != root.target().equals(location)
          || collectionBindings.get(destination.relationId()).target().equals(location)) {
        throw new IllegalArgumentException(
            "Conversion target differs from the frozen owner and edge");
      }
    }
    if ((contractVersion == 6) != (rebind != null)
        || (command.collectionRebind() != null) != (rebind != null)) {
      throw new IllegalArgumentException("Collection replacement requires version six and frozen storage");
    }
    if (rebind != null) {
      var old = command.rebindSource();
      var next = command.rebindDestination();
      var entrance = rebind.entrance().projection();
      if (entrance == null || entrance.ownerKey() == null || !next.targetModelId().equals(entrance.physicalModelId())
          || !sameTarget(root, rebind.sourceTarget(), old.targetModelId().equals(key.modelId()))
          || !sameTarget(root, rebind.destinationTarget(), next.targetModelId().equals(key.modelId()))
          || rebind.sourceTarget().target().equals(rebind.destinationTarget().target())
          || retiringCollectionBindings.get(old.relationId()).target().equals(rebind.sourceTarget().target())
          || collectionBindings.get(next.relationId()).target().equals(rebind.destinationTarget().target())) {
        throw new IllegalArgumentException("Rebind targets differ from the frozen owner or edge storage");
      }
    }
  }

  private static boolean sameTarget(Binding root, Binding target, boolean self) {
    var from = root.target();
    var to = target.target();
    return self == from.equals(to) && from.dataSourceId().equals(to.dataSourceId())
        && Objects.equals(from.catalogName(), to.catalogName())
        && Objects.equals(from.schemaName(), to.schemaName());
  }

  RecordPublicationIntent(
      int contractVersion, RecordWriteFenceKey key, RecordWritePolicyPublicationCommand command,
      String fingerprint, String previousFingerprint, String actorId, String evaluatedAt,
      Map<String, Binding> bindings, InstalledCalculation previousCalculation,
      Map<String, Map<String, Resource>> resources, Map<String, Set<String>> queryProtection,
      Map<String, Binding> collectionBindings, Map<String, Binding> retiringCollectionBindings,
      Conversion conversion) {
    this(contractVersion, key, command, fingerprint, previousFingerprint, actorId, evaluatedAt,
        bindings, previousCalculation, resources, queryProtection, collectionBindings,
        retiringCollectionBindings, conversion, null);
  }

  RecordPublicationIntent(
      int contractVersion,
      RecordWriteFenceKey key,
      RecordWritePolicyPublicationCommand command,
      String fingerprint,
      String previousFingerprint,
      String actorId,
      String evaluatedAt,
      Map<String, Binding> bindings,
      InstalledCalculation previousCalculation,
      Map<String, Map<String, Resource>> resources,
      Map<String, Set<String>> queryProtection,
      Map<String, Binding> collectionBindings,
      Map<String, Binding> retiringCollectionBindings) {
    this(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        resources,
        queryProtection,
        collectionBindings,
        retiringCollectionBindings,
        null);
  }

  /** Version three journals retain their exact active-collection payload. */
  RecordPublicationIntent(
      int contractVersion,
      RecordWriteFenceKey key,
      RecordWritePolicyPublicationCommand command,
      String fingerprint,
      String previousFingerprint,
      String actorId,
      String evaluatedAt,
      Map<String, Binding> bindings,
      InstalledCalculation previousCalculation,
      Map<String, Map<String, Resource>> resources,
      Map<String, Set<String>> queryProtection,
      Map<String, Binding> collectionBindings) {
    this(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        resources,
        queryProtection,
        collectionBindings,
        null);
  }

  /** Prior journals omit collection bindings, including their canonical checksums. */
  RecordPublicationIntent(
      final int contractVersion,
      final RecordWriteFenceKey key,
      final RecordWritePolicyPublicationCommand command,
      final String fingerprint,
      final String previousFingerprint,
      final String actorId,
      final String evaluatedAt,
      final Map<String, Binding> bindings,
      final InstalledCalculation previousCalculation,
      final Map<String, Map<String, Resource>> resources,
      final Map<String, Set<String>> queryProtection) {
    this(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        resources,
        queryProtection,
        null);
  }

  /** Existing journals without copied values retain their exact serialized shape. */
  RecordPublicationIntent(
      final int contractVersion,
      final RecordWriteFenceKey key,
      final RecordWritePolicyPublicationCommand command,
      final String fingerprint,
      final String previousFingerprint,
      final String actorId,
      final String evaluatedAt,
      final Map<String, Binding> bindings,
      final InstalledCalculation previousCalculation,
      final Map<String, Map<String, Resource>> resources) {
    this(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        resources,
        Map.of());
  }

  /** Retains the exact version-one JSON shape and checksums for existing durable journals. */
  RecordPublicationIntent(
      final int contractVersion,
      final RecordWriteFenceKey key,
      final RecordWritePolicyPublicationCommand command,
      final String fingerprint,
      final String previousFingerprint,
      final String actorId,
      final String evaluatedAt,
      final Map<String, Binding> bindings,
      final InstalledCalculation previousCalculation) {
    this(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        null);
  }

  RecordPublicationIntent withQueryProtection(final Map<String, Set<String>> protection) {
    return new RecordPublicationIntent(
        contractVersion,
        key,
        command,
        fingerprint,
        previousFingerprint,
        actorId,
        evaluatedAt,
        bindings,
        previousCalculation,
        resources,
        protection,
        collectionBindings,
        retiringCollectionBindings,
        conversion,
        rebind);
  }

  record Conversion(Binding target, Resource entrance, long sourceCount) {
    Conversion {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(entrance, "entrance");
      if (target.resourceGeneration() != 0 || sourceCount < 0) {
        throw new IllegalArgumentException("Invalid conversion binding or count");
      }
    }
  }

  record Rebind(Binding sourceTarget, Binding destinationTarget, Resource entrance, long edgeCount) {
    Rebind {
      Objects.requireNonNull(sourceTarget, "sourceTarget");
      Objects.requireNonNull(destinationTarget, "destinationTarget");
      Objects.requireNonNull(entrance, "entrance");
      if (sourceTarget.resourceGeneration() != 0 || destinationTarget.resourceGeneration() != 0 || edgeCount < 0) {
        throw new IllegalArgumentException("Invalid frozen rebind binding or count");
      }
    }
  }

  /**
   * Binding identity is server-owned; a missing projection represents a legacy unmanaged
   * association.
   */
  record Resource(String bindingId, Projection projection) {
    Resource {
      if (!identity(bindingId, 36)) {
        throw new IllegalArgumentException("Invalid publication resource binding identity");
      }
    }
  }

  /** Complete resource ownership fence, not a scalar maximum across unrelated pages. */
  record Projection(String id, String ownerKey, long generation, String physicalModelId) {
    Projection {
      if (!identity(id, 36)
          || generation < 0
          || ownerKey == null && (generation != 0 || physicalModelId != null)
          || ownerKey != null
              && (!identity(ownerKey, 160) || generation < 1 || !identity(physicalModelId, 36))) {
        throw new IllegalArgumentException("Invalid publication resource projection");
      }
    }
  }

  private static boolean identity(final String value, final int maximum) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximum
        && value.equals(value.trim());
  }

  Instant clock() {
    return Instant.parse(evaluatedAt);
  }

  static boolean checksum(final String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  /**
   * Versions and placement are checked again before every batch; mutable drafts are never
   * consulted.
   */
  record Binding(
      RecordSchemaReference activeSchema,
      RecordSchemaReference requestedSchema,
      RuntimeMetadataTarget target,
      long resourceGeneration) {
    Binding {
      Objects.requireNonNull(activeSchema, "activeSchema");
      Objects.requireNonNull(requestedSchema, "requestedSchema");
      Objects.requireNonNull(target, "target");
      if (resourceGeneration < 0
          || requestedSchema.version() > activeSchema.version()
          || requestedSchema.version() == activeSchema.version()
              && !requestedSchema.equals(activeSchema)) {
        throw new IllegalArgumentException("Invalid frozen publication binding");
      }
    }
  }

  /**
   * The original installed definition remains immutable; cancellation must preserve this identity.
   */
  record InstalledCalculation(long revision, String checksum) {
    InstalledCalculation {
      if (revision < 1 || !RecordPublicationIntent.checksum(checksum)) {
        throw new IllegalArgumentException("Invalid original calculation identity");
      }
    }
  }
}
