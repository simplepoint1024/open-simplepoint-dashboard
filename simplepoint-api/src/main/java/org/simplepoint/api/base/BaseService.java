/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.base;

import cn.hutool.core.bean.copier.CopyOptions;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
   * @throws Exception exception
   */
  <S extends T> S add(S entity) throws Exception;

  /**
   * add.
   *
   * @param entities entity collection
   * @return list
   * @throws Exception exception
   */
  List<T> add(Collection<T> entities) throws Exception;

  /**
   * modifyById.
   *
   * @param entity entity
   * @param <S>    entity
   * @return entity
   * @throws Exception exception
   */
  <S extends T> T modifyById(S entity) throws Exception;

  /**
   * getCopyOptions.
   *
   * @return CopyOptions
   */
  CopyOptions getCopyOptions();

  /**
   * removeAll.
   *
   * @throws Exception exception
   */
  void removeAll() throws Exception;

  /**
   * removeById.
   *
   * @param id primary key
   * @throws Exception exception
   */
  void removeById(I id) throws Exception;

  /**
   * removeByIds.
   *
   * @param ids primary keys
   * @throws Exception exception
   */
  void removeByIds(Collection<I> ids) throws Exception;

  /**
   * findById.
   *
   * @param id primary key
   * @return data
   * @throws Exception exception
   */
  Optional<T> findById(I id) throws Exception;

  /**
   * find all by ids.
   *
   * @param ids ids
   * @return list
   * @throws Exception exception
   */
  List<T> findAllByIds(Iterable<I> ids) throws Exception;

  /**
   * findAll.
   *
   * @param attributes args
   * @return list
   * @throws Exception exception
   */
  List<T> findAll(Map<String, String> attributes) throws Exception;

  /**
   * limit.
   *
   * @param attributes attributes
   * @param pageable   pageable
   * @param <S>        entity
   * @return page
   * @throws Exception exception
   */
  <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable) throws Exception;

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
   * @throws Exception exception
   */
  <S extends T> boolean exists(S example) throws Exception;

  /**
   * existsById.
   *
   * @param id primary key
   * @return exists
   * @throws Exception exception
   */
  boolean existsById(I id) throws Exception;

  /**
   * count.
   *
   * @param example condition
   * @param <S>     entity
   * @return COUNT
   * @throws Exception exception
   */
  <S extends T> long count(S example) throws Exception;
}
