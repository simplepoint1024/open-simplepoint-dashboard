package org.simplepoint.plugin.rbac.tenant.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.springframework.core.annotation.Order;

/**
 * Represents a tenant entity.
 */
@Data
@Entity
@Table(name = "simpoint_saas_tenants")
@EqualsAndHashCode(callSuper = true)
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMaxSize = 1,
        argumentMinSize = 0,
        authority = "tenants.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "tenants.edit"
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
        authority = "tenants.delete"
    ),
    @ButtonDeclaration(
        title = "i18n:tenants.button.config.package",
        key = "config.package",
        color = "orange",
        icon = Icons.SAFETY_OUTLINED,
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "tenants.config.package"
    )
})
@NoArgsConstructor
@AllArgsConstructor
@Tag(name = "租户对象", description = "用于管理系统中的租户")
@Schema(title = "租户对象", description = "用于管理系统中的租户")
public class Tenant extends BaseEntityImpl<String> {

  /** Tenant logo stored in OSS. */
  @Order(-2)
  @Schema(
      title = "i18n:tenants.title.logo",
      description = "i18n:tenants.description.logo",
      format = "data-url",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 1024)
  private String logo;

  /** Tenant home-page background stored in OSS. */
  @Order(-1)
  @Schema(
      title = "i18n:tenants.title.backgroundImage",
      description = "i18n:tenants.description.backgroundImage",
      format = "data-url",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false"),
          })
      }
  )
  @Column(length = 1024)
  private String backgroundImage;

  /**
   * The name of the tenant.
   */
  @Order(0)
  @Schema(
      title = "i18n:tenants.title.name",
      description = "i18n:tenants.description.name",
      example = "default",
      maxLength = 128,
      minLength = 1,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 128, nullable = false, unique = true)
  private String name;

  /**
   * The description of the tenant.
   */
  @Order(3)
  @Schema(
      title = "i18n:tenants.title.description",
      description = "i18n:tenants.description.description",
      example = "Default tenant",
      maxLength = 512,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Column(length = 512)
  private String description;

  /**
   * Version marker for tenant-scoped authorization snapshots.
   */
  @Column(name = "authorization_version", nullable = false)
  @Schema(
      title = "i18n:tenants.title.authorizationVersion",
      description = "i18n:tenants.description.authorizationVersion",
      example = "0",
      hidden = true,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false"),
          })
      }
  )
  private Long authorizationVersion;

  /**
   * The ID of the tenant owner.
   * This field is used to identify the user who owns the tenant and has administrative privileges over it.
   */
  @Order(2)
  @Schema(
      title = "i18n:tenants.title.ownerId",
      description = "i18n:tenants.description.ownerId",
      example = "admin",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "false"),
              @ExtensionProperty(name = "widget", value = "UserPicker"),
              @ExtensionProperty(
                  name = "options",
                  value = "{\"selectionMode\":\"single\","
                      + "\"endpoint\":\"/common/users/picker/items\","
                      + "\"resolveEndpoint\":\"/common/users/picker/selected\","
                      + "\"pageSize\":20,\"debounceMs\":350,\"minSearchLength\":3}",
                  parseValue = true
              ),
          })
      }
  )
  @Column(nullable = false)
  private String ownerId;

  /** Decorated real name of the tenant administrator. */
  @Order(10)
  @Transient
  @Schema(
      title = "i18n:tenants.title.ownerName",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String ownerName;

  /** Decorated gender of the tenant administrator. */
  @Order(11)
  @Transient
  @Schema(
      title = "i18n:tenants.title.ownerGender",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String ownerGender;

  /** Decorated phone number of the tenant administrator. */
  @Order(12)
  @Transient
  @Schema(
      title = "i18n:tenants.title.ownerPhoneNumber",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String ownerPhoneNumber;

  /** Decorated email address of the tenant administrator. */
  @Order(13)
  @Transient
  @Schema(
      title = "i18n:tenants.title.ownerEmail",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  private String ownerEmail;

  /** Decorated avatar of the tenant administrator. */
  @Transient
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private String ownerPicture;

  /** Whether the current actor may edit the current tenant profile. */
  @Transient
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  private Boolean profileEditable;

  /**
   * The type of the tenant (PERSONAL or ORGANIZATION).
   */
  @Order(1)
  @Schema(
      title = "i18n:tenants.title.tenantType",
      description = "i18n:tenants.description.tenantType",
      example = "ORGANIZATION",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "x-list-visible", value = "true"),
          })
      }
  )
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private TenantType tenantType;

  /**
   * Lifecycle callback to set default values before persisting the entity.
   */
  @Override
  public void prePersist() {
    if (this.tenantType == null) {
      this.tenantType = TenantType.ORGANIZATION;
    }
    super.prePersist();
  }
}
