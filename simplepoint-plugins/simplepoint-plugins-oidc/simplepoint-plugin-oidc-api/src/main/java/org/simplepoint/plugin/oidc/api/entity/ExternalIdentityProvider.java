package org.simplepoint.plugin.oidc.api.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.plugin.oidc.api.constants.ExternalIdentityProviderDictionaryCodes;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.springframework.core.annotation.Order;

/** System-global configuration for an external OAuth2 or OpenID Connect identity provider. */
@Data
@Entity
@Table(name = "simpoint_ac_external_identity_provider", indexes = {
    @Index(name = "idx_external_idp_registration", columnList = "registration_id"),
    @Index(name = "idx_external_idp_enabled_sort", columnList = "enabled, sort_order")
})
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Tag(name = "外部身份提供商", description = "管理平台作为客户端接入的 OAuth2/OIDC 身份提供商")
@Schema(title = "i18n:external-idp.entity.title")
@ButtonDeclarations({
    @ButtonDeclaration(title = PublicButtonKeys.ADD_TITLE, key = PublicButtonKeys.ADD_KEY,
        icon = Icons.PLUS_CIRCLE, sort = 0, argumentMinSize = 0, argumentMaxSize = 1,
        authority = "identityProviders.create"),
    @ButtonDeclaration(title = PublicButtonKeys.EDIT_TITLE, key = PublicButtonKeys.EDIT_KEY,
        icon = Icons.EDIT, color = "orange", sort = 1, argumentMinSize = 1,
        argumentMaxSize = 1, authority = "identityProviders.edit"),
    @ButtonDeclaration(title = "i18n:external-idp.button.test", key = "test",
        icon = "ApiOutlined", sort = 2, argumentMinSize = 1, argumentMaxSize = 1,
        authority = "identityProviders.test"),
    @ButtonDeclaration(title = PublicButtonKeys.DELETE_TITLE, key = PublicButtonKeys.DELETE_KEY,
        icon = Icons.MINUS_CIRCLE, color = "danger", danger = true, sort = 3,
        argumentMinSize = 1, argumentMaxSize = 10, authority = "identityProviders.delete")
})
public class ExternalIdentityProvider extends BaseEntityImpl<String> {

  @Order(0)
  @Column(name = "registration_id", length = 64, nullable = false, unique = true)
  @Schema(title = "i18n:external-idp.title.registrationId",
      description = "i18n:external-idp.description.registrationId",
      minLength = 2, maxLength = 64,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String registrationId;

  @Order(1)
  @Column(name = "display_name", length = 128, nullable = false)
  @Schema(title = "i18n:external-idp.title.displayName", minLength = 1, maxLength = 128,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private String displayName;

  @Order(2)
  @DictionaryField(ExternalIdentityProviderDictionaryCodes.PRESET)
  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  @Schema(title = "i18n:external-idp.title.preset",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private ExternalIdentityProviderPreset preset;

  @Order(3)
  @DictionaryField(ExternalIdentityProviderDictionaryCodes.PROTOCOL)
  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  @Schema(title = "i18n:external-idp.title.protocol")
  private ExternalIdentityProviderProtocol protocol;

  @Order(4)
  @Column(name = "issuer_uri", length = 2048)
  @Schema(title = "i18n:external-idp.title.issuerUri",
      description = "i18n:external-idp.description.issuerUri", maxLength = 2048)
  private String issuerUri;

  @Order(5)
  @Column(name = "authorization_uri", length = 2048)
  @Schema(title = "i18n:external-idp.title.authorizationUri", maxLength = 2048)
  private String authorizationUri;

  @Order(6)
  @Column(name = "token_uri", length = 2048)
  @Schema(title = "i18n:external-idp.title.tokenUri", maxLength = 2048)
  private String tokenUri;

  @Order(7)
  @Column(name = "user_info_uri", length = 2048)
  @Schema(title = "i18n:external-idp.title.userInfoUri", maxLength = 2048)
  private String userInfoUri;

  @Order(8)
  @Column(name = "jwk_set_uri", length = 2048)
  @Schema(title = "i18n:external-idp.title.jwkSetUri", maxLength = 2048)
  private String jwkSetUri;

  @Order(9)
  @Column(name = "client_id", length = 512, nullable = false)
  @Schema(title = "i18n:external-idp.title.clientId", minLength = 1, maxLength = 512)
  private String clientId;

  @Order(10)
  @Transient
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Schema(title = "i18n:external-idp.title.clientSecret",
      description = "i18n:external-idp.description.clientSecret",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "widget", value = "password")))
  private String clientSecret;

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Column(name = "client_secret_ciphertext", length = 4096)
  @Schema(hidden = true)
  private String clientSecretCiphertext;

  @Order(11)
  @DictionaryField(ExternalIdentityProviderDictionaryCodes.CLIENT_AUTHENTICATION_METHOD)
  @Column(name = "client_authentication_method", length = 64, nullable = false)
  @Schema(title = "i18n:external-idp.title.clientAuthenticationMethod")
  private String clientAuthenticationMethod;

  @Order(12)
  @Column(length = 2000, nullable = false)
  @Schema(title = "i18n:external-idp.title.scopes",
      description = "i18n:external-idp.description.scopes",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "widget", value = "textarea")))
  private String scopes;

  @Order(13)
  @Column(name = "user_name_attribute", length = 128, nullable = false)
  @Schema(title = "i18n:external-idp.title.userNameAttribute")
  private String userNameAttribute;

  @Order(14)
  @Column(name = "subject_claim", length = 128, nullable = false)
  @Schema(title = "i18n:external-idp.title.subjectClaim")
  private String subjectClaim;

  @Order(15)
  @Column(name = "email_claim", length = 128)
  @Schema(title = "i18n:external-idp.title.emailClaim")
  private String emailClaim;

  @Order(16)
  @Column(name = "email_verified_claim", length = 128)
  @Schema(title = "i18n:external-idp.title.emailVerifiedClaim")
  private String emailVerifiedClaim;

  @Order(17)
  @DictionaryField(ExternalIdentityProviderDictionaryCodes.MATCH_STRATEGY)
  @Enumerated(EnumType.STRING)
  @Column(name = "match_strategy", length = 32, nullable = false)
  @Schema(title = "i18n:external-idp.title.matchStrategy")
  private ExternalIdentityMatchStrategy matchStrategy;

  @Order(18)
  @Column(name = "require_verified_email", nullable = false)
  @Schema(title = "i18n:external-idp.title.requireVerifiedEmail",
      description = "i18n:external-idp.description.requireVerifiedEmail")
  private Boolean requireVerifiedEmail;

  @Order(19)
  @Column(name = "allow_private_network", nullable = false)
  @Schema(title = "i18n:external-idp.title.allowPrivateNetwork",
      description = "i18n:external-idp.description.allowPrivateNetwork")
  private Boolean allowPrivateNetwork;

  @Order(20)
  @Column(name = "sort_order", nullable = false)
  @Schema(title = "i18n:external-idp.title.sortOrder")
  private Integer sortOrder;

  @Order(21)
  @Column(nullable = false)
  @Schema(title = "i18n:external-idp.title.enabled",
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean enabled;

  @Order(22)
  @Column(length = 512)
  @Schema(title = "i18n:external-idp.title.description", maxLength = 512,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "widget", value = "textarea")))
  private String description;

  @Transient
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Schema(title = "i18n:external-idp.title.clientSecretConfigured",
      accessMode = Schema.AccessMode.READ_ONLY,
      extensions = @Extension(name = "x-ui", properties =
          @ExtensionProperty(name = "x-list-visible", value = "true")))
  private Boolean clientSecretConfigured;
}
