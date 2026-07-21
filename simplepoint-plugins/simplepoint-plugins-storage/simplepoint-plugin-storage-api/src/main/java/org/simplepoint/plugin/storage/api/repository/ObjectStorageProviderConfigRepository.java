/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.simplepoint.plugin.storage.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;

/**
 * Repository contract for system-global OSS provider configurations.
 */
public interface ObjectStorageProviderConfigRepository
    extends BaseRepository<ObjectStorageProviderConfig, String> {

  /**
   * Finds an active configuration by id.
   */
  Optional<ObjectStorageProviderConfig> findActiveById(String id);

  /**
   * Finds an active configuration by code.
   */
  Optional<ObjectStorageProviderConfig> findActiveByCode(String code);

  /**
   * Finds the enabled system-default configuration.
   */
  Optional<ObjectStorageProviderConfig> findDefaultEnabled();

  /**
   * Lists all active enabled configurations.
   */
  List<ObjectStorageProviderConfig> findAllActiveEnabled();

  /**
   * Returns whether at least one active database configuration exists.
   */
  boolean existsAnyActive();

  /**
   * Clears the default flag from every active configuration except the given id.
   */
  void clearDefaultExcept(String id);
}
