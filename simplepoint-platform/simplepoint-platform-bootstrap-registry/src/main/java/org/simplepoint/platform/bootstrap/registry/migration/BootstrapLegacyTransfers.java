package org.simplepoint.platform.bootstrap.registry.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Command;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Identity;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Receipt;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Snapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, local-only installer transfer. Source history is retained and never replayed. */
@Service
public class BootstrapLegacyTransfers {
  private final EntityManager entityManager;
  private final BootstrapRegistryWriteBoundary boundary;

  /** Uses only bootstrap-owned storage; never reads dictionary or other domain tables. */
  public BootstrapLegacyTransfers(
      EntityManager entityManager, BootstrapRegistryWriteBoundary boundary) {
    this.entityManager = entityManager;
    this.boundary = boundary;
  }

  /** Read-only preview fails on uncertain, conflicting, absent or already modular history. */
  @Transactional(readOnly = true)
  public Snapshot preview(Identity identity) {
    if (existing(identity) != null) {
      throw failure("ALREADY_TRANSFERRED");
    }
    return snapshot(identity, sources(identity));
  }

  /** Atomically records the transfer and, for a module target, preserves its applied revision. */
  @Transactional(rollbackFor = Exception.class)
  public Receipt transfer(Command command) {
    Objects.requireNonNull(command);
    boundary.enter();
    var identity = command.expected().identity();
    var previous = existing(identity);
    if (previous != null) {
      if (!previous.operationId().equals(command.operationId())
          || !previous.targetScope().equals(command.targetScope())
          || !previous.version().equals(command.expected().version())
          || !previous.checksum().equals(command.expected().checksum())
          || !previous.fingerprint().equals(command.expected().fingerprint())) {
        throw failure("CONFLICT");
      }
      return previous;
    }
    var sources = sources(identity);
    if (!snapshot(identity, sources).equals(command.expected())) {
      throw failure("PREVIEW_CHANGED");
    }
    if (command.targetScope().startsWith("module/")) {
      var source =
          sources.stream()
              .max(Comparator.comparing(PlatformContributionRecord::getAppliedAt))
              .orElseThrow();
      var target = new PlatformContributionRecord();
      target.setServiceName(command.targetScope());
      target.setModuleCode(identity.moduleCode());
      target.setContributionType(identity.type());
      target.setContributionKey(identity.key());
      target.setVersion(source.getVersion());
      target.setChecksum(source.getChecksum());
      target.setStatus(source.getStatus());
      target.setError(source.getError());
      target.setStartedAt(source.getStartedAt());
      target.setAppliedAt(source.getAppliedAt());
      target.setUpdatedAt(source.getUpdatedAt());
      entityManager.persist(target);
    }
    bind(
            query(
                """
insert into simpoint_bootstrap_legacy_transfers
  (module_code,contribution_type,contribution_key,operation_id,target_scope,version,checksum,fingerprint)
values (:module,:type,:key,:operation,:target,:version,:checksum,:fingerprint)
"""),
            identity)
        .setParameter("operation", command.operationId().toString())
        .setParameter("target", command.targetScope())
        .setParameter("version", command.expected().version())
        .setParameter("checksum", command.expected().checksum())
        .setParameter("fingerprint", command.expected().fingerprint())
        .executeUpdate();
    entityManager.flush();
    return existing(identity);
  }

  /** Reads durable installer evidence without recreating or changing a transfer. */
  @Transactional(readOnly = true)
  public java.util.Optional<Receipt> findCompletion(Identity identity) {
    return java.util.Optional.ofNullable(existing(identity));
  }

  /** Runtime legacy calls cannot alter a transferred revision or claim a different module. */
  public void requireScope(String scope, String module, String type, String key) {
    var receipt = existing(new Identity(module, type, key));
    if (receipt != null
        && (!receipt.targetScope().startsWith("module/") || !receipt.targetScope().equals(scope))) {
      throw failure("RETIRED_SCOPE");
    }
  }

  private List<PlatformContributionRecord> sources(Identity identity) {
    var rows =
        entityManager
            .createQuery(
                """
                select c from PlatformContributionRecord c where c.moduleCode=:module
                and c.contributionType=:type and c.contributionKey=:key order by c.serviceName
                """,
                PlatformContributionRecord.class)
            .setParameter("module", identity.moduleCode())
            .setParameter("type", identity.type())
            .setParameter("key", identity.key())
            .setMaxResults(65)
            .getResultList();
    if (rows.isEmpty()
        || rows.size() > 64
        || rows.stream().anyMatch(row -> row.getServiceName().startsWith("module/"))) {
      throw failure("SOURCE_CONFLICT");
    }
    return rows;
  }

  private Snapshot snapshot(Identity identity, List<PlatformContributionRecord> rows) {
    var first = rows.getFirst();
    for (var row : rows) {
      if (!PlatformContributionRecord.STATUS_APPLIED.equals(row.getStatus())
          || row.getAppliedAt() == null
          || !Objects.equals(first.getVersion(), row.getVersion())
          || !Objects.equals(first.getChecksum(), row.getChecksum())) {
        throw failure("RECOVERY_REQUIRED");
      }
    }
    return new Snapshot(
        identity,
        first.getVersion(),
        first.getChecksum(),
        fingerprint(rows),
        rows.stream().map(PlatformContributionRecord::getServiceName).toList());
  }

  private Receipt existing(Identity identity) {
    var rows =
        bind(
                query(
                    "select operation_id,target_scope,version,checksum,fingerprint"
                        + " from simpoint_bootstrap_legacy_transfers where module_code=:module"
                        + " and contribution_type=:type and contribution_key=:key"),
                identity)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = (Object[]) rows.getFirst();
    return new Receipt(
        UUID.fromString((String) row[0]),
        (String) row[1],
        identity,
        (String) row[2],
        (String) row[3],
        (String) row[4]);
  }

  private jakarta.persistence.Query query(String sql) {
    return entityManager
        .createNativeQuery(sql)
        .setFlushMode(FlushModeType.COMMIT)
        .setHint("jakarta.persistence.query.timeout", 10000);
  }

  private static jakarta.persistence.Query bind(
      jakarta.persistence.Query query, Identity identity) {
    return query
        .setParameter("module", identity.moduleCode())
        .setParameter("type", identity.type())
        .setParameter("key", identity.key());
  }

  private static String fingerprint(List<PlatformContributionRecord> rows) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeInt(1);
      out.writeInt(rows.size());
      for (var row : rows) {
        for (Object value :
            new Object[] {
              row.getServiceName(),
              row.getModuleCode(),
              row.getContributionType(),
              row.getContributionKey(),
              row.getVersion(),
              row.getChecksum(),
              row.getStatus(),
              row.getError(),
              row.getStartedAt(),
              row.getAppliedAt(),
              row.getUpdatedAt()
            }) {
          if (value == null) {
            out.writeInt(-1);
          } else {
            var text = value.toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(text.length);
            out.write(text);
          }
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot fingerprint bootstrap history", impossible);
    }
  }

  private static IllegalStateException failure(String code) {
    return new IllegalStateException("BOOTSTRAP_TRANSFER_" + code);
  }
}
