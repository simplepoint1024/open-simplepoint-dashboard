package org.simplepoint.plugin.dna.runtime.service.support;

import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalPlacementRequest;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePlacementProperties;

/** Resolves logical placement keys from administrator-owned service configuration. */
public class ConfiguredPhysicalBindingTargetResolver
    implements PhysicalBindingTargetResolver {

  private final DnaRuntimePlacementProperties properties;

  /** Creates a resolver backed by administrator-owned service configuration. */
  public ConfiguredPhysicalBindingTargetResolver(
      final DnaRuntimePlacementProperties properties
  ) {
    this.properties = properties;
  }

  @Override
  public PhysicalBindingTarget resolve(final PhysicalPlacementRequest request) {
    Map<String, DnaRuntimePlacementProperties.Target> targets = properties.getTargets();
    DnaRuntimePlacementProperties.Target target = targets == null
        ? null : targets.get(request.placementKey());
    if (target == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PLACEMENT_UNAVAILABLE,
          "No server placement is configured for key " + request.placementKey()
      );
    }
    try {
      return new PhysicalBindingTarget(
          target.getDataSourceId(),
          target.getCatalogName(),
          target.getSchemaName(),
          target.getIsolationMode()
      );
    } catch (IllegalArgumentException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PLACEMENT_UNAVAILABLE,
          "Server placement configuration is invalid for key " + request.placementKey(),
          exception
      );
    }
  }
}
