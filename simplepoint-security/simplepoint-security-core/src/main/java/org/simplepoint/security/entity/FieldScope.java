/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents a field scope policy that controls column-level access to entity fields.
 * A field scope is attached to a role to restrict which fields are visible or editable.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "simpoint_ac_field_scope")
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "field-scope.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "field-scope.edit"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.DELETE_TITLE,
        key = PublicButtonKeys.DELETE_KEY,
        color = "danger",
        icon = Icons.MINUS_CIRCLE,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 10,
        danger = true,
        authority = "field-scope.delete"
    )
})
@Schema(title = "字段范围", description = "定义列级字段访问权限策略")
public class FieldScope extends TenantBaseEntityImpl<String> {

  @Schema(title = "i18n:field-scope.title.name", description = "i18n:field-scope.description.name")
  @Column(nullable = false, length = 100)
  private String name;

  @Schema(title = "i18n:field-scope.title.description", description = "i18n:field-scope.description.description")
  @Column(length = 200)
  private String description;

  @Schema(title = "i18n:field-scope.title.entries", description = "i18n:field-scope.description.entries")
  @OneToMany(mappedBy = "fieldScopeId", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  private List<FieldScopeEntry> entries = new ArrayList<>();
}
