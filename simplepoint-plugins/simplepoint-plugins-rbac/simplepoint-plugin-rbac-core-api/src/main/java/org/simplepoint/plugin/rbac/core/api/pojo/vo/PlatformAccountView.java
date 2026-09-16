package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.security.entity.PlatformRole;
import org.springframework.core.annotation.Order;

/** Read model and UI contract for the platform account control plane. */
@Schema(title = "i18n:platform-accounts.account", description = "i18n:platform-accounts.boundary")
@ButtonDeclarations({
    @ButtonDeclaration(
        title = PublicButtonKeys.ADD_TITLE,
        key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE,
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 1,
        authority = "platform.accounts.create"
    ),
    @ButtonDeclaration(
        title = PublicButtonKeys.EDIT_TITLE,
        key = PublicButtonKeys.EDIT_KEY,
        color = "orange",
        icon = Icons.EDIT,
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        authority = "platform.accounts.edit"
    ),
    @ButtonDeclaration(
        title = "i18n:platform-accounts.assign",
        key = "config.identity",
        color = "danger",
        icon = Icons.SAFETY_OUTLINED,
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = true,
        authority = "platform.identity.manage"
    )
})
public record PlatformAccountView(
    @Order(0)
    @Schema(title = "i18n:platform-accounts.id", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "false")))
    String id,
    @Order(1)
    @Schema(title = "i18n:platform-accounts.name", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "true")))
    String name,
    @Order(2)
    @Schema(title = "i18n:platform-accounts.email", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "true")))
    String email,
    @Order(3)
    @Schema(title = "i18n:platform-accounts.enabled", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "true")))
    boolean enabled,
    @Order(4)
    @Schema(title = "i18n:platform-accounts.root", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "false")))
    boolean superAdmin,
    @Order(5)
    @Schema(title = "i18n:platform-accounts.identity", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "true")))
    Set<PlatformRole> roles,
    @Order(6)
    @Schema(title = "i18n:platform-accounts.revision", accessMode = Schema.AccessMode.READ_ONLY,
        extensions = @Extension(name = "x-ui", properties =
            @ExtensionProperty(name = "x-list-visible", value = "false")))
    long revision
) { }
