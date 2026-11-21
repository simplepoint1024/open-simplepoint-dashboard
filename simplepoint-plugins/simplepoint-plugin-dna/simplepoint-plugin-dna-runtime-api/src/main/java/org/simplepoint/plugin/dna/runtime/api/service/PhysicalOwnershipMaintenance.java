package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Offline installer capability; deliberately not an RPC service or a browser resource. */
public interface PhysicalOwnershipMaintenance {
  /** Returns every retained binding across tenants, including soft-deleted models and bindings. */
  PhysicalBindingInventory inventory();
}
