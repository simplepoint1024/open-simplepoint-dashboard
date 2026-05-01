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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * A single field-access rule within a {@link FieldScope}.
 * Each entry defines the access type for one field of one resource class.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(
    name = "simpoint_ac_field_scope_entry",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_field_scope_entry",
        columnNames = {"field_scope_id", "resource", "field"}
    )
)
@Schema(title = "字段范围条目", description = "字段范围中的单条字段访问规则")
public class FieldScopeEntry extends TenantBaseEntityImpl<String> {

  @Schema(title = "i18n:field-scopes.title.entry.fieldScopeId", description = "i18n:field-scopes.description.entry.fieldScopeId")
  @Column(name = "field_scope_id", nullable = false)
  private String fieldScopeId;

  @Schema(title = "i18n:field-scopes.title.entry.resource", description = "i18n:field-scopes.description.entry.resource", example = "Order")
  @Column(nullable = false, length = 200)
  private String resource;

  @Schema(title = "i18n:field-scopes.title.entry.field", description = "i18n:field-scopes.description.entry.field", example = "amount")
  @Column(nullable = false, length = 100)
  private String field;

  @Schema(title = "i18n:field-scopes.title.entry.access", description = "i18n:field-scopes.description.entry.access")
  @Column(nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private FieldAccessType access;
}
