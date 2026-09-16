/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageProviderConfigRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for system-global OSS provider configurations.
 */
@Repository
public interface JpaObjectStorageProviderConfigRepository
    extends BaseRepository<ObjectStorageProviderConfig, String>, ObjectStorageProviderConfigRepository {

  @Override
  @Query("""
      select p from ObjectStorageProviderConfig p
      where p.id = :id and p.deletedAt is null
      """)
  Optional<ObjectStorageProviderConfig> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select p from ObjectStorageProviderConfig p
      where lower(p.code) = lower(:code) and p.deletedAt is null
      """)
  Optional<ObjectStorageProviderConfig> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select p from ObjectStorageProviderConfig p
      where p.defaultProvider = true and p.enabled = true and p.deletedAt is null
      order by p.updatedAt desc
      """)
  Optional<ObjectStorageProviderConfig> findDefaultEnabled();

  @Override
  @Query("""
      select p from ObjectStorageProviderConfig p
      where p.enabled = true and p.deletedAt is null
      order by p.defaultProvider desc, p.code asc
      """)
  List<ObjectStorageProviderConfig> findAllActiveEnabled();

  @Override
  @Query("""
      select (count(p) > 0) from ObjectStorageProviderConfig p
      where p.deletedAt is null
      """)
  boolean existsAnyActive();

  @Override
  @Modifying
  @Query("""
      update ObjectStorageProviderConfig p
      set p.defaultProvider = false
      where p.defaultProvider = true and p.deletedAt is null and p.id <> :id
      """)
  void clearDefaultExcept(@Param("id") String id);
}
