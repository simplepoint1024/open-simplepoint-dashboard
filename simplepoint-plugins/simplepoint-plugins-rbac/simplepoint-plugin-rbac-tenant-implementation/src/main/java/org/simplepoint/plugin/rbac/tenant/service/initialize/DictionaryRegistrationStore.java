package org.simplepoint.plugin.rbac.tenant.service.initialize;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;
import org.simplepoint.platform.dictionary.registration.DictionaryInstallation;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException.Code;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationReceipt;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned durable installation authority. Administration is local, never RPC or HTTP. */
@Service
public class DictionaryRegistrationStore {
  private final EntityManager entityManager;
  private final DictionaryCatalogWriteBoundary boundary;

  /** Shares the exact transaction and database boundary used by dictionary mutations. */
  public DictionaryRegistrationStore(
      EntityManager entityManager, DictionaryCatalogWriteBoundary boundary) {
    this.entityManager = entityManager;
    this.boundary = boundary;
  }

  /** Reads scalar deployment state for an installer without changing the authority. */
  public long generation(String module) {
    return current(module).map(Approval::generation).orElse(0L);
  }

  /**
   * Atomically approves a complete installer operation. Retries must match the entire batch and
   * still be current. Existing unmanaged dictionary data requires a separate explicit migration.
   */
  @Transactional(rollbackFor = Exception.class)
  public void install(List<DictionaryInstallation> requested) {
    var commands = validateInstallations(requested);
    UUID operation = commands.getFirst().operationId();
    boundary.enter();
    String fingerprint = operationFingerprint(commands);
    List<?> journal =
        query(
                "select fingerprint from simpoint_dictionary_registration_operations where"
                    + " operation_id=:operation")
            .setParameter("operation", operation.toString())
            .getResultList();
    if (!journal.isEmpty() && !journal.getFirst().equals(fingerprint)) {
      throw error(Code.DEPLOYMENT_CONFLICT);
    }
    for (var command : commands) {
      var current = current(command.moduleCode());
      if (!journal.isEmpty()) {
        if (current.isEmpty()
            || current.get().generation() != command.expectedGeneration() + 1
            || !current.get().operation().equals(operation)) {
          throw error(Code.DEPLOYMENT_CONFLICT);
        }
      } else if (current.map(Approval::generation).orElse(0L) != command.expectedGeneration()
          || !query(
                  "select generation from simpoint_dictionary_registration_approvals where"
                      + " client_id=:client")
              .setParameter("client", command.clientId())
              .setMaxResults(1)
              .getResultList()
              .isEmpty()) {
        throw error(Code.DEPLOYMENT_CONFLICT);
      }
    }
    if (!journal.isEmpty()) {
      return;
    }
    query(
            "insert into simpoint_dictionary_registration_operations(operation_id,fingerprint)"
                + " values (:operation,:fingerprint)")
        .setParameter("operation", operation.toString())
        .setParameter("fingerprint", fingerprint)
        .executeUpdate();
    for (var command : commands) {
      if (command.declaration() != null) {
        claimCodes(command.declaration());
      }
      query(
              """
insert into simpoint_dictionary_registration_approvals
  (module_code,generation,operation_id,client_id,contribution_key,version,fingerprint,applied)
values (:module,:generation,:operation,:client,:key,:version,:fingerprint,:applied)
""")
          .setParameter("module", command.moduleCode())
          .setParameter("generation", command.expectedGeneration() + 1)
          .setParameter("operation", operation.toString())
          .setParameter("client", command.clientId())
          .setParameter(
              "key", command.declaration() == null ? null : command.declaration().contributionKey())
          .setParameter(
              "version", command.declaration() == null ? null : command.declaration().version())
          .setParameter(
              "fingerprint",
              command.declaration() == null
                  ? null
                  : DictionaryFingerprint.of(command.declaration()))
          .setParameter(
              "applied", command.declaration() != null && alreadyApplied(command.declaration()))
          .executeUpdate();
    }
  }

  /** Reserved codes cannot be created by management before their approved registration applies. */
  public void requireCreateAllowed(String code) {
    boundary.enter();
    if (owned(code) && !DictionaryRegistrationWrites.allowsCreation(code)) {
      throw error(Code.OWNERSHIP_CONFLICT);
    }
  }

