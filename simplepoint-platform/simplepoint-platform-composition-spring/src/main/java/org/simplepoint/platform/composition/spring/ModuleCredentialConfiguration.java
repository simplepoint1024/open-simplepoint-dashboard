package org.simplepoint.platform.composition.spring;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.invocation.ClientCredentialsServiceRouterAccessTokenProvider;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import java.util.LinkedHashMap;
import java.util.List;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Builds only credentials for selected modules, without issuing tokens or broadening grants. */
@Configuration(proxyBeanMethods = false)
public class ModuleCredentialConfiguration {

  /** Module defaults contain no secrets; the installation supplies credentials externally. */
  @Bean
  @Primary
  @ConditionalOnMissingBean(ModuleServiceCredentials.class)
  public ModuleServiceCredentials moduleServiceCredentials(
      ModulePlan plan, ServiceRouterProperties routing, RestClient http, Environment environment) {
    if (!"oauth2".equalsIgnoreCase(routing.getInternalAuth().getMode())) {
      throw new ModuleCompositionException(
          "MODULE_OAUTH_REQUIRED", "Configure OAuth2 service credentials");
    }
    var providers = new LinkedHashMap<String, ServiceRouterAccessTokenProvider>();
    var overrides = new LinkedHashMap<String, String>();
    for (var module : plan.modules()) {
      String client =
          environment.getProperty("simplepoint.modules.credentials." + module.id() + ".client-id");
      if (client != null) {
        overrides.put(module.id(), client);
      }
    }
    var clientIds = ModuleClientPlan.identities(plan, overrides);
    for (var module : plan.modules()) {
      String prefix = "simplepoint.modules.credentials." + module.id() + ".";
      String secret = environment.getProperty(prefix + "client-secret");
      if (!StringUtils.hasText(secret)) {
        if (!module.consumes().isEmpty()) {
          throw new ModuleCompositionException("MISSING_MODULE_CREDENTIALS", module.id());
        }
        continue;
      }
      var oauth = new ServiceRouterProperties.InternalAuth.Oauth2();
      oauth.setTokenUri(routing.getInternalAuth().getOauth2().getTokenUri());
      oauth.setClientId(clientIds.get(module.id()));
      oauth.setClientSecret(secret);
      oauth.setScopes(List.of("service-router.invoke"));
      oauth.setTokenRefreshSkew(routing.getInternalAuth().getOauth2().getTokenRefreshSkew());
      if (!StringUtils.hasText(oauth.getTokenUri()) || !StringUtils.hasText(oauth.getClientId())) {
        throw new ModuleCompositionException("INVALID_MODULE_CREDENTIALS", module.id());
      }
      providers.put(
          module.id(), new ClientCredentialsServiceRouterAccessTokenProvider(http, oauth));
    }
    return new ModuleServiceCredentials(providers);
  }
}
