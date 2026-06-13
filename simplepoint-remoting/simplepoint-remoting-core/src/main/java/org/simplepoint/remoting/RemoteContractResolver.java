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
 * Resolves transport-neutral metadata from Java service interfaces.
 */
public final class RemoteContractResolver {

  private RemoteContractResolver() {
  }

  /**
   * Resolves metadata for an interface annotated with {@link RemoteContract}.
   *
   * @param contractInterface the service interface
   * @return metadata when the interface declares a remoting contract
   */
  public static Optional<RemoteContractMetadata> resolve(final Class<?> contractInterface) {
    if (contractInterface == null || !contractInterface.isInterface()) {
      return Optional.empty();
    }
    RemoteContract contract = contractInterface.getAnnotation(RemoteContract.class);
    if (contract == null || contract.name().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new RemoteContractMetadata(
        contractInterface.getName(),
        contract.name(),
        contract.version().isBlank() ? "1" : contract.version()
    ));
  }

  /**
   * Returns whether the given interface declares a remoting contract.
   *
   * @param contractInterface the service interface
   * @return true when remoting metadata is available
   */
  public static boolean isRemoteContract(final Class<?> contractInterface) {
    return resolve(contractInterface).isPresent();
  }
}
