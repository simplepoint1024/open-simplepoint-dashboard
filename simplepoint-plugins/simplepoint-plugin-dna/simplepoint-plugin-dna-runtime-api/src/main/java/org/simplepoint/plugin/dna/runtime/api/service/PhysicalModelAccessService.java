package org.simplepoint.plugin.dna.runtime.api.service;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;

/** Maintains the server-owned page-resource authorization projection for a model. */
public interface PhysicalModelAccessService {

  /** Idempotently grants one page resource access to the model. */
  PhysicalModelResourceBindingView bind(PhysicalModelResourceBindingCommand command);

  /** Revokes one page resource binding. */
  void unbind(PhysicalModelResourceBindingCommand command);

  /** Lists current resource bindings without exposing physical placement. */
  List<PhysicalModelResourceBindingView> list(String physicalModelId);
}
