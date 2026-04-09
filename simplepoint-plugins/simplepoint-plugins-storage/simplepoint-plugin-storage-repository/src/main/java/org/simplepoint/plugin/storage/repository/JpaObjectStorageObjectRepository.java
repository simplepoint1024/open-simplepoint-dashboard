/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for object-storage metadata.
 */
@Repository
public interface JpaObjectStorageObjectRepository
    extends BaseRepository<ObjectStorageObject, String>, ObjectStorageObjectRepository {

  @Override
  @Query("""
      select o
      from ObjectStorageObject o
      where o.id = :id
        and o.deletedAt is null
      """)
  Optional<ObjectStorageObject> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select o
      from ObjectStorageObject o
      where o.id in :ids
        and o.deletedAt is null
      """)
  List<ObjectStorageObject> findAllActiveByIds(@Param("ids") Collection<String> ids);

  @Override
  @Query("""
      select o
      from ObjectStorageObject o
      where o.providerCode = :providerCode
        and o.bucket = :bucket
        and o.objectKey = :objectKey
        and o.deletedAt is null
      """)
  Optional<ObjectStorageObject> findActiveByProviderCodeAndBucketAndObjectKey(
      @Param("providerCode") String providerCode,
      @Param("bucket") String bucket,
      @Param("objectKey") String objectKey
  );

  @Override
  @Query("""
      select coalesce(sum(o.contentLength), 0)
      from ObjectStorageObject o
      where o.tenantId = :tenantId
        and o.deletedAt is null
      """)
  Long sumActiveContentLengthByTenantId(@Param("tenantId") String tenantId);

  @Override
  @Query("""
      select o.tenantId as tenantId, coalesce(sum(o.contentLength), 0) as usedBytes
      from ObjectStorageObject o
      where o.tenantId in :tenantIds
        and o.deletedAt is null
      group by o.tenantId
      """)
  Collection<TenantStorageUsage> sumActiveContentLengthByTenantIds(@Param("tenantIds") Collection<String> tenantIds);
}