  /**
   * Registered dictionary identities remain stable; metadata and enabled-state edits are allowed.
   */
  public void requireRenameAllowed(String previous, String next) {
    boundary.enter();
    if (!java.util.Objects.equals(previous, next) && (owned(previous) || owned(next))) {
      throw error(Code.OWNERSHIP_CONFLICT);
    }
  }

  /** Retirement does not release ownership or authorize deletion of historical platform data. */
  public void requireRemovalAllowed(java.util.Collection<String> codes) {
    boundary.enter();
    if (codes.stream().anyMatch(this::owned)) {
      throw error(Code.OWNERSHIP_CONFLICT);
    }
  }

  private boolean owned(String code) {
    try {
      return code != null
          && !query(
                  "select module_code from simpoint_dictionary_registration_owners where"
                      + " dictionary_code=:code")
              .setParameter("code", code)
              .getResultList()
              .isEmpty();
    } catch (jakarta.persistence.PersistenceException failure) {
      var rejection = error(Code.CATALOG_UNAVAILABLE);
      rejection.initCause(failure);
      throw rejection;
    }
  }

  /** Prevents compatibility startup from silently overwriting a managed or retired module. */
  void requireLegacy(DictionaryContribution declaration) {
    boundary.enter();
    if (current(declaration.moduleCode()).isPresent()) {
      throw error(Code.NOT_APPROVED);
    }
    for (var dictionary : declaration.dictionaries()) {
      if (!query(
              "select module_code from simpoint_dictionary_registration_owners where"
                  + " dictionary_code=:code")
          .setParameter("code", dictionary.code())
          .getResultList()
          .isEmpty()) {
        throw error(Code.OWNERSHIP_CONFLICT);
      }
    }
  }

  Approval requireApproved(DictionaryContribution declaration, String authenticatedClient) {
    boundary.enter();
    var approval = current(declaration.moduleCode()).orElseThrow(() -> error(Code.NOT_APPROVED));
    if (approval.client() == null || !approval.client().equals(authenticatedClient)) {
      throw error(Code.ACCESS_DENIED);
    }
    if (!approval.fingerprint().equals(DictionaryFingerprint.of(declaration))) {
      throw error(Code.DECLARATION_MISMATCH);
    }
    for (var dictionary : declaration.dictionaries()) {
      List<?> owners =
          query(
                  "select module_code from simpoint_dictionary_registration_owners where"
                      + " dictionary_code=:code")
              .setParameter("code", dictionary.code())
              .getResultList();
      if (owners.size() != 1 || !owners.getFirst().equals(declaration.moduleCode())) {
        throw error(Code.OWNERSHIP_CONFLICT);
      }
    }
    return approval;
  }

  DictionaryRegistrationReceipt complete(DictionaryContribution declaration, Approval approval) {
    if (!approval.applied()) {
      int changed =
          query(
                  """
                  update simpoint_dictionary_registration_approvals set applied=true
                  where module_code=:module and generation=:generation and applied=false
                  """)
              .setParameter("module", declaration.moduleCode())
              .setParameter("generation", approval.generation())
              .executeUpdate();
      if (changed != 1) {
        throw error(Code.DEPLOYMENT_CONFLICT);
      }
    }
    return new DictionaryRegistrationReceipt(
        declaration.moduleCode(),
        declaration.contributionKey(),
        declaration.version(),
        approval.operation(),
        approval.generation(),
        approval.fingerprint());
  }

