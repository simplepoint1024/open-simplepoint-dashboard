package org.simplepoint.plugin.dna.runtime.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable authorization projection managed by trusted APP publication flows. */
@Service
public class PhysicalModelResourceStateService {

  private static final String MANAGE_PERMISSION = "dna.runtime.model-bindings.manage";

  private final EntityManager entityManager;
  private final RuntimeRequestContext requestContext;
  private final PhysicalModelRepository modelRepository;
  private final PhysicalModelResourceBindingRepository bindingRepository;

  /** Creates the model resource binding service. */
  public PhysicalModelResourceStateService(
      final EntityManager entityManager,
      final RuntimeRequestContext requestContext,
      final PhysicalModelRepository modelRepository,
      final PhysicalModelResourceBindingRepository bindingRepository
  ) {
    this.entityManager = entityManager;
    this.requestContext = requestContext;
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
  }

  /** Grants an unmanaged resource binding, safely reusing a previously retired row. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PhysicalModelResourceBindingView bind(
      final PhysicalModelResourceBindingCommand command
  ) {
    requireCommand(command);
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
    requireUnmanaged(lockResource(actor, command.resourceCode()));
    PhysicalModelEntity model = requireLockedModel(actor.tenantId(), command.physicalModelId());
    PhysicalModelResourceBindingEntity existing = bindingRepository.findActive(
        actor.tenantId(),
        model.getId(),
        command.resourceCode()
    ).orElse(null);
    if (existing != null) {
      return view(existing);
    }
    requireVersion(model, command.expectedLockVersion());
    if (model.getStatus() == PhysicalModelStatus.RETIRED) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT,
          "A retired physical model cannot receive resource bindings"
      );
    }
    model.setUpdatedBy(actor.actorId());
    return view(activate(actor, model.getId(), command.resourceCode()));
  }

  /** Revokes a legacy binding under the same resource lock used by managed projections. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void unbind(final PhysicalModelResourceBindingCommand command) {
    requireCommand(command);
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
    requireUnmanaged(lockResource(actor, command.resourceCode()));
    PhysicalModelEntity model = requireLockedModel(actor.tenantId(), command.physicalModelId());
    PhysicalModelResourceBindingEntity binding = bindingRepository.findActive(
        actor.tenantId(),
        model.getId(),
        command.resourceCode()
    ).orElse(null);
    if (binding == null) {
      return;
    }
    requireVersion(model, command.expectedLockVersion());
    binding.setDeletedAt(Instant.now());
    binding.setUpdatedBy(actor.actorId());
    model.setUpdatedBy(actor.actorId());
  }

  /** Lists the current model projection without changing ownership. */
  @Transactional(readOnly = true)
  public List<PhysicalModelResourceBindingView> list(final String physicalModelId) {
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
    PhysicalModelEntity model = modelRepository.findActiveById(actor.tenantId(), physicalModelId)
        .orElseThrow(PhysicalModelResourceStateService::notFound);
    return bindingRepository.findActiveByModel(actor.tenantId(), model.getId()).stream()
        .map(PhysicalModelResourceStateService::view)
        .toList();
  }

