/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.base.entity.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.extra.spring.SpringUtil;
import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import java.io.Serializable;
import java.time.Instant;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLDeleteAll;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.annotation.UuidStringGenerator;
import org.simplepoint.core.context.UserContext;

/**
 * A base entity class for database models.
 * This class is annotated as a MappedSuperclass and extends BaseEntityImpl.
 * It provides implementations for commonly used audit fields such as ID,
 * creation timestamps, update timestamps, and user metadata.
 *
 * @param <I> the type of the ID field, which must be serializable
 */
@Slf4j
@Data
@JacksonStdImpl
@MappedSuperclass
@Filter(name = "softDeleteFilter", condition = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE ${TABLE_NAME} SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLDeleteAll(sql = "UPDATE ${TABLE_NAME} SET deleted_at = CURRENT_TIMESTAMP WHERE id in (?)")
public class BaseEntityImpl<I extends Serializable> implements BaseEntity<I> {

  /**
   * The unique identifier for the entity.
   * This field is annotated with @Id and @SnowflakeId for ID generation.
   */
  @Id
  @UuidStringGenerator
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private I id;

  /**
   * The timestamp indicating when the entity was deleted.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private Instant deletedAt;

  /**
   * The organization department ID associated with the entity.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String createOrgDeptId;

  /**
   * The user who created the entity.
   * This field is annotated with @CreatedBy.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String createdBy;

  /**
   * The user who last updated the entity.
   * This field is annotated with @LastModifiedBy.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String updatedBy;

  /**
   * The timestamp indicating when the entity was created.
   * This field is annotated with @LastModifiedDate.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private Instant createdAt;

  /**
   * The timestamp indicating when the entity was last updated.
   * This field is annotated with @UpdateTimestamp.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private Instant updatedAt;

  /**
   * Retrieves the unique identifier for the entity.
   * This method is annotated with @Id and @SnowflakeId for ID generation.
   *
   * @return the unique identifier of the entity
   */
  @Override
  public I getId() {
    return this.id;
  }

  /**
   * Retrieves the timestamp indicating when the entity was created.
   * This method is annotated with @LastModifiedDate.
   *
   * @return the creation timestamp of the entity
   */
  @Override
  public Instant getCreatedAt() {
    return this.createdAt;
  }

  /**
   * Retrieves the timestamp indicating when the entity was last updated.
   * This method is annotated with @UpdateTimestamp.
   *
   * @return the last update timestamp of the entity
   */
  @Override
  public Instant getUpdatedAt() {
    return this.updatedAt;
  }

  /**
   * Retrieves the user who created the entity.
   * This method is annotated with @CreatedBy.
   *
   * @return the creator of the entity
   */
  @Override
  public String getCreatedBy() {
    return this.createdBy;
  }

  /**
   * Retrieves the user who last updated the entity.
   * This method is annotated with @LastModifiedBy.
   *
   * @return the last updater of the entity
   */
  @Override
  public String getUpdatedBy() {
    return this.updatedBy;
  }

  /**
   * Pre-persist lifecycle callback to perform actions before the entity is persisted.
   */
  @PrePersist
  public void prePersist() {
    try {
      UserContext<BaseUser> userContext = SpringUtil.getBean(new TypeReference<>() {
      });
      if (this.createdBy == null) {
        if (userContext != null) {
          this.setCreatedBy(userContext.getDetails().getUsername());
        }
      }
      if (this.updatedBy == null) {
        if (userContext != null) {
          this.setUpdatedBy(userContext.getDetails().getUsername());
        }
      }
    } catch (Exception ex) {
      log.warn("Failed to set createdBy or updatedBy in prePersist: {}", ex.getMessage());
    }

    if (this.createdAt == null) {
      this.setCreatedAt(Instant.now());
    }
    if (this.updatedAt == null) {
      this.setUpdatedAt(Instant.now());
    }
  }

  /**
   * Pre-update lifecycle callback to perform actions before the entity is updated.
   */
  @PreUpdate
  public void preUpdate() {
    UserContext<BaseUser> userContext = SpringUtil.getBean(new TypeReference<>() {
    });
    if (userContext != null) {
      this.setUpdatedBy(userContext.getDetails().getUsername());
    }
    this.setUpdatedAt(Instant.now());
  }

  /**
   * Pre-remove lifecycle callback to perform actions before the entity is removed.
   */
  @PreRemove
  public void preRemove() {
    this.setDeletedAt(Instant.now());
  }
}
