/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.base.entity.impl;

import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.io.Serializable;
import java.time.Instant;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.core.annotation.FormSchema;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * A base entity class for database models.
 * This class is annotated as a MappedSuperclass and extends BaseEntityImpl.
 * It provides implementations for commonly used audit fields such as ID,
 * creation timestamps, update timestamps, and user metadata.
 *
 * @param <I> the type of the ID field, which must be serializable
 */
@Data
@FormSchema
@JacksonStdImpl
@MappedSuperclass
public class BaseEntityImpl<I extends Serializable> implements BaseEntity<I> {

  /**
   * The unique identifier for the entity.
   * This field is annotated with @Id and @SnowflakeId for ID generation.
   */
  @Id
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private I id;

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
  @GeneratedValue(strategy = GenerationType.UUID)
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
  @LastModifiedDate
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
  @UpdateTimestamp
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
  @CreatedBy
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
  @LastModifiedBy
  public String getUpdatedBy() {
    return this.updatedBy;
  }
}
