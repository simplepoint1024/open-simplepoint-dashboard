package org.simplepoint.plugin.dna.runtime.api.service;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;

/** Maintains the server-owned page-resource authorization projection for a model. */
public interface PhysicalModelAccessService {

  /** Atomically replaces the managed resource projection, rejecting stale generations and foreign owners. */
  PhysicalModelResourceBindingView project(PhysicalModelResourceProjectionCommand command);

  /** Idempotently grants one page resource access to the model. */
  PhysicalModelResourceBindingView bind(PhysicalModelResourceBindingCommand command);

  /** Revokes one page resource binding. */
  void unbind(PhysicalModelResourceBindingCommand command);

  /** Lists current resource bindings without exposing physical placement. */
  List<PhysicalModelResourceBindingView> list(String physicalModelId);
}
