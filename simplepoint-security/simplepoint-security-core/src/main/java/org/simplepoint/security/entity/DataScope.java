/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;

/**
 * Represents a data scope policy that controls row-level access to data.
 * A data scope is attached to a role to restrict which rows of data the role's holders can see.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "simpoint_ac_data_scope")
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "data-scope.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "data-scope.edit"
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
        authority = "data-scope.delete"
    )
})
@Schema(title = "数据范围", description = "定义行级数据访问范围策略")
public class DataScope extends TenantBaseEntityImpl<String> {

  @Schema(title = "i18n:data-scopes.title.name", description = "i18n:data-scopes.description.name")
  @Column(nullable = false, length = 100)
  private String name;

  @Schema(title = "i18n:data-scopes.title.type", description = "i18n:data-scopes.description.type")
  @Column(nullable = false, length = 30)
  @Enumerated(EnumType.STRING)
  private DataScopeType type;

  @Schema(title = "i18n:data-scopes.title.customDeptIds", description = "i18n:data-scopes.description.customDeptIds")
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "simpoint_ac_data_scope_dept", joinColumns = @JoinColumn(name = "data_scope_id"))
  @Column(name = "dept_id", nullable = false)
  private Set<String> customDeptIds = new HashSet<>();

  @Schema(title = "i18n:data-scopes.title.description", description = "i18n:data-scopes.description.description")
  @Column(length = 200)
  private String description;
}