  /** Applies one desired resource generation and retires all superseded model associations atomically. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PhysicalModelResourceBindingView project(final PhysicalModelResourceProjectionCommand command) {
    if (command == null) {
      throw conflict("Resource projection command is required");
    }
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
    PhysicalResourceProjectionEntity projection = lockResource(actor, command.resourceCode());
    if (projection.getOwnerKey() != null && !projection.getOwnerKey().equals(command.ownerKey())) {
      throw conflict("Resource projection belongs to another owner");
    }
    if (command.generation() < projection.getGeneration()
        || command.generation() == projection.getGeneration() && projection.isFormallyOffline()) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED,
          "A newer resource publication has already superseded this generation");
    }
    if (command.generation() == projection.getGeneration()
            && !command.physicalModelId().equals(projection.getPhysicalModelId())) {
      throw conflict("Resource projection owner or generation does not match");
    }
    PhysicalModelEntity target = requireLockedModel(actor.tenantId(), command.physicalModelId());
    if (target.getStatus() != PhysicalModelStatus.ACTIVE) {
      throw conflict("Only an active model can receive a managed resource projection");
    }
    Instant timestamp = Instant.now();
    List<PhysicalModelResourceBindingEntity> previous = entityManager.createQuery(
            "from PhysicalModelResourceBindingEntity b where b.tenantId = :tenant "
                + "and b.resourceCode = :resource and b.deletedAt is null", PhysicalModelResourceBindingEntity.class)
        .setParameter("tenant", actor.tenantId()).setParameter("resource", command.resourceCode()).getResultList();
    for (PhysicalModelResourceBindingEntity binding : previous) {
      if (!target.getId().equals(binding.getPhysicalModelId())) {
        binding.setDeletedAt(timestamp);
        binding.setUpdatedBy(actor.actorId());
      }
    }
    final PhysicalModelResourceBindingEntity active = activate(actor, target.getId(), command.resourceCode());
    projection.setOwnerKey(command.ownerKey());
    projection.setGeneration(command.generation());
    projection.setFormallyOffline(false);
    projection.setPhysicalModelId(target.getId());
    projection.setUpdatedBy(actor.actorId());
    projection.setUpdatedAt(timestamp);
    entityManager.flush();
    return view(active);
  }

  private PhysicalResourceProjectionEntity lockResource(final RuntimeActor actor, final String resourceCode) {
    PhysicalResourceProjectionEntity projection = entityManager.createQuery(
            "from PhysicalResourceProjectionEntity p where p.tenantId = :tenant and p.resourceCode = :resource",
            PhysicalResourceProjectionEntity.class)
        .setParameter("tenant", actor.tenantId()).setParameter("resource", resourceCode)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
    if (projection != null) {
      return projection;
    }
    projection = new PhysicalResourceProjectionEntity();
    projection.setId(UUID.randomUUID().toString());
    projection.setTenantId(actor.tenantId());
    projection.setResourceCode(resourceCode);
    projection.setCreatedBy(actor.actorId());
    projection.setUpdatedBy(actor.actorId());
    projection.setCreatedAt(Instant.now());
    projection.setUpdatedAt(projection.getCreatedAt());
    try {
      entityManager.persist(projection);
      entityManager.flush();
    } catch (PersistenceException failure) {
      throw new ResourceProjectionCreateRaceException(failure);
    }
    return projection;
  }

  private PhysicalModelResourceBindingEntity activate(RuntimeActor actor, String modelId, String resourceCode) {
    PhysicalModelResourceBindingEntity binding = bindingRepository.findIncludingDeleted(
        actor.tenantId(), modelId, resourceCode).orElse(null);
    if (binding == null) {
      binding = new PhysicalModelResourceBindingEntity();
      binding.setId(UUID.randomUUID().toString());
      binding.setTenantId(actor.tenantId());
      binding.setPhysicalModelId(modelId);
      binding.setResourceCode(resourceCode);
      binding.setCreatedBy(actor.actorId());
      binding.setUpdatedBy(actor.actorId());
      entityManager.persist(binding);
    } else {
      binding.setDeletedAt(null);
      binding.setUpdatedBy(actor.actorId());
    }
    entityManager.flush();
    return binding;
  }

  private static void requireUnmanaged(final PhysicalResourceProjectionEntity projection) {
    if (projection.getOwnerKey() != null) {
      throw conflict("Managed resources require their owner and monotonic generation");
    }
  }

  private static DnaRuntimeException conflict(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PHYSICAL_BINDING_CONFLICT, message);
  }

  private PhysicalModelEntity requireLockedModel(
      final String tenantId,
      final String physicalModelId
  ) {
    return modelRepository.findActiveByIdForUpdate(tenantId, physicalModelId)
        .orElseThrow(PhysicalModelResourceStateService::notFound);
  }

  private static void requireResourceAdministration() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    Collection<String> resources = context == null || context.getResources() == null
        ? List.of() : context.getResources();
    if (context == null || !Boolean.TRUE.equals(context.getIsAdministrator())
        && !resources.contains(MANAGE_PERMISSION)) {
      throw new AccessDeniedException("The current actor cannot bind this page resource");
    }
  }

  private static void requireVersion(
      final PhysicalModelEntity model,
      final long expectedVersion
  ) {
    if (model.getLockVersion() != expectedVersion) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT,
          "The physical model was changed by another request"
      );
    }
  }

  private static void requireCommand(final PhysicalModelResourceBindingCommand command) {
    if (command == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.INVALID_ARGUMENT,
          "Physical model resource binding command is required"
      );
    }
  }

  private static PhysicalModelResourceBindingView view(
      final PhysicalModelResourceBindingEntity entity
  ) {
    return new PhysicalModelResourceBindingView(
        entity.getId(),
        entity.getPhysicalModelId(),
        entity.getResourceCode(),
        entity.getCreatedAt()
    );
  }

  private static DnaRuntimeException notFound() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
        "Physical model was not found in the active tenant"
    );
  }
}
