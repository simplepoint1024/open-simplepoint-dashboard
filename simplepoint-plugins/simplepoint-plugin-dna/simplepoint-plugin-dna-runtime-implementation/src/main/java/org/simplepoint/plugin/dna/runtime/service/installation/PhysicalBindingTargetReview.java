package org.simplepoint.plugin.dna.runtime.service.installation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalOwnershipInspection;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Offline native coordinates only; never proof of table structure, ownership or activation. */
public record PhysicalBindingTargetReview(
    int version, String controlFingerprint, List<Binding> bindings) {
  /** Rejects duplicate native targets even when their managed datasource aliases differ. */
  public PhysicalBindingTargetReview {
    if (version != 1
        || controlFingerprint == null
        || !controlFingerprint.matches("[0-9a-f]{64}")
        || bindings == null
        || bindings.size() > PhysicalBindingInventory.MAX_BINDINGS) {
      throw new IllegalArgumentException("Invalid physical binding target review");
    }
    var ids = new HashSet<String>();
    var targets = new HashSet<List<String>>();
    var namespaces = new HashMap<List<String>, PhysicalOwnershipInspection>();
    var identities = new HashMap<String, List<String>>();
    for (var binding : bindings) {
      var inspection = binding.namespace();
      var key = nativeKey(inspection.target());
      var object = new java.util.ArrayList<>(key);
      object.add(binding.table());
      if (!ids.add(binding.bindingId()) || !targets.add(object)) {
        throw new IllegalArgumentException("DNA_OWNERSHIP_TARGET_COLLISION");
      }
      var previous = namespaces.putIfAbsent(key, inspection);
      if (previous != null && !previous.equals(inspection)) {
        throw new IllegalArgumentException("DNA_OWNERSHIP_TARGET_NAMESPACE_CHANGED");
      }
      if (inspection.namespaceId() != null) {
        var former = identities.putIfAbsent(inspection.namespaceId().toString(), key);
        if (former != null && !former.equals(key)) {
          throw new IllegalArgumentException("DNA_OWNERSHIP_TARGET_IDENTITY_COLLISION");
        }
      }
    }
    bindings = bindings.stream().sorted(Comparator.comparing(Binding::bindingId)).toList();
  }

  /** Fingerprints native identity, directory state and canonical names with length framing. */
  public String fingerprint() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      value(hash, "dna-physical-binding-target-review-v1");
      value(hash, controlFingerprint);
      for (var binding : bindings) {
        value(hash, binding.bindingId());
        var inspection = binding.namespace();
        var descriptor = inspection.target();
        value(hash, descriptor.dialect());
        value(hash, descriptor.serverIdentity());
        value(hash, descriptor.database());
        value(hash, descriptor.databaseIdentity());
        value(hash, descriptor.namespace());
        value(hash, descriptor.namespaceIdentity());
        value(hash, descriptor.protocolChecksum());
        value(hash, inspection.namespaceId() == null ? "" : inspection.namespaceId().toString());
        value(hash, Boolean.toString(inspection.complete()));
        value(hash, binding.table());
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  /** Exact native namespace, deliberately excluding managed datasource names and credentials. */
  private static List<String> nativeKey(PhysicalNamespaceDescriptor descriptor) {
    return List.of(
        descriptor.dialect(),
        descriptor.serverIdentity(),
        descriptor.databaseIdentity(),
        descriptor.namespaceIdentity());
  }

  private static void value(MessageDigest hash, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    hash.update(bytes);
  }

  /** The same binding identity as the complete domain-owned control inventory. */
  public record Binding(String bindingId, PhysicalOwnershipInspection namespace, String table) {
    /** Bounds persisted review fields without normalizing quoted names. */
    public Binding {
      if (bindingId == null
          || bindingId.isBlank()
          || bindingId.length() > 36
          || table == null
          || table.isBlank()
          || table.length() > 255
          || bindingId.indexOf('\0') >= 0
          || table.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Invalid resolved physical binding");
      }
      Objects.requireNonNull(namespace, "namespace");
    }
  }
}
