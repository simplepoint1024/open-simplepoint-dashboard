package org.simplepoint.plugin.rbac.resource.service.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.plugin.rbac.resource.api.deployment.ResourceDeploymentChange;
import org.simplepoint.plugin.rbac.resource.api.deployment.ResourceDeploymentState;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.resource.ResourceRegistrationException.Code;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned writer generations. Administration is local and never exported as RPC or HTTP. */
@Service
public class ResourceRegistrationDeployments {
  private static final int MAX_CHANGES = 256;
  private final EntityManager entityManager;
  private final ResourceCatalogWriteBoundary boundary;
  private final ResourceRegistrationCallerPolicy legacyPolicy;

  /** Uses the same database and write boundary as resource snapshots. */
  public ResourceRegistrationDeployments(
      EntityManager entityManager,
      ResourceCatalogWriteBoundary boundary,
      ResourceRegistrationCallerPolicy legacyPolicy) {
    this.entityManager = entityManager;
    this.boundary = boundary;
    this.legacyPolicy = legacyPolicy;
  }

  /** Checks verified caller identity inside the transaction that will apply its snapshot. */
  public void requireRegistration(String owner, String verifiedClient) {
    boundary.enter();
    var state = current(owner);
    if (state.isEmpty()) {
      legacyPolicy.requireConfiguredCaller(owner, verifiedClient);
    } else if (!state.get().active() || !state.get().clientId().equals(verifiedClient)) {
      throw new ResourceRegistrationException(Code.GENERATION_REJECTED);
    }
  }

  /**
   * Local plugin installation cannot re-enter compatibility mode for a managed or retired owner.
   */
  public void requireUnmanaged(String owner) {
    boundary.enter();
    if (current(owner).isPresent()) {
      throw new ResourceRegistrationException(Code.GENERATION_REJECTED);
    }
  }

  /** Reads scalar state without reusing managed JPA entities or mutating a generation. */
  public Optional<ResourceDeploymentState> current(String owner) {
    requireText(owner, 128);
    try {
      List<?> rows =
          entityManager
              .createNativeQuery(
                  """
                  select generation, operation_id, client_id
                  from simpoint_resource_registration_deployments
                  where owner=:owner order by generation desc
                  """)
              .setParameter("owner", owner)
              .setFlushMode(FlushModeType.COMMIT)
              .setMaxResults(1)
              .getResultList();
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Object[] row = (Object[]) rows.getFirst();
      return Optional.of(
          new ResourceDeploymentState(
              owner,
              ((Number) row[0]).longValue(),
              UUID.fromString((String) row[1]),
              (String) row[2]));
    } catch (PersistenceException failure) {
      var rejection = new ResourceRegistrationException(Code.CATALOG_UNAVAILABLE);
      rejection.initCause(failure);
      throw rejection;
    }
  }

