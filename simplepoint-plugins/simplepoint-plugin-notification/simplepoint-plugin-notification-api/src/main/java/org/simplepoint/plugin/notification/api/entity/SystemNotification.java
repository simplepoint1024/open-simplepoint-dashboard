package org.simplepoint.plugin.notification.api.entity;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.notification.api.constants.NotificationDictionaryCodes;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationCategory;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.model.NotificationStatus;
import org.springframework.core.annotation.Order;

/** A centrally managed notification with a selector-based audience. */
@Data
@Entity
@Table(name = "simpoint_system_notifications", indexes = {
    @Index(name = "idx_simpoint_notification_delivery",
        columnList = "status, publish_at, expire_at"),
    @Index(name = "idx_simpoint_notification_audience",
        columnList = "audience_type, audience_id")
})
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ButtonDeclarations({
    @ButtonDeclaration(title = PublicButtonKeys.ADD_TITLE, key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE, sort = 0, argumentMinSize = 0, argumentMaxSize = 1,
        authority = "notifications.create"),
    @ButtonDeclaration(title = PublicButtonKeys.EDIT_TITLE, key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT, color = "orange", sort = 1, argumentMinSize = 1,
        argumentMaxSize = 1, authority = "notifications.edit"),
    @ButtonDeclaration(title = "i18n:notifications.button.publish", key = "publish",
        icon = "SendOutlined", sort = 2, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "notifications.publish"),
    @ButtonDeclaration(title = "i18n:notifications.button.revoke", key = "revoke",
        icon = "StopOutlined", color = "orange", sort = 3, argumentMinSize = 1,
        argumentMaxSize = 1, authority = "notifications.revoke"),
    @ButtonDeclaration(title = "i18n:notifications.button.duplicate", key = "duplicate",
        icon = "CopyOutlined", sort = 4, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "notifications.duplicate"),
    @ButtonDeclaration(title = PublicButtonKeys.DELETE_TITLE, key = PublicButtonKeys.DELETE_KEY,
        icon = Icons.MINUS_CIRCLE, color = "danger", danger = true, sort = 5,
        argumentMinSize = 1, argumentMaxSize = 10, authority = "notifications.delete")
})
@Schema(title = "i18n:notifications.entity.title")
public class SystemNotification extends BaseEntityImpl<String> {

  @Version
  @Schema(hidden = true)
  private Long version;

  @Order(0)
  @Schema(title = "i18n:notifications.title.title", minLength = 1, maxLength = 200,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(length = 200, nullable = false)
  private String title;

  @Order(1)
  @Schema(title = "i18n:notifications.title.content", minLength = 1, maxLength = 8000,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "widget", value = "textarea")))
  @Column(length = 8000, nullable = false)
  private String content;

  @Order(2)
  @DictionaryField(NotificationDictionaryCodes.CATEGORY)
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:notifications.title.category",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private NotificationCategory category;

  @Order(3)
  @DictionaryField(NotificationDictionaryCodes.PRIORITY)
  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  @Schema(title = "i18n:notifications.title.priority",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private NotificationPriority priority;

  @Order(4)
  @DictionaryField(NotificationDictionaryCodes.AUDIENCE_TYPE)
  @Enumerated(EnumType.STRING)
  @Column(name = "audience_type", length = 16, nullable = false)
  @Schema(title = "i18n:notifications.title.audienceType",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private NotificationAudienceType audienceType;

  @Order(5)
  @Column(name = "audience_id", length = 64)
  @Schema(title = "i18n:notifications.title.audienceId", maxLength = 64)
  private String audienceId;

  @Order(6)
  @Schema(title = "i18n:notifications.title.linkUrl", maxLength = 1024)
  @Column(name = "link_url", length = 1024)
  private String linkUrl;

  @Order(7)
  @Schema(title = "i18n:notifications.title.publishAt",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  @Column(name = "publish_at")
  private Instant publishAt;

  @Order(8)
  @Schema(title = "i18n:notifications.title.expireAt")
  @Column(name = "expire_at")
  private Instant expireAt;

  @Order(9)
  @DictionaryField(NotificationDictionaryCodes.STATUS)
  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  @Schema(title = "i18n:notifications.title.status",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private NotificationStatus status;

  @Schema(title = "i18n:notifications.title.publishedAt",
      accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "published_at")
  private Instant publishedAt;
}
