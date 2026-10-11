package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingTarget;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalPlacementRequest;

/** Resolves a logical placement key to a server-owned physical target. */
public interface PhysicalBindingTargetResolver {

  /** Resolves a logical policy using trusted tenant and form context. */
  PhysicalBindingTarget resolve(PhysicalPlacementRequest request);
}
