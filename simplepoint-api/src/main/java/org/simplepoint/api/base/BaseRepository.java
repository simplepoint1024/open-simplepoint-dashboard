/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.base;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Base Repository.
 *
 * @param <T> entity
 * @param <I> primary key
 */
public interface BaseRepository<T extends BaseEntity<I>, I extends Serializable> {

  /**
   * Head.
   */
  Class<T> getDomainClass();

  /**
   * flush.
   */
  void flush();

  /**
   * add.
   *
   * @param entity entity.
   * @param <S>    entity
   * @return entity.
   */
  <S extends T> S save(S entity);

  /**
   * add.
   *
   * @param entities entity
   * @return entity.
   */
  <S extends T> List<S> saveAll(Iterable<S> entities);

  /**
   * modify by id.
   *
   * @param entity entity
   * @param <S>    entity
   * @return entity
   */
  <S extends T> T updateById(S entity);

  /**
   * remove all data.
   */
  void deleteAll();

  /**
   * remove by primary key.
   *
   * @param id primary key.
   */
  void deleteById(I id);

  /**
   * Batch remove by primary key.
   *
   * @param ids ids
   */
  void deleteByIds(Collection<I> ids);

  /**
   * Find by id.
   *
   * @param id id.
   * @return entity
   */
  Optional<T> findById(I id);

  /**
   * find all by ids.
   *
   * @param ids ids.
   * @return entity list
   */
  List<T> findAllByIds(Iterable<I> ids);

  /**
   * find by attributes.
   *
   * @param attributes args.
   * @return data
   */
  List<T> findAll(Map<String, String> attributes);

  /**
   * Page.
   *
   * @param attributes args
   * @param pageable   pageable arguments
   * @param <S>        entity
   * @return page
   */
  <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable);

  /**
   * select data is exists.
   *
   * @param example arguments.
   * @param <S>     entity
   * @return exists
   */
  <S extends T> boolean exists(S example);

  /**
   * exists by id.
   *
   * @param id id.
   * @return result.
   */
  boolean existsById(I id);

  /**
   * count.
   *
   * @param example arguments
   * @param <S>     entity
   * @return count
   */
  <S extends T> long count(S example);
}