  private boolean alreadyApplied(DictionaryContribution declaration) {
    List<?> rows =
        query(
                """
                select fingerprint from simpoint_dictionary_registration_approvals
                where module_code=:module and applied=true order by generation desc
                """)
            .setParameter("module", declaration.moduleCode())
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty()) {
      rows =
          query(
                  "select declaration_fingerprint from simpoint_dictionary_legacy_adoptions where"
                      + " module_code=:module")
              .setParameter("module", declaration.moduleCode())
              .getResultList();
    }
    return !rows.isEmpty() && rows.getFirst().equals(DictionaryFingerprint.of(declaration));
  }

  private void claimCodes(DictionaryContribution declaration) {
    for (var dictionary : declaration.dictionaries()) {
      List<?> owners =
          query(
                  "select module_code from simpoint_dictionary_registration_owners where"
                      + " dictionary_code=:code")
              .setParameter("code", dictionary.code())
              .getResultList();
      if (!owners.isEmpty()) {
        if (!owners.getFirst().equals(declaration.moduleCode())) {
          throw error(Code.OWNERSHIP_CONFLICT);
        }
        continue;
      }
      // Raw identity-owned storage includes soft-deleted rows; request filters cannot hide
      // collisions.
      if (!query("select id from simpoint_saas_dictionaries where code=:code")
              .setParameter("code", dictionary.code())
              .setMaxResults(1)
              .getResultList()
              .isEmpty()
          || !query("select id from simpoint_saas_dictionary_items where dictionary_code=:code")
              .setParameter("code", dictionary.code())
              .setMaxResults(1)
              .getResultList()
              .isEmpty()) {
        throw error(Code.LEGACY_ADOPTION_REQUIRED);
      }
      query(
              "insert into simpoint_dictionary_registration_owners(dictionary_code,module_code)"
                  + " values (:code,:module)")
          .setParameter("code", dictionary.code())
          .setParameter("module", declaration.moduleCode())
          .executeUpdate();
    }
  }

  private Optional<Approval> current(String module) {
    List<?> rows =
        query(
                """
                select generation,operation_id,client_id,fingerprint,applied
                from simpoint_dictionary_registration_approvals
                where module_code=:module order by generation desc
                """)
            .setParameter("module", module)
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = (Object[]) rows.getFirst();
    return Optional.of(
        new Approval(
            ((Number) row[0]).longValue(),
            UUID.fromString((String) row[1]),
            (String) row[2],
            (String) row[3],
            (Boolean) row[4]));
  }

  private jakarta.persistence.Query query(String sql) {
    return entityManager
        .createNativeQuery(sql)
        .setFlushMode(FlushModeType.COMMIT)
        .setHint("jakarta.persistence.query.timeout", 10000);
  }

  /** Reconciles the complete committed approval without applying declarations or advancing state. */
  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public boolean installed(List<DictionaryInstallation> requested) {
    var commands = validateInstallations(requested);
    UUID operation = commands.getFirst().operationId();
    List<?> journal = query(
        "select fingerprint from simpoint_dictionary_registration_operations where operation_id=:operation")
        .setParameter("operation", operation.toString()).getResultList();
    if (journal.isEmpty()) {
      return false;
    }
    if (!operationFingerprint(commands).equals(journal.getFirst())) {
      throw error(Code.DEPLOYMENT_CONFLICT);
    }
    for (var command : commands) {
      var approval = current(command.moduleCode());
      if (approval.isEmpty() || approval.get().generation() != command.expectedGeneration() + 1
          || !approval.get().operation().equals(operation)
          || !java.util.Objects.equals(approval.get().client(), command.clientId())) {
        throw error(Code.DEPLOYMENT_CONFLICT);
      }
    }
    return true;
  }

  private static List<DictionaryInstallation> validateInstallations(List<DictionaryInstallation> requested) {
    if (requested == null
        || requested.isEmpty()
        || requested.size() > 256
        || requested.stream().anyMatch(java.util.Objects::isNull)) {
      throw error(Code.DEPLOYMENT_CONFLICT);
    }
    var commands = List.copyOf(requested);
    UUID operation = commands.getFirst().operationId();
    var modules = new HashSet<String>();
    var clients = new HashSet<String>();
    for (var command : commands) {
      if (!operation.equals(command.operationId())
          || !modules.add(command.moduleCode())
          || (command.clientId() != null && !clients.add(command.clientId()))) {
        throw error(Code.DEPLOYMENT_CONFLICT);
      }
    }
    return commands;
  }

  private static String operationFingerprint(List<DictionaryInstallation> commands) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      output.writeInt(1);
      output.writeInt(commands.size());
      for (var command :
          commands.stream()
              .sorted(Comparator.comparing(DictionaryInstallation::moduleCode))
              .toList()) {
        text(output, command.moduleCode());
        output.writeLong(command.expectedGeneration());
        text(output, command.clientId());
        text(
            output,
            command.declaration() == null ? null : DictionaryFingerprint.of(command.declaration()));
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot fingerprint dictionary installation", impossible);
    }
  }

  private static void text(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static DictionaryRegistrationException error(Code code) {
    return new DictionaryRegistrationException(code);
  }

  record Approval(
      long generation, UUID operation, String client, String fingerprint, boolean applied) {}
}
