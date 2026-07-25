package org.simplepoint.plugin.oidc.api.service;

import java.util.List;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderConnectionTestResult;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderView;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;

/** Manages external OAuth2/OIDC identity providers and resolves runtime registrations. */
public interface ExternalIdentityProviderService
    extends BaseService<ExternalIdentityProvider, String> {

  /** Lists credential-free providers shown on the login page. */
  List<ExternalIdentityProviderView> enabledProviders();

  /** Resolves and decrypts one enabled provider for trusted runtime use. */
  ResolvedExternalIdentityProvider resolve(String registrationId);

  /** Validates endpoints and resolves OIDC discovery metadata. */
  ExternalIdentityProviderConnectionTestResult testConnection(String id);
}
