/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for tenant quota definitions.
 */
@Repository
public interface JpaObjectStorageTenantQuotaRepository
    extends BaseRepository<ObjectStorageTenantQuota, String>, ObjectStorageTenantQuotaRepository {

  @Override
  @Query("""
      select q
      from ObjectStorageTenantQuota q
      where q.id = :id
        and q.deletedAt is null
      """)
  Optional<ObjectStorageTenantQuota> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select q
      from ObjectStorageTenantQuota q
      where q.tenantId = :tenantId
        and q.deletedAt is null
      order by q.createdAt desc
      """)
  Optional<ObjectStorageTenantQuota> findActiveByTenantId(@Param("tenantId") String tenantId);
}
