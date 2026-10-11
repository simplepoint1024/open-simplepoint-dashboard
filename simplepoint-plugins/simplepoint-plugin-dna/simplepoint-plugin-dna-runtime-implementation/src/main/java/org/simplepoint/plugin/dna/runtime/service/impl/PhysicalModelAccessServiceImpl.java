package org.simplepoint.plugin.dna.runtime.service.impl;

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
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable authorization projection managed by trusted APP publication flows. */
@Service
public class PhysicalModelAccessServiceImpl implements PhysicalModelAccessService {

  private static final String MANAGE_PERMISSION = "dna.runtime.model-bindings.manage";

  private final RuntimeRequestContext requestContext;
  private final PhysicalModelRepository modelRepository;
  private final PhysicalModelResourceBindingRepository bindingRepository;

  /** Creates the model resource binding service. */
  public PhysicalModelAccessServiceImpl(
      final RuntimeRequestContext requestContext,
      final PhysicalModelRepository modelRepository,
      final PhysicalModelResourceBindingRepository bindingRepository
  ) {
    this.requestContext = requestContext;
    this.modelRepository = modelRepository;
    this.bindingRepository = bindingRepository;
  }

  @Override
  @Transactional
  public PhysicalModelResourceBindingView bind(
      final PhysicalModelResourceBindingCommand command
  ) {
    requireCommand(command);
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
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
    PhysicalModelResourceBindingEntity entity = new PhysicalModelResourceBindingEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setTenantId(actor.tenantId());
    entity.setPhysicalModelId(model.getId());
    entity.setResourceCode(command.resourceCode());
    entity.setCreatedBy(actor.actorId());
    entity.setUpdatedBy(actor.actorId());
    model.setUpdatedBy(actor.actorId());
    return view(bindingRepository.save(entity));
  }

  @Override
  @Transactional
  public void unbind(final PhysicalModelResourceBindingCommand command) {
    requireCommand(command);
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
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

  @Override
  @Transactional(readOnly = true)
  public List<PhysicalModelResourceBindingView> list(final String physicalModelId) {
    RuntimeActor actor = requestContext.requireActor();
    requireResourceAdministration();
    PhysicalModelEntity model = modelRepository.findActiveById(actor.tenantId(), physicalModelId)
        .orElseThrow(PhysicalModelAccessServiceImpl::notFound);
    return bindingRepository.findActiveByModel(actor.tenantId(), model.getId()).stream()
        .map(PhysicalModelAccessServiceImpl::view)
        .toList();
  }

  private PhysicalModelEntity requireLockedModel(
      final String tenantId,
      final String physicalModelId
  ) {
    return modelRepository.findActiveByIdForUpdate(tenantId, physicalModelId)
        .orElseThrow(PhysicalModelAccessServiceImpl::notFound);
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
