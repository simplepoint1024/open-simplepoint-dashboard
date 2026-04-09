/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;

/**
 * Repository contract for tenant quota configuration.
 */
public interface ObjectStorageTenantQuotaRepository extends BaseRepository<ObjectStorageTenantQuota, String> {

  /**
   * Finds an active quota by id.
   *
   * @param id quota id
   * @return active quota
   */
  Optional<ObjectStorageTenantQuota> findActiveById(String id);

  /**
   * Finds an active quota by tenant id.
   *
   * @param tenantId tenant id
   * @return active quota
   */
  Optional<ObjectStorageTenantQuota> findActiveByTenantId(String tenantId);
}
