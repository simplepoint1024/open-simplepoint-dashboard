/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.base;

import cn.hutool.core.bean.copier.CopyOptions;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Base Service.
 *
 * @param <T> entity
 * @param <I> primary key
 */
public interface BaseService<T extends BaseEntity<I>, I extends Serializable> {

  /**
   * get auditing services.
   *
   * @return Class
   */
  Collection<ModifyDataAuditingService> getModifyDataAuditingServices();

  /**
   * Get metadata.
   *
   * @return metadata
   */
  Map<String, Object> schema();

  /**
   * add.
   *
   * @param entity entity
   * @param <S>    entity
   * @return list g
   */
  <S extends T> S persist(S entity);

  /**
   * add.
   *
   * @param entities entity collection
   * @return list
   */
  List<T> persist(Collection<T> entities);

  /**
   * modifyById.
   *
   * @param entity entity
   * @param <S>    entity
   * @return entity
   */
  <S extends T> T modifyById(S entity);

  /**
   * getCopyOptions.
   *
   * @return CopyOptions
   */
  CopyOptions getCopyOptions();

  /**
   * removeAll.
   */
  void removeAll();

  /**
   * removeById.
   *
   * @param id primary key
   */
  void removeById(I id);

  /**
   * removeByIds.
   *
   * @param ids primary keys
   */
  void removeByIds(Collection<I> ids);

  /**
   * findById.
   *
   * @param id primary key
   * @return data
   */
  Optional<T> findById(I id);

  /**
   * find all by ids.
   *
   * @param ids ids
   * @return list
   */
  List<T> findAllByIds(Iterable<I> ids);

  /**
   * findAll.
   *
   * @param attributes args
   * @return list
   */
  List<T> findAll(Map<String, String> attributes);

  /**
   * limit.
   *
   * @param attributes attributes
   * @param pageable   pageable
   * @param <S>        entity
   * @return page
   */
  <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable);

  /**
   * validate.
   *
   * @param data data
   * @param <S>  entity
   */
  <S extends T> void validate(Collection<S> data);

  /**
   * exists.
   *
   * @param example condition
   * @param <S>     entity
   * @return exists.
   */
  <S extends T> boolean exists(S example);

  /**
   * existsById.
   *
   * @param id primary key
   * @return exists
   */
  boolean existsById(I id);

  /**
   * count.
   *
   * @param example condition
   * @param <S>     entity
   * @return COUNT
   */
  <S extends T> long count(S example);
}
