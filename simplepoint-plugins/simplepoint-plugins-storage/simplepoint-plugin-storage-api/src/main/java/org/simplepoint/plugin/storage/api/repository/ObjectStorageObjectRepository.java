/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;

/**
 * Repository contract for stored object metadata.
 */
public interface ObjectStorageObjectRepository extends BaseRepository<ObjectStorageObject, String> {

  /**
   * Finds an active object by id.
   *
   * @param id object id
   * @return object metadata
   */
  Optional<ObjectStorageObject> findActiveById(String id);

  /**
   * Finds active objects by ids.
   *
   * @param ids ids
   * @return active objects
   */
  List<ObjectStorageObject> findAllActiveByIds(Collection<String> ids);

  /**
   * Finds an active object by provider+bucket+key.
   *
   * @param providerCode provider code
   * @param bucket       bucket name
   * @param objectKey    object key
   * @return object metadata
   */
  Optional<ObjectStorageObject> findActiveByProviderCodeAndBucketAndObjectKey(
      String providerCode,
      String bucket,
      String objectKey
  );

  /**
   * Sums active object bytes for a tenant.
   *
   * @param tenantId tenant id
   * @return bytes used
   */
  Long sumActiveContentLengthByTenantId(String tenantId);

  /**
   * Sums active object bytes for multiple tenants.
   *
   * @param tenantIds tenant ids
   * @return usage projection list
   */
  Collection<TenantStorageUsage> sumActiveContentLengthByTenantIds(Collection<String> tenantIds);

  /**
   * Tenant usage projection.
   */
  interface TenantStorageUsage {
    String getTenantId();

    Long getUsedBytes();
  }
}
