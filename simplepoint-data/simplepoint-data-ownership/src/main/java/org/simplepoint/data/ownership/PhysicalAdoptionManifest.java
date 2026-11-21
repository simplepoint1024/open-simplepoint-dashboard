package org.simplepoint.data.ownership;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reviewed complete retained claims for one inactive namespace; contains no credentials. */
public record PhysicalAdoptionManifest(
    int version,
    PhysicalNamespaceDescriptor target,
    UUID namespaceId,
    UUID operation,
    String reviewFingerprint,
    List<Claim> claims) {
  /** Bounds one atomic target-store adoption and preserves exact reviewed claim identities. */
  public PhysicalAdoptionManifest {
    if (version != 1
        || target == null
        || namespaceId == null
        || operation == null
        || reviewFingerprint == null
        || !reviewFingerprint.matches("[0-9a-f]{64}")
        || claims == null
        || claims.size() > 20000) {
      throw new IllegalArgumentException("Invalid physical adoption manifest");
    }
    var names = new HashSet<String>();
    for (var claim : claims) {
      if (!names.add(claim.table()) || PhysicalNamespace.DIRECTORY.equals(claim.table())) {
        throw new IllegalArgumentException("Duplicate or reserved physical adoption object");
      }
    }
    claims = claims.stream().sorted(Comparator.comparing(Claim::table)).toList();
  }

  /** Binds native coordinates, operation, review evidence and every retained domain owner. */
  public String fingerprint() {
    var value = new StringBuilder("physical-adoption-manifest-v1");
    for (String field :
        new String[] {
          target.dialect(),
          target.serverIdentity(),
          target.database(),
          target.databaseIdentity(),
          target.namespace(),
          target.namespaceIdentity(),
          target.protocolChecksum(),
          namespaceId.toString(),
          operation.toString(),
          reviewFingerprint
        }) {
      append(value, field);
    }
    for (var claim : claims) {
      for (String field :
          new String[] {
            claim.table(),
            claim.owner().moduleId(),
            claim.owner().tenantId(),
            claim.owner().resourceId(),
            Boolean.toString(claim.exists()),
            claim.structureChecksum()
          }) {
        append(value, field);
      }
    }
    return PhysicalNamespace.digest(value.toString());
  }

  private static void append(StringBuilder builder, String value) {
    builder.append(value == null ? -1 : value.length()).append(':');
    if (value != null) {
      builder.append(value);
    }
  }

  /** Both existing tables and reviewed not-yet-created tables retain their domain reservations. */
  public record Claim(
      String table, PhysicalObjectOwner owner, boolean exists, String structureChecksum) {
    /** The domain verifier authenticates structureChecksum on the same locked connection. */
    public Claim {
      table = PhysicalObjectOwner.required(table, 255);
      Objects.requireNonNull(owner, "owner");
      if (structureChecksum == null || !structureChecksum.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid physical adoption structure fingerprint");
      }
    }
  }
}
