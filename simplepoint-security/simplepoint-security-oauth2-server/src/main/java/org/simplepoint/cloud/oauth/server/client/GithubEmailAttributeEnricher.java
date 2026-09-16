package org.simplepoint.cloud.oauth.server.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Loads GitHub's primary verified email when it is not present in the basic user response. */
@Component
@Slf4j
public class GithubEmailAttributeEnricher {

  private static final String EMAILS_URI = "https://api.github.com/user/emails";

  private final RestClient restClient;

  /** Creates an enricher backed by the default REST client. */
  public GithubEmailAttributeEnricher() {
    this(RestClient.builder().build());
  }

  GithubEmailAttributeEnricher(final RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Enriches GitHub attributes with a verified primary email.
   */
  public Map<String, Object> enrich(
      final OAuth2UserRequest request,
      final ResolvedExternalIdentityProvider provider,
      final Map<String, Object> attributes
  ) {
    Map<String, Object> result = new LinkedHashMap<>(attributes);
    if (provider.preset() != ExternalIdentityProviderPreset.GITHUB) {
      return result;
    }
    List<Map<String, Object>> emails;
    try {
      emails = restClient.get()
          .uri(EMAILS_URI)
          .header(
              HttpHeaders.AUTHORIZATION,
              "Bearer " + request.getAccessToken().getTokenValue()
          )
          .accept(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Api-Version", "2022-11-28")
          .retrieve()
          .body(new ParameterizedTypeReference<>() {
          });
    } catch (RestClientException ex) {
      // A private-email permission issue must not discard an otherwise valid GitHub login.
      // The explicit binding flow can safely continue without trusting the email claim.
      log.warn("Unable to load verified GitHub email: {}", ex.getMessage());
      return result;
    }
    if (emails == null) {
      return result;
    }
    Map<String, Object> selected = emails.stream()
        .filter(item -> Boolean.TRUE.equals(item.get("verified")))
        .filter(item -> Boolean.TRUE.equals(item.get("primary")))
        .findFirst()
        .orElseGet(() -> emails.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("verified")))
            .findFirst()
            .orElse(null));
    if (selected != null && selected.get("email") != null) {
      result.put(provider.emailClaim(), selected.get("email"));
      if (provider.emailVerifiedClaim() != null) {
        result.put(provider.emailVerifiedClaim(), true);
      }
    }
    return result;
  }
}
