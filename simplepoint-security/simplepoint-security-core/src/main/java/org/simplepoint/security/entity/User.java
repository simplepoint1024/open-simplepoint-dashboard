/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import java.time.Instant;
import java.util.Collection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Represents the User entity in the RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `rbac_users` table and contains user attributes
 * such as username, email, address, and profile details.
 */
@Data
@Entity
@Table(name = "security_users", indexes = {
    @Index(name = "idx_username", columnList = "username"),
    @Index(name = "idx_email", columnList = "email"),
    @Index(name = "idx_phone", columnList = "phone_number")
})
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "users:add"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "users:edit"
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
        authority = "users:delete"
    ),
    @ButtonDeclaration(
        title = "i18n:users.button.config.role",
        key = "config.role",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "users:config:role"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "用户对象", description = "用于管理系统中的用户")
public class User extends BaseEntityImpl<String> implements BaseUser {

  /**
   * The constant field name for the account identifier.
   * This is typically used for querying or identifying the user by their username.
   */
  public static final String ACCOUNT_FIELD = "username";

  /**
   * The username of the user.
   * This field uniquely identifies the user within the system.
   */
  @Order(1)
  @Schema(title = "i18n:users.title.username", description = "i18n:users.description.username", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String username;

  /**
   * The password of the user.
   * This is securely stored and used for authentication purposes.
   */
  @Order(2)
  @Schema(title = "i18n:users.title.password", description = "i18n:users.description.username", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "widget", value = "password"),
      })
  })
  private String password;

  /**
   * The email address of the user.
   * This field is used for communication and account recovery.
   */
  @Email
  @JsonProperty(index = 1)
  @Schema(
      maxLength = 64,
      minLength = 5,
      title = "i18n:users.title.email",
      description = "i18n:users.description.email",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(unique = true, nullable = false, length = 64)
  private String email;

  /**
   * The address of the user.
   * This field contains the user's physical address details.
   */
  @Schema(
      title = "i18n:users.title.address",
      description = "i18n:users.description.address",
      maxLength = 255,
      minLength = 5
  )
  private String address;

  /**
   * The birthdate of the user.
   * This field contains the user's date of birth.
   */
  @Schema(
      title = "i18n:users.title.birthdate",
      description = "i18n:users.description.birthdate",
      type = "string",
      format = "date-time"
  )
  private Instant birthdate;

  /**
   * Indicates whether the user's email is verified.
   */
  @Schema(
      hidden = true,
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.emailVerified",
      description = "i18n:users.description.emailVerified"
  )
  private Boolean emailVerified;

  /**
   * The family name (last name) of the user.
   */
  @Schema(
      title = "i18n:users.title.familyName",
      description = "i18n:users.description.familyName",
      maxLength = 50,
      minLength = 1
  )
  private String familyName;

  /**
   * The gender of the user.
   * This field specifies the user's gender.
   */
  @Schema(
      hidden = true,
      title = "i18n:users.title.gender",
      description = "i18n:users.description.gender",
      maxLength = 10,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String gender;

  /**
   * The given name (first name) of the user.
   */
  @Schema(
      maxLength = 50,
      minLength = 1,
      title = "i18n:users.title.givenName",
      description = "i18n:users.description.givenName"
  )
  private String givenName;

  /**
   * The locale of the user.
   * This field specifies the user's preferred language or region.
   */
  @Order(5)
  @Schema(
      hidden = true,
      title = "i18n:users.title.locale",
      description = "i18n:users.description.locale",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String locale;

  /**
   * The middle name of the user.
   */
  @Schema(
      title = "i18n:users.title.middleName",
      description = "i18n:users.description.middleName",
      maxLength = 50,
      minLength = 1
  )
  private String middleName;

  /**
   * The full name of the user.
   */
  @Schema(
      hidden = true,
      title = "i18n:users.title.name",
      description = "i18n:users.description.name",
      maxLength = 100,
      minLength = 1
  )
  private String name;

  /**
   * The nickname of the user.
   */
  @Order(2)
  @Schema(
      maxLength = 50,
      minLength = 1,
      title = "i18n:users.title.nickname",
      description = "i18n:users.description.nickname",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private String nickname;

  /**
   * The URL or path to the user's profile picture.
   */
  @Schema(title = "i18n:users.title.picture", description = "i18n:users.description.picture", format = "data-url")
  private String picture;

  /**
   * The phone number of the user.
   */
  @Order(3)
  @Schema(
      maxLength = 50,
      minLength = 5,
      title = "i18n:users.title.phoneNumber",
      description = "i18n:users.description.phoneNumber",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  @Column(length = 18)
  private String phoneNumber;

  /**
   * Indicates whether the user's phone number is verified.
   */
  @Schema(
      hidden = true,
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.phoneNumberVerified",
      description = "i18n:users.description.phoneNumberVerified"
  )
  private Boolean phoneNumberVerified;

  /**
   * The preferred username of the user.
   * This field is typically used for display purposes.
   */
  @Schema(hidden = true, title = "i18n:users.title.preferredUsername", description = "i18n:users.description.preferredUsername")
  private String preferredUsername;

  /**
   * The URL or path to the user's profile.
   */
  @URL
  @Schema(title = "i18n:users.title.profile", description = "i18n:users.description.profile")
  private String profile;

  /**
   * The personal or business website of the user.
   */
  @Schema(hidden = true, title = "i18n:users.title.website", description = "i18n:users.description.website")
  private String website;

  /**
   * The time zone information of the user.
   */
  @Schema(hidden = true, title = "i18n:users.title.zoneinfo", description = "i18n:users.description.zoneinfo")
  private String zoneinfo;

  /**
   * 指示账户是否启用
   * Indicates whether the account is enabled.
   */
  @Schema(
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.enabled",
      description = "i18n:users.description.enabled",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private Boolean enabled;

  /**
   * 指示账户是否未过期
   * Indicates whether the account is non-expired.
   */
  @Schema(
      hidden = true,
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.accountNonExpired",
      description = "i18n:users.description.accountNonExpired"
  )
  private Boolean accountNonExpired;

  /**
   * 指示账户是否未被锁定
   * Indicates whether the account is non-locked.
   */
  @Schema(
      hidden = true,
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.accountNonLocked",
      description = "i18n:users.description.accountNonLocked"
  )
  private Boolean accountNonLocked;

  /**
   * 指示凭据是否未过期
   * Indicates whether the credentials are non-expired.
   */
  @Schema(
      hidden = true,
      accessMode = Schema.AccessMode.READ_ONLY,
      title = "i18n:users.title.credentialsNonExpired",
      description = "i18n:users.description.credentialsNonExpired"
  )
  private Boolean credentialsNonExpired;

  @Schema(
      title = "i18n:users.title.superAdmin",
      description = "i18n:users.description.superAdmin",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      })
  private Boolean superAdmin;

  /**
   * 用户的授权集合，存储该用户的权限信息
   * A collection of authorities granted to the user.
   */
  @Schema(hidden = true)
  @Transient
  private Collection<GrantedAuthority> authorities;

  /**
   * 在实体持久化之前执行的回调方法
   * Callback method executed before persisting the entity.
   */
  @PrePersist
  public void prePersist() {
    /*
     * 如果 enabled 为空，默认设置为 true
     * Sets enabled to true if it is null.
     */
    if (this.enabled == null) {
      this.enabled = true;
    }

    /*
     * 如果 accountNonExpired 为空，默认设置为 true
     * Sets accountNonExpired to true if it is null.
     */
    if (this.accountNonExpired == null) {
      this.accountNonExpired = true;
    }

    /*
     * 如果 accountNonLocked 为空，默认设置为 true
     * Sets accountNonLocked to true if it is null.
     */
    if (this.accountNonLocked == null) {
      this.accountNonLocked = true;
    }

    /*
     * 如果 credentialsNonExpired 为空，默认设置为 true
     * Sets credentialsNonExpired to true if it is null.
     */
    if (this.credentialsNonExpired == null) {
      this.credentialsNonExpired = true;
    }

    /*
     * 如果 superAdmin 为空，默认设置为 false
     * Sets superAdmin to false if it is null.
     */
    if (this.superAdmin == null) {
      this.superAdmin = false;
    }
  }

  /**
   * 获取用户名
   * Gets the username.
   *
   * @return the username
   */
  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public Boolean superAdmin() {
    return this.superAdmin;
  }
}

