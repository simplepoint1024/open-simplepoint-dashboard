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
import org.springframework.core.annotation.Order;
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
        title = "添加", key = "add", icon = "PlusCircleOutlined", sort = 0, argumentMaxSize = 0, argumentMinSize = 0
    ),
    @ButtonDeclaration(
        title = "编辑", key = "edit", color = "orange", icon = "EditOutlined", sort = 1,
        argumentMinSize = 1, argumentMaxSize = 1
    ),
    @ButtonDeclaration(
        title = "删除", key = "delete", color = "danger", icon = "MinusCircleOutlined", sort = 2,
        argumentMinSize = 1, argumentMaxSize = 10, danger = true
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
  @Schema(title = "用户名", description = "用户的唯一标识符", extensions = {
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
  @Schema(title = "密码", description = "用户的登录密码", extensions = {
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
  @Schema(maxLength = 64, minLength = 5, title = "邮箱", description = "用户的电子邮件地址", extensions = {
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
  @Schema(title = "地址", description = "用户的地址信息", maxLength = 255, minLength = 5)
  private String address;

  /**
   * The birthdate of the user.
   * This field contains the user's date of birth.
   */
  @Schema(title = "出生日期", description = "用户的出生日期", type = "string", format = "date-time")
  private Instant birthdate;

  /**
   * Indicates whether the user's email is verified.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY, title = "邮箱是否验证", description = "指示用户的电子邮件是否已验证")
  private Boolean emailVerified;

  /**
   * The family name (last name) of the user.
   */
  @Schema(title = "姓氏", description = "用户的姓氏", maxLength = 50, minLength = 1)
  private String familyName;

  /**
   * The gender of the user.
   * This field specifies the user's gender.
   */
  @Schema(hidden = true, title = "性别", description = "用户的性别", maxLength = 10, minLength = 1, extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String gender;

  /**
   * The given name (first name) of the user.
   */
  @Schema(maxLength = 50, minLength = 1, title = "名字", description = "用户的名字")
  private String givenName;

  /**
   * The locale of the user.
   * This field specifies the user's preferred language or region.
   */
  @Order(5)
  @Schema(hidden = true, title = "区域设置", description = "用户的语言或地区偏好", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String locale;

  /**
   * The middle name of the user.
   */
  @Schema(title = "中间名", description = "用户的中间名", maxLength = 50, minLength = 1)
  private String middleName;

  /**
   * The full name of the user.
   */
  @Schema(hidden = true, title = "姓名", description = "用户的全名", maxLength = 100, minLength = 1)
  private String name;

  /**
   * The nickname of the user.
   */
  @Order(2)
  @Schema(maxLength = 50, minLength = 1, title = "昵称", description = "用户的昵称", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private String nickname;

  /**
   * The URL or path to the user's profile picture.
   */
  @Schema(title = "头像", description = "用户的头像图片URL或路径", format = "data-url")
  private String picture;

  /**
   * The phone number of the user.
   */
  @Order(3)
  @Schema(maxLength = 50, minLength = 5, title = "手机号", description = "用户的联系电话", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  @Column(length = 18)
  private String phoneNumber;

  /**
   * Indicates whether the user's phone number is verified.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY, title = "手机号是否验证", description = "指示用户的联系电话是否已验证")
  private Boolean phoneNumberVerified;

  /**
   * The preferred username of the user.
   * This field is typically used for display purposes.
   */
  @Schema(hidden = true, title = "首选用户名", description = "用户的首选用户名")
  private String preferredUsername;

  /**
   * The URL or path to the user's profile.
   */
  @URL
  @Schema(title = "个人资料", description = "用户的个人资料URL或路径")
  private String profile;

  /**
   * The personal or business website of the user.
   */
  @Schema(hidden = true, title = "网站", description = "用户的个人或商业网站URL")
  private String website;

  /**
   * The time zone information of the user.
   */
  @Schema(hidden = true, title = "时区", description = "用户的时区信息")
  private String zoneinfo;

  /**
   * 指示账户是否启用
   * Indicates whether the account is enabled.
   */
  @Schema(accessMode = Schema.AccessMode.READ_ONLY, title = "是否启用", description = "指示账户是否启用", extensions = {
      @Extension(name = "x-ui", properties = {
          @ExtensionProperty(name = "x-list-visible", value = "true"),
      })
  })
  private Boolean enabled;

  /**
   * 指示账户是否未过期
   * Indicates whether the account is non-expired.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY, title = "是否过期", description = "指示账户是否未过期")
  private Boolean accountNonExpired;

  /**
   * 指示账户是否未被锁定
   * Indicates whether the account is non-locked.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY, title = "是否锁定", description = "指示账户是否未被锁定")
  private Boolean accountNonLocked;

  /**
   * 指示凭据是否未过期
   * Indicates whether the credentials are non-expired.
   */
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY, title = "凭据是否过期", description = "指示凭据是否未过期")
  private Boolean credentialsNonExpired;

  @Schema(title = "是否为管理员", description = "指示用户是否具有管理员权限", extensions = {
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
  private Collection<SimpleGrantedAuthority> authorities;

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

