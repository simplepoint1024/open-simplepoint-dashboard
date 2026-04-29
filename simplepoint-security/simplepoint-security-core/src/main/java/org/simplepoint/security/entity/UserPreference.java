/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Stores arbitrary UI/UX preference values per user, keyed by a string key.
 * Each (userId, preferenceKey) pair is unique, allowing per-user, per-page settings.
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(
    name = "simpoint_ac_user_preferences",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_user_preference_key",
            columnNames = {"user_id", "preference_key"}
        )
    },
    indexes = {
        @Index(name = "idx_user_preference_user_id", columnList = "user_id")
    }
)
@EqualsAndHashCode(callSuper = true)
public class UserPreference extends BaseEntityImpl<String> {

  @Schema(description = "The user this preference belongs to", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Schema(description = "Preference key, e.g. 'sp.table.cols.myPage'")
  @Column(name = "preference_key", nullable = false, length = 200)
  private String preferenceKey;

  @Schema(description = "Preference value, stored as JSON text")
  @Lob
  @Column(name = "preference_value", columnDefinition = "TEXT")
  private String preferenceValue;
}
