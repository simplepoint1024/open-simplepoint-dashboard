package org.simplepoint.plugin.ai.skill.service.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Secure OCI Distribution push client for generated Skill Artifacts. */
@Component
public class OciSkillArtifactPublisher {

  private static final Pattern AUTH_PARAMETER = Pattern.compile(
      "([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\""
  );

  private final SkillArtifactProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient client;

  /** Creates the bounded push client. */
  public OciSkillArtifactPublisher(
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

  /** Pushes missing blobs and atomically assigns the semantic version tag. */
  public PushedSkillOciArtifact push(
      final String skillCode,
      final String semanticVersion,
      final GeneratedSkillOciArtifact artifact
  ) {
    RegistryTarget target = requireTarget(skillCode, semanticVersion);
    pushBlob(
        target,
        artifact.configDigest(),
        artifact.config(),
        OciSkillArtifactVerifier.SKILL_CONFIG_MEDIA_TYPE
    );
    pushBlob(
        target,
        artifact.skillManifestDigest(),
        artifact.skillManifest(),
        OciSkillArtifactVerifier.SKILL_MANIFEST_MEDIA_TYPE
    );
    URI manifestUri = target.repositoryUri().resolve(
        "manifests/" + encodePath(semanticVersion)
    );
    RegistryResponse response = authorized(
        target,
        "PUT",
        manifestUri,
        artifact.ociManifest(),
        OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE,
        OciSkillArtifactVerifier.OCI_MANIFEST_MEDIA_TYPE
    );
    if (response.statusCode() != 201) {
      throw responseError("OCI manifest push", response.statusCode());
    }
    String registryDigest = response.headers()
        .firstValue("Docker-Content-Digest")
        .orElse("")
        .trim()
        .toLowerCase(Locale.ROOT);
    if (!artifact.ociManifestDigest().equals(registryDigest)) {
      throw new IllegalStateException(
          "OCI Registry returned a mismatched manifest digest"
      );
    }
    return new PushedSkillOciArtifact(
        target.registry() + "/" + target.repository()
            + ":" + semanticVersion,
        artifact.ociManifestDigest(),
        artifact.configDigest(),
        artifact.skillManifestDigest(),
        artifact.contentHash()
    );
  }

  private void pushBlob(
      final RegistryTarget target,
      final String digest,
      final byte[] content,
      final String mediaType
  ) {
    URI blobUri = target.repositoryUri().resolve("blobs/" + digest);
    RegistryResponse existing = authorized(
        target,
        "HEAD",
        blobUri,
        null,
        null,
        mediaType
    );
    if (existing.statusCode() == 200) {
      return;
    }
    if (existing.statusCode() != 404) {
      throw responseError("OCI blob existence check", existing.statusCode());
    }
    RegistryResponse started = authorized(
        target,
        "POST",
        target.repositoryUri().resolve("blobs/uploads/"),
        new byte[0],
        "application/octet-stream",
        "application/json"
    );
    if (started.statusCode() != 202) {
      throw responseError("OCI blob upload start", started.statusCode());
    }
    URI upload = validateUploadLocation(
        target,
        started.headers().firstValue("Location").orElse("")
    );
    String separator = StringUtils.hasText(upload.getQuery()) ? "&" : "?";
    URI completed = URI.create(
        upload + separator + "digest=" + encodeQuery(digest)
    );
    RegistryResponse uploaded = authorized(
        target,
        "PUT",
        completed,
        content,
        mediaType,
        "application/json"
    );
    if (uploaded.statusCode() != 201) {
      throw responseError("OCI blob upload", uploaded.statusCode());
    }
    String responseDigest = uploaded.headers()
        .firstValue("Docker-Content-Digest")
        .orElse(digest)
        .trim()
        .toLowerCase(Locale.ROOT);
    if (!digest.equals(responseDigest)) {
      throw new IllegalStateException(
          "OCI Registry returned a mismatched blob digest"
      );
    }
  }

  private RegistryResponse authorized(
      final RegistryTarget target,
      final String method,
      final URI uri,
      final byte[] body,
      final String contentType,
      final String accept
  ) {
    RegistryResponse response = send(request(
        method,
        uri,
        body,
        contentType,
        accept,
        null
    ));
    if (response.statusCode() != 401) {
      return response;
    }
    String challenge = response.headers()
        .firstValue("WWW-Authenticate")
        .orElse("");
    return send(request(
        method,
        uri,
        body,
        contentType,
        accept,
        authorization(challenge, target)
    ));
  }

  private HttpRequest request(
      final String method,
      final URI uri,
      final byte[] body,
      final String contentType,
      final String accept,
      final String authorization
  ) {
    HttpRequest.BodyPublisher publisher = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(body);
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(properties.getRequestTimeout())
        .method(method, publisher);
    if (StringUtils.hasText(contentType)) {
      builder.header("Content-Type", contentType);
    }
    if (StringUtils.hasText(accept)) {
      builder.header("Accept", accept);
    }
    if (StringUtils.hasText(authorization)) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  private RegistryResponse send(final HttpRequest request) {
    try {
      HttpResponse<InputStream> response = client.send(
          request,
          HttpResponse.BodyHandlers.ofInputStream()
      );
      try (InputStream body = response.body()) {
        byte[] content = body.readNBytes(64 * 1024 + 1);
        if (content.length > 64 * 1024) {
          throw new IllegalStateException(
              "OCI Registry response exceeds the configured limit"
          );
        }
        return new RegistryResponse(
            response.statusCode(),
            response.headers(),
            content
        );
      }
    } catch (IOException ex) {
      throw new IllegalStateException("OCI Registry request failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OCI Registry request was interrupted", ex);
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
          "OCI Registry authentication challenge is unsupported"
      );
    }
    Map<String, String> parameters = authParameters(challenge.substring(7));
    URI realm = validateTokenRealm(parameters.get("realm"));
    String expectedScope = "repository:" + target.repository() + ":pull,push";
    String scope = parameters.getOrDefault("scope", expectedScope);
    if (!expectedScope.equals(scope)) {
      throw new IllegalStateException(
          "OCI Registry token scope does not match the target repository"
      );
    }
    String query = "scope=" + encodeQuery(scope);
    if (StringUtils.hasText(parameters.get("service"))) {
      query += "&service=" + encodeQuery(parameters.get("service"));
    }
    URI tokenUri = URI.create(
        realm + (StringUtils.hasText(realm.getQuery()) ? "&" : "?") + query
    );
    RegistryResponse response = send(request(
        "GET",
        tokenUri,
        null,
        null,
        "application/json",
        hasCredentials() ? basicAuthorization() : null
    ));
    if (response.statusCode() != 200) {
      throw responseError("OCI Registry token request", response.statusCode());
    }
    try {
      JsonNode root = objectMapper.readTree(response.body());
      String token = root.path("token").asText("");
      if (!StringUtils.hasText(token)) {
        token = root.path("access_token").asText("");
      }
      if (!StringUtils.hasText(token) || token.length() > 16 * 1024) {
        throw new IllegalStateException(
            "OCI Registry token response is invalid"
        );
      }
      return "Bearer " + token;
    } catch (IOException ex) {
      throw new IllegalStateException(
          "OCI Registry token response is invalid",
          ex
      );
    }
  }

  private RegistryTarget requireTarget(
      final String skillCode,
      final String semanticVersion
  ) {
    if (!Boolean.TRUE.equals(properties.getManagedRegistryEnabled())) {
      throw new IllegalStateException("Managed OCI Registry is not configured");
    }
    String registry = normalize(properties.getManagedRegistry());
    String repository = normalize(properties.getManagedRepositoryPrefix())
        + "/" + normalize(skillCode);
    OciSkillArtifactReference reference = OciSkillArtifactReference.parse(
        registry + "/" + repository + ":" + semanticVersion
    );
    if (!allowed(properties.getAllowedRegistries()).contains(registry)) {
      throw new IllegalArgumentException(
          "Managed OCI Registry is not in the allowed Registry list"
      );
    }
    String scheme = allowed(properties.getInsecureRegistries())
        .contains(registry) ? "http" : "https";
    URI repositoryUri = URI.create(
        scheme + "://" + registry + "/v2/" + repository + "/"
    );
    return new RegistryTarget(
        reference.registry(),
        reference.repository(),
        repositoryUri
    );
  }

  private URI validateUploadLocation(
      final RegistryTarget target,
      final String location
  ) {
    if (!StringUtils.hasText(location)) {
      throw new IllegalStateException("OCI Registry upload Location is missing");
    }
    URI resolved = target.repositoryUri().resolve(location);
    String expectedPrefix = "/v2/" + target.repository()
        + "/blobs/uploads/";
    if (!target.repositoryUri().getScheme().equalsIgnoreCase(resolved.getScheme())
        || !target.repositoryUri().getAuthority()
        .equalsIgnoreCase(resolved.getAuthority())
        || resolved.getUserInfo() != null
        || resolved.getFragment() != null
        || !resolved.getPath().startsWith(expectedPrefix)) {
      throw new IllegalStateException(
          "OCI Registry upload Location is not allowed"
      );
    }
    return resolved;
  }

  private URI validateTokenRealm(final String value) {
    try {
      URI uri = URI.create(value == null ? "" : value);
      String authority = normalize(uri.getAuthority());
      boolean secure = "https".equalsIgnoreCase(uri.getScheme());
      boolean allowedInsecure = "http".equalsIgnoreCase(uri.getScheme())
          && allowed(properties.getInsecureRegistries()).contains(authority);
      if (uri.getHost() == null || uri.getUserInfo() != null
          || uri.getFragment() != null || !secure && !allowedInsecure
          || !allowed(properties.getAllowedTokenHosts()).contains(authority)) {
        throw new IllegalArgumentException("invalid realm");
      }
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "OCI Registry token service is not allowed"
      );
    }
  }

