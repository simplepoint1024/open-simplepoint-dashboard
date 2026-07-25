package org.simplepoint.plugin.oidc.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.oidc.api.constants.ExternalIdentityProviderPaths;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderConnectionTestResult;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Management API for external OAuth2 and OpenID Connect identity providers. */
@RestController
@RequestMapping(ExternalIdentityProviderPaths.BASE)
@Tag(name = "外部身份提供商", description = "管理平台接入的外部 OAuth2/OIDC 登录方式")
public class ExternalIdentityProviderController
    extends BaseController<
        ExternalIdentityProviderService,
        ExternalIdentityProvider,
        String
    > {

  /**
   * Creates the controller.
   *
   * @param service identity-provider service
   */
  public ExternalIdentityProviderController(
      final ExternalIdentityProviderService service
  ) {
    super(service);
  }

  /**
   * Returns provider configurations without plaintext credentials.
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('identityProviders.view')")
  @Operation(summary = "分页查询外部身份提供商")
  public Response<Page<ExternalIdentityProvider>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(
        service.limit(attributes, pageable),
        ExternalIdentityProvider.class
    );
  }

  /**
   * Creates a provider and encrypts its client secret.
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('identityProviders.create')")
  @Operation(summary = "新增外部身份提供商")
  public Response<ExternalIdentityProvider> add(
      @RequestBody final ExternalIdentityProvider provider
  ) {
    return ok(service.create(provider));
  }

  /**
   * Updates a provider; a blank secret keeps the current encrypted credential.
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('identityProviders.edit')")
  @Operation(summary = "修改外部身份提供商")
  public Response<ExternalIdentityProvider> modify(
      @RequestBody final ExternalIdentityProvider provider
  ) {
    return ok(service.modifyById(provider));
  }

  /**
   * Soft-deletes providers that have no account bindings.
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('identityProviders.delete')")
  @Operation(summary = "删除外部身份提供商")
  public Response<Set<String>> remove(@RequestParam("ids") final String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  /**
   * Resolves OIDC discovery metadata or validates explicit OAuth2 endpoints.
   */
  @PostMapping("/{id}/test")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('identityProviders.test')")
  @Operation(summary = "测试外部身份提供商配置")
  public Response<ExternalIdentityProviderConnectionTestResult> test(
      @PathVariable("id") final String id
  ) {
    return ok(service.testConnection(id));
  }
}
