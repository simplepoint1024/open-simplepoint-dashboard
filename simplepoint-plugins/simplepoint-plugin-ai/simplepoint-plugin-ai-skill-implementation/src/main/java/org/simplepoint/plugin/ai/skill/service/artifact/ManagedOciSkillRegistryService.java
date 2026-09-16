package org.simplepoint.plugin.ai.skill.service.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.skill.api.model.SkillManagedRegistryStatus;
import org.simplepoint.plugin.ai.skill.api.service.SkillManagedRegistryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Bounded, credential-safe managed OCI Registry control-plane client. */
@Service
public class ManagedOciSkillRegistryService
    implements SkillManagedRegistryService {

  private static final Pattern AUTH_PARAMETER = Pattern.compile(
      "([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\""
  );

  private final SkillArtifactProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient client;

  /** Creates the managed Registry client. */
  public ManagedOciSkillRegistryService(
      final SkillArtifactProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.client = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public SkillManagedRegistryStatus describe() {
    boolean configured = Boolean.TRUE.equals(
        properties.getManagedRegistryEnabled()
    );
    String registry = trim(properties.getManagedRegistry());
    String prefix = trim(properties.getManagedRepositoryPrefix());
    return new SkillManagedRegistryStatus(
        configured,
        registry,
        prefix,
        !isInsecure(registry),
        hasCredentials(),
        Boolean.TRUE.equals(properties.getSignatureRequired()),
        null,
        null,
        configured
            ? "SKILL_REGISTRY_CHECK_REQUIRED"
            : "SKILL_REGISTRY_NOT_CONFIGURED"
    );
  }

  @Override
  public SkillManagedRegistryStatus checkConnectivity() {
    RegistryTarget target = requireTarget();
    URI endpoint = URI.create(
        target.scheme() + "://" + target.registry() + "/v2/"
    );
    HttpResponse<String> response = send(request(endpoint, null));
    if (response.statusCode() == 401) {
      String challenge = response.headers()
          .firstValue("WWW-Authenticate")
          .orElse("");
      response = send(request(
          endpoint,
          authorization(challenge, target)
      ));
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Managed OCI Registry connectivity check returned HTTP "
              + response.statusCode()
      );
    }
    return new SkillManagedRegistryStatus(
        true,
        target.registry(),
        target.repositoryPrefix(),
        "https".equals(target.scheme()),
        hasCredentials(),
        Boolean.TRUE.equals(properties.getSignatureRequired()),
        true,
        Instant.now(),
        "SKILL_REGISTRY_CONNECTED"
    );
  }

  private RegistryTarget requireTarget() {
    if (!Boolean.TRUE.equals(properties.getManagedRegistryEnabled())) {
      throw new IllegalStateException("Managed OCI Registry is not configured");
    }
    String registry = trim(properties.getManagedRegistry())
        .toLowerCase(Locale.ROOT);
    String prefix = trim(properties.getManagedRepositoryPrefix())
        .toLowerCase(Locale.ROOT);
    OciSkillArtifactReference parsed = OciSkillArtifactReference.parse(
        registry + "/" + prefix + "/connectivity-check:probe"
    );
    if (!parsed.registry().equals(registry)) {
      throw new IllegalArgumentException("Managed OCI Registry is invalid");
    }
    if (!properties.getAllowedRegistries().stream()
        .map(ManagedOciSkillRegistryService::normalize)
        .anyMatch(registry::equals)) {
      throw new IllegalArgumentException(
          "Managed OCI Registry is not in the allowed Registry list"
      );
    }
    if (StringUtils.hasText(properties.getRegistryUsername())
        != StringUtils.hasText(properties.getRegistryPassword())) {
      throw new IllegalStateException(
          "Managed OCI Registry username and password must be configured together"
      );
    }
    return new RegistryTarget(
        registry,
        prefix,
        isInsecure(registry) ? "http" : "https"
    );
  }

  private HttpRequest request(final URI uri, final String authorization) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .GET();
    if (StringUtils.hasText(authorization)) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  private HttpResponse<String> send(final HttpRequest request) {
    try {
      return client.send(
          request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
      );
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Managed OCI Registry is unreachable",
          ex
      );
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Managed OCI Registry connectivity check was interrupted",
          ex
      );
    }
  }

  private String authorization(
      final String challenge,
      final RegistryTarget target
  ) {
    if (challenge.regionMatches(true, 0, "Basic ", 0, 6)) {
      return basicAuthorization();
    }
    if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new IllegalStateException(
          "Managed OCI Registry authentication challenge is unsupported"
      );
    }
    Map<String, String> parameters = authParameters(challenge.substring(7));
    String realm = parameters.get("realm");
    if (!StringUtils.hasText(realm)) {
      throw new IllegalStateException(
          "Managed OCI Registry Bearer challenge has no realm"
      );
    }
    URI tokenUri = tokenUri(realm, parameters, target);
    HttpResponse<String> response = send(request(
        tokenUri,
        hasCredentials() ? basicAuthorization() : null
    ));
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Managed OCI Registry token service returned HTTP "
              + response.statusCode()
      );
    }
    try {
      JsonNode body = objectMapper.readTree(response.body());
      String token = body.path("token").asText("");
      if (!StringUtils.hasText(token)) {
        token = body.path("access_token").asText("");
      }
      if (!StringUtils.hasText(token)) {
        throw new IllegalStateException(
            "Managed OCI Registry token service returned no token"
        );
      }
      return "Bearer " + token;
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Managed OCI Registry token response is invalid",
          ex
      );
    }
  }

  private URI tokenUri(
      final String realm,
      final Map<String, String> parameters,
      final RegistryTarget target
  ) {
    URI base = URI.create(realm);
    String host = base.getHost() == null
        ? "" : base.getHost().toLowerCase(Locale.ROOT);
    int port = base.getPort();
    String authority = port < 0 ? host : host + ":" + port;
    if (!properties.getAllowedTokenHosts().stream()
        .map(ManagedOciSkillRegistryService::normalize)
        .anyMatch(authority::equals)) {
      throw new IllegalStateException(
          "Managed OCI Registry token host is not allowed"
      );
    }
    boolean secure = "https".equalsIgnoreCase(base.getScheme());
    if (!secure && !isInsecure(authority)) {
      throw new IllegalStateException(
          "Managed OCI Registry token service must use HTTPS"
      );
    }
    String service = parameters.get("service");
    String scope = "repository:" + target.repositoryPrefix()
        + "/connectivity-check:pull,push";
    String separator = StringUtils.hasText(base.getQuery()) ? "&" : "?";
    return URI.create(base + separator
        + (StringUtils.hasText(service)
            ? "service=" + encode(service) + "&" : "")
        + "scope=" + encode(scope));
  }

  private String basicAuthorization() {
    if (!hasCredentials()) {
      throw new IllegalStateException(
          "Managed OCI Registry credentials are not configured"
      );
    }
    String raw = properties.getRegistryUsername()
        + ":" + properties.getRegistryPassword();
    return "Basic " + Base64.getEncoder().encodeToString(
        raw.getBytes(StandardCharsets.UTF_8)
    );
  }

  private boolean hasCredentials() {
    return StringUtils.hasText(properties.getRegistryUsername())
        && StringUtils.hasText(properties.getRegistryPassword());
  }

  private boolean isInsecure(final String registry) {
    String normalized = normalize(registry);
    return properties.getInsecureRegistries().stream()
        .map(ManagedOciSkillRegistryService::normalize)
        .anyMatch(normalized::equals);
  }

  private static Map<String, String> authParameters(final String value) {
    Map<String, String> parameters = new HashMap<>();
    Matcher matcher = AUTH_PARAMETER.matcher(value);
    while (matcher.find()) {
      parameters.put(
          matcher.group(1).toLowerCase(Locale.ROOT),
          matcher.group(2)
      );
    }
    return parameters;
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String trim(final String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalize(final String value) {
    return trim(value).toLowerCase(Locale.ROOT);
  }

  private record RegistryTarget(
      String registry,
      String repositoryPrefix,
      String scheme
  ) {
  }
}