  private String basicAuthorization() {
    if (!hasCredentials()) {
      throw new IllegalStateException(
          "OCI Registry credentials are not configured"
      );
    }
    String raw = properties.getRegistryUsername().trim()
        + ":" + properties.getRegistryPassword();
    return "Basic " + Base64.getEncoder().encodeToString(
        raw.getBytes(StandardCharsets.UTF_8)
    );
  }

  private boolean hasCredentials() {
    return StringUtils.hasText(properties.getRegistryUsername())
        && StringUtils.hasText(properties.getRegistryPassword());
  }

  private static Map<String, String> authParameters(final String value) {
    Map<String, String> result = new HashMap<>();
    Matcher matcher = AUTH_PARAMETER.matcher(value);
    while (matcher.find()) {
      result.put(
          matcher.group(1).toLowerCase(Locale.ROOT),
          matcher.group(2)
      );
    }
    return result;
  }

  private static Set<String> allowed(final List<String> values) {
    return values.stream()
        .map(OciSkillArtifactPublisher::normalize)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String encodePath(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static String encodeQuery(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static IllegalStateException responseError(
      final String operation,
      final int status
  ) {
    return new IllegalStateException(operation + " returned HTTP " + status);
  }

  private record RegistryTarget(
      String registry,
      String repository,
      URI repositoryUri
  ) {
  }

  private record RegistryResponse(
      int statusCode,
      HttpHeaders headers,
      byte[] body
  ) {
  }
}
