/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.base;

import java.io.Serializable;
import java.time.Instant;

/**
 * Base entity.
 *
 * @param <I> Primary key.
 */
public interface BaseEntity<I extends Serializable> extends Serializable {

  /**
   * primary Key.
   *
   * @return primary key.
   */
  I getId();

  /**
   * setter id.
   *
   * @param id id
   */
  void setId(I id);

  /**
   * getter creator.
   *
   * @return creator.
   */
  String getCreatedBy();

  /**
   * set creator.
   *
   * @param createdBy creator.
   */
  void setCreatedBy(String createdBy);

  /**
   * getter updater.
   *
   * @return updater
   */
  String getUpdatedBy();

  /**
   * setter updater.
   */
  void setUpdatedBy(String updatedBy);

  /**
   * getter create time.
   *
   * @return create time
   */
  Instant getCreatedAt();

  /**
   * setter create time.
   */
  void setCreatedAt(Instant createdAt);

  /**
   * getter update time.
   *
   * @return update time
   */

  Instant getUpdatedAt();

  /**
   * setter update time.
   */

  void setUpdatedAt(Instant updatedAt);

  /**
   * getter delete time.
   *
   * @return delete time
   */
  Instant getDeletedAt();

  /**
   * setter delete time.
   */
  void setDeletedAt(Instant deletedAt);

  /**
   * getter org department id.
   *
   * @return org department id
   */
  String getCreateOrgDeptId();

  /**
   * setter org department id.
   */
  void setCreateOrgDeptId(String createOrgDeptId);
}