  /**
   * Atomically activates/retires an installer's batch with expected-generation checks and exact
   * retry semantics. This trusted local installation port must not be called from runtime
   * initialization or exposed through a business controller. Never deletes a retirement marker.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ResourceDeploymentState> apply(List<ResourceDeploymentChange> changes) {
    List<ResourceDeploymentChange> commands = validateChanges(changes);
    UUID operation = commands.getFirst().operationId();
    boundary.enter();
    String fingerprint = fingerprint(commands);
    List<?> journal =
        entityManager
            .createNativeQuery(
                """
                select fingerprint from simpoint_resource_registration_operations
                where operation_id=:operation
                """)
            .setParameter("operation", operation.toString())
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (!journal.isEmpty() && !fingerprint.equals(journal.getFirst())) {
      throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
    }
    List<ResourceDeploymentState> result = new ArrayList<>();
    List<ResourceDeploymentState> pending = new ArrayList<>();
    for (var change : commands) {
      var existing = current(change.owner());
      var desired =
          new ResourceDeploymentState(
              change.owner(),
              change.expectedGeneration() + 1,
              change.operationId(),
              change.clientId());
      if (!journal.isEmpty() && existing.isPresent() && existing.get().equals(desired)) {
        result.add(existing.get());
        continue;
      }
      if (!journal.isEmpty()
          || existing.map(ResourceDeploymentState::generation).orElse(0L)
              != change.expectedGeneration()
          || existing
              .map(ResourceDeploymentState::operationId)
              .filter(change.operationId()::equals)
              .isPresent()
          || operationAlreadyUsed(change)) {
        throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
      }
      result.add(desired);
      pending.add(desired);
    }
    if (journal.isEmpty()) {
      entityManager
          .createNativeQuery(
              """
              insert into simpoint_resource_registration_operations (operation_id,fingerprint)
              values (:operation,:fingerprint)
              """)
          .setParameter("operation", operation.toString())
          .setParameter("fingerprint", fingerprint)
          .setFlushMode(FlushModeType.COMMIT)
          .executeUpdate();
    }
    for (var state : pending) {
      entityManager
          .createNativeQuery(
              """
              insert into simpoint_resource_registration_deployments
                (owner,generation,operation_id,client_id)
              values (:owner,:generation,:operation,:client)
              """)
          .setParameter("owner", state.owner())
          .setParameter("generation", state.generation())
          .setParameter("operation", state.operationId().toString())
          .setParameter("client", state.clientId())
          .setFlushMode(FlushModeType.COMMIT)
          .executeUpdate();
    }
    return List.copyOf(result);
  }

  /** Reads the exact durable batch receipt and current authority without changing the catalog. */
  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public boolean installed(List<ResourceDeploymentChange> changes) {
    var commands = validateChanges(changes);
    UUID operation = commands.getFirst().operationId();
    List<?> journal = entityManager.createNativeQuery(
        "select fingerprint from simpoint_resource_registration_operations where operation_id=:operation")
        .setParameter("operation", operation.toString()).setFlushMode(FlushModeType.COMMIT).getResultList();
    if (journal.isEmpty()) {
      return false;
    }
    if (!fingerprint(commands).equals(journal.getFirst())) {
      throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
    }
    for (var command : commands) {
      var expected = new ResourceDeploymentState(command.owner(), command.expectedGeneration() + 1,
          operation, command.clientId());
      if (!current(command.owner()).filter(expected::equals).isPresent()) {
        throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
      }
    }
    return true;
  }

  private static List<ResourceDeploymentChange> validateChanges(List<ResourceDeploymentChange> changes) {
    if (changes == null
        || changes.isEmpty()
        || changes.size() > MAX_CHANGES
        || changes.stream().anyMatch(java.util.Objects::isNull)) {
      throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
    }
    List<ResourceDeploymentChange> commands = List.copyOf(changes);
    UUID operation = commands.getFirst().operationId();
    var owners = new HashSet<String>();
    var clients = new HashSet<String>();
    for (var change : commands) {
      requireText(change.owner(), 128);
      if (!owners.add(change.owner())
          || change.operationId() == null
          || !change.operationId().equals(operation)
          || change.expectedGeneration() < 0
          || change.expectedGeneration() == Long.MAX_VALUE) {
        throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
      }
      if (change.clientId() != null) {
        requireText(change.clientId(), 256);
        if (!ModuleClientPlan.belongsToDeployment(change.clientId(), change.operationId())
            || !clients.add(change.clientId())) {
          throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
        }
      }
    }
    return commands;
  }

  private static String fingerprint(List<ResourceDeploymentChange> commands) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(1);
        output.writeInt(commands.size());
        for (var command :
            commands.stream()
                .sorted(Comparator.comparing(ResourceDeploymentChange::owner))
                .toList()) {
          writeText(output, command.owner());
          output.writeLong(command.expectedGeneration());
          output.writeBoolean(command.clientId() != null);
          if (command.clientId() != null) {
            writeText(output, command.clientId());
          }
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot fingerprint resource deployment", impossible);
    }
  }

  private static void writeText(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private boolean operationAlreadyUsed(ResourceDeploymentChange change) {
    return !entityManager
        .createNativeQuery(
            """
            select generation from simpoint_resource_registration_deployments
            where (owner=:owner and operation_id=:operation) or client_id=:client
            """)
        .setParameter("owner", change.owner())
        .setParameter("operation", change.operationId().toString())
        .setParameter("client", change.clientId())
        .setFlushMode(FlushModeType.COMMIT)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  private static void requireText(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value
            .codePoints()
            .anyMatch(
                c ->
                    Character.isWhitespace(c)
                        || Character.getType(c) == Character.SURROGATE
                        || Character.isISOControl(c)
                        || c == '*')) {
      throw new ResourceRegistrationException(Code.DEPLOYMENT_CONFLICT);
    }
  }
}
