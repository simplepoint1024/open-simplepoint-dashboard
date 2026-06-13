/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.remoting;

import java.util.Optional;

/**
 * Discovers providers for remoting contracts.
 */
public interface RemoteServiceCapabilityRegistry {

  /**
   * Finds a provider for the requested contract.
   *
   * @param contract the contract metadata
   * @return the selected provider when available
   */
  Optional<RemoteServiceCapability> findProvider(RemoteContractMetadata contract);
}
