package org.simplepoint.plugin.ai.skill.service.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import org.simplepoint.plugin.ai.skill.api.model.VerifiedSkillArtifact;
import org.simplepoint.plugin.ai.skill.api.service.SkillArtifactVerifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Bounded OCI Distribution client and supply-chain verifier for Skill content.
 */
@Service
public class OciSkillArtifactVerifier implements SkillArtifactVerifier {

  static final String OCI_MANIFEST_MEDIA_TYPE =
      "application/vnd.oci.image.manifest.v1+json";

  static final String SKILL_ARTIFACT_TYPE =
      "application/vnd.simplepoint.skill.v1+json";

  static final String SKILL_CONFIG_MEDIA_TYPE =
      "application/vnd.simplepoint.skill.config.v1+json";

  static final String SKILL_MANIFEST_MEDIA_TYPE =
      "application/vnd.simplepoint.skill.manifest.v1+json";

  private static final Pattern DIGEST =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private static final Pattern AUTH_PARAMETER = Pattern.compile(
      "([A-Za-z][A-Za-z0-9_-]*)=\"([^\"]*)\""
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final SkillArtifactProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient registryClient;

  private final HttpClient verifierClient;

  /**
   * Creates the bounded Registry and verifier clients.
   */
  public OciSkillArtifactVerifier(
      final SkillArtifactProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    validateProperties();
    this.registryClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    HttpClient.Builder verifierBuilder = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER);
    if (Boolean.TRUE.equals(properties.getSignatureRequired())
        && Boolean.TRUE.equals(properties.getVerifierMtlsEnabled())) {
      SSLParameters sslParameters = new SSLParameters();
      sslParameters.setProtocols(new String[]{"TLSv1.3"});
      verifierBuilder
          .sslContext(SkillArtifactTlsContextFactory.create(properties))
          .sslParameters(sslParameters);
    }
    this.verifierClient = verifierBuilder.build();
  }

  @Override
  public VerifiedSkillArtifact verify(
      final String artifactReference,
      final String expectedDigest
  ) {
    OciSkillArtifactReference reference =
        OciSkillArtifactReference.parse(artifactReference);
    String digest = requireDigest(expectedDigest, "OCI artifact digest");
    if (reference.digestSelected() && !reference.selector().equals(digest)) {
      throw new IllegalArgumentException(
          "OCI reference digest does not match artifactDigest"
      );
    }
    assertAllowedRegistry(reference.registry());

    RegistryResponse manifestResponse = registryGet(
        reference,
        "manifests/" + reference.selector(),
        OCI_MANIFEST_MEDIA_TYPE,
        properties.getMaximumOciManifestBytes()
    );
    assertContentType(
        manifestResponse.contentType(),
        OCI_MANIFEST_MEDIA_TYPE,
        "OCI Artifact manifest"
    );
    assertDigest(
        manifestResponse.body(),
        digest,
        "OCI Artifact manifest"
    );
    String registryDigest = manifestResponse.digest();
    if (StringUtils.hasText(registryDigest)
        && !digest.equals(registryDigest.trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(
          "Registry manifest digest does not match artifactDigest"
      );
    }

    OciManifest ociManifest = read(
        manifestResponse.body(),
        OciManifest.class,
        "OCI Artifact manifest"
    );
    validateManifest(ociManifest);
    byte[] config = getBlob(
        reference,
        ociManifest.config(),
        properties.getMaximumConfigBytes(),
        "Skill Artifact config"
    );
    Descriptor contentDescriptor = ociManifest.layers().getFirst();
    byte[] content = getBlob(
        reference,
        contentDescriptor,
        properties.getMaximumSkillManifestBytes(),
        "Skill Manifest layer"
    );
    validateConfig(config, contentDescriptor.digest());
    Map<String, Object> skillManifest = read(
        content,
        MAP_TYPE,
        "Skill Manifest layer"
    );

    SignatureDecision signature = verifySignature(
        reference.digestReference(digest)
    );
    return new VerifiedSkillArtifact(
        digest,
        ociManifest.artifactType(),
        ociManifest.config().digest(),
        contentDescriptor.digest(),
        Collections.unmodifiableMap(new LinkedHashMap<>(skillManifest)),
        Boolean.TRUE.equals(properties.getSignatureRequired()),
        signature.verified(),
        signature.policyHash(),
        signature.checkedAt()
    );
  }

  private byte[] getBlob(
      final OciSkillArtifactReference reference,
      final Descriptor descriptor,
      final int limit,
      final String name
  ) {
    validateDescriptor(descriptor, limit, name);
    RegistryResponse response = registryGet(
        reference,
        "blobs/" + descriptor.digest(),
        descriptor.mediaType(),
        limit
    );
    assertBlobContentType(response.contentType(), descriptor.mediaType(), name);
    if (response.body().length != descriptor.size()) {
      throw new IllegalArgumentException(name + " size does not match descriptor");
    }
    assertDigest(response.body(), descriptor.digest(), name);
    return response.body();
  }

  private RegistryResponse registryGet(
      final OciSkillArtifactReference reference,
      final String path,
      final String accept,
      final int maximumBytes
  ) {
    URI uri = URI.create(
        registryScheme(reference.registry())
            + "://" + reference.registry()
            + "/v2/" + reference.repository() + "/" + path
    );
    HttpResponse<InputStream> response = send(
        registryClient,
        request(uri, accept, null),
        "OCI Registry"
    );
    if (response.statusCode() == 401) {
      String challenge = response.headers()
          .firstValue("WWW-Authenticate")
          .orElse("");
      close(response.body());
      response = send(
          registryClient,
          request(uri, accept, authorization(reference, challenge)),
          "OCI Registry"
      );
    }
    if (response.statusCode() != 200) {
      close(response.body());
      throw new IllegalArgumentException(
          "OCI Registry returned HTTP " + response.statusCode()
      );
    }
    byte[] body = readBounded(response.body(), maximumBytes, "OCI Registry");
    return new RegistryResponse(
        body,
        response.headers().firstValue("Content-Type").orElse(""),
        response.headers().firstValue("Docker-Content-Digest").orElse("")
    );
  }

  private String authorization(
      final OciSkillArtifactReference reference,
      final String challenge
  ) {
    if (challenge.regionMatches(true, 0, "Basic ", 0, 6)) {
      return basicAuthorization();
    }
    if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new IllegalArgumentException(
          "OCI Registry authentication challenge is unsupported"
      );
    }
    Map<String, String> parameters = new HashMap<>();
    Matcher matcher = AUTH_PARAMETER.matcher(challenge.substring(7));
    while (matcher.find()) {
      parameters.put(
          matcher.group(1).toLowerCase(Locale.ROOT),
          matcher.group(2)
      );
    }
    URI realm = parseTokenRealm(parameters.get("realm"));
    String expectedScope = "repository:" + reference.repository() + ":pull";
    String scope = parameters.getOrDefault("scope", expectedScope);
    if (!expectedScope.equals(scope)) {
      throw new IllegalArgumentException(
          "OCI Registry token scope is broader than repository pull"
      );
    }
    StringBuilder query = new StringBuilder("scope=")
        .append(encode(scope));
    if (StringUtils.hasText(parameters.get("service"))) {
      query.append("&service=").append(encode(parameters.get("service")));
    }
    URI tokenUri = URI.create(realm + (realm.getQuery() == null ? "?" : "&")
        + query);
    HttpRequest.Builder builder = HttpRequest.newBuilder(tokenUri)
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .GET();
    if (hasRegistryCredential()) {
      builder.header("Authorization", basicAuthorization());
    }
    HttpResponse<InputStream> response = send(
        registryClient,
        builder.build(),
        "OCI Registry token service"
    );
    if (response.statusCode() != 200) {
      close(response.body());
      throw new IllegalArgumentException(
          "OCI Registry token service returned HTTP " + response.statusCode()
      );
    }
    byte[] body = readBounded(
        response.body(),
        64 * 1024,
        "OCI Registry token service"
    );
    JsonNode root = read(body, JsonNode.class, "OCI Registry token response");
    String token = root.path("token").asText("");
    if (token.isBlank()) {
      token = root.path("access_token").asText("");
    }
    if (token.isBlank() || token.length() > 16 * 1024) {
      throw new IllegalArgumentException(
          "OCI Registry token response is invalid"
      );
    }
    return "Bearer " + token;
  }

  private SignatureDecision verifySignature(final String digestReference) {
    if (!Boolean.TRUE.equals(properties.getSignatureRequired())) {
      return new SignatureDecision(
          false,
          contentOnlyPolicyHash(),
          Instant.now()
      );
    }
    URI base = parseVerifierUri();
    URI uri = base.resolve("/internal/v1/artifacts/verify");
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(Map.of("artifact", digestReference));
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Artifact verifier request cannot be encoded",
          ex
      );
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<InputStream> response = send(
        verifierClient,
        request,
        "Artifact signature verifier"
    );
    if (response.statusCode() != 200) {
      close(response.body());
      throw new IllegalArgumentException(
          "Artifact signature verifier returned HTTP " + response.statusCode()
      );
    }
    SignatureResponse result = read(
        readBounded(
            response.body(),
            64 * 1024,
            "Artifact signature verifier"
        ),
        SignatureResponse.class,
        "Artifact signature verifier response"
    );
    if (!result.admitted() || !result.signatureVerified()) {
      throw new IllegalArgumentException(
          "OCI Artifact signature was rejected by supply-chain policy"
      );
    }
    return new SignatureDecision(
        true,
        requireDigest(result.policyHash(), "Verifier policy hash"),
        result.checkedAt() == null ? Instant.now() : result.checkedAt()
    );
  }

  private void validateManifest(final OciManifest manifest) {
    if (manifest.schemaVersion() != 2
        || !OCI_MANIFEST_MEDIA_TYPE.equals(manifest.mediaType())
        || !SKILL_ARTIFACT_TYPE.equals(manifest.artifactType())
        || manifest.config() == null
        || manifest.layers() == null
        || manifest.layers().size() != 1) {
      throw new IllegalArgumentException(
          "OCI Artifact manifest does not match the Skill media contract"
      );
    }
    if (!SKILL_CONFIG_MEDIA_TYPE.equals(manifest.config().mediaType())
        || !SKILL_MANIFEST_MEDIA_TYPE.equals(
            manifest.layers().getFirst().mediaType()
        )) {
      throw new IllegalArgumentException(
          "OCI Artifact descriptor media type is invalid"
      );
    }
  }

  private void validateConfig(
      final byte[] content,
      final String manifestDigest
  ) {
    JsonNode root = read(content, JsonNode.class, "Skill Artifact config");
    if (!root.isObject()
        || root.size() != 3
        || !"1.0".equals(root.path("schemaVersion").asText())
        || !SKILL_MANIFEST_MEDIA_TYPE.equals(
            root.path("manifestMediaType").asText()
        )
        || !manifestDigest.equals(root.path("manifestDigest").asText())) {
      throw new IllegalArgumentException(
          "Skill Artifact config does not match its Manifest layer"
      );
    }
  }

  private void validateDescriptor(
      final Descriptor descriptor,
      final int limit,
      final String name
  ) {
    if (descriptor == null
        || !DIGEST.matcher(nullToEmpty(descriptor.digest())).matches()
        || !StringUtils.hasText(descriptor.mediaType())
        || descriptor.size() <= 0
        || descriptor.size() > limit) {
      throw new IllegalArgumentException(name + " descriptor is invalid");
    }
  }

  private HttpRequest request(
      final URI uri,
      final String accept,
      final String authorization
  ) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(properties.getRequestTimeout())
        .header("Accept", accept)
        .GET();
    if (StringUtils.hasText(authorization)) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  private HttpResponse<InputStream> send(
      final HttpClient client,
      final HttpRequest request,
      final String name
  ) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException(name + " request was interrupted");
    } catch (IOException ex) {
      throw new IllegalArgumentException(name + " request failed");
    }
  }

  private byte[] readBounded(
      final InputStream input,
      final int maximumBytes,
      final String name
  ) {
    try (InputStream stream = input) {
      byte[] result = stream.readNBytes(maximumBytes + 1);
      if (result.length > maximumBytes) {
        throw new IllegalArgumentException(
            name + " response exceeds the configured limit"
        );
      }
      return result;
    } catch (IOException ex) {
      throw new IllegalArgumentException(name + " response cannot be read");
    }
  }

  private <T> T read(
      final byte[] content,
      final Class<T> type,
      final String name
  ) {
    try {
      T value = objectMapper.readValue(content, type);
      if (value == null) {
        throw new IllegalArgumentException(name + " is empty");
      }
      return value;
    } catch (IOException ex) {
      throw new IllegalArgumentException(name + " is not valid JSON");
    }
  }

  private <T> T read(
      final byte[] content,
      final TypeReference<T> type,
      final String name
  ) {
    try {
      T value = objectMapper.readValue(content, type);
      if (value == null) {
        throw new IllegalArgumentException(name + " is empty");
      }
      return value;
    } catch (IOException ex) {
      throw new IllegalArgumentException(name + " is not valid JSON");
    }
  }

  private void assertAllowedRegistry(final String registry) {
    if (!normalizedSet(properties.getAllowedRegistries()).contains(registry)) {
      throw new IllegalArgumentException(
          "OCI Registry is not allowed by Skill Artifact policy"
      );
    }
  }

  private String registryScheme(final String registry) {
    return normalizedSet(properties.getInsecureRegistries()).contains(registry)
        ? "http" : "https";
  }

  private URI parseTokenRealm(final String value) {
    try {
      URI uri = URI.create(nullToEmpty(value));
      boolean secure = "https".equalsIgnoreCase(uri.getScheme());
      boolean allowedInsecure = "http".equalsIgnoreCase(uri.getScheme())
          && normalizedSet(properties.getInsecureRegistries())
          .contains(uri.getAuthority().toLowerCase(Locale.ROOT));
      if ((!secure && !allowedInsecure)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || !normalizedSet(properties.getAllowedTokenHosts())
          .contains(uri.getAuthority().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "OCI Registry token service is not allowed"
        );
      }
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "OCI Registry token service is invalid"
      );
    }
  }

  private URI parseVerifierUri() {
    try {
      URI uri = URI.create(nullToEmpty(properties.getVerifierUrl()));
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || StringUtils.hasText(uri.getPath())
          && !"/".equals(uri.getPath())
          || Boolean.TRUE.equals(properties.getVerifierMtlsEnabled())
          && !"https".equalsIgnoreCase(uri.getScheme())
          || !Boolean.TRUE.equals(properties.getVerifierMtlsEnabled())
          && !"http".equalsIgnoreCase(uri.getScheme())) {
        throw new IllegalArgumentException(
            "Artifact signature verifier URL is invalid"
        );
      }
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "Artifact signature verifier URL is invalid"
      );
    }
  }

  private void validateProperties() {
    Duration connectTimeout = properties.getConnectTimeout();
    Duration requestTimeout = properties.getRequestTimeout();
    if (connectTimeout == null
        || connectTimeout.isZero()
        || connectTimeout.isNegative()
        || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
        || requestTimeout == null
        || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
        || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0
        || properties.getMaximumOciManifestBytes() == null
        || properties.getMaximumOciManifestBytes() < 1024
        || properties.getMaximumOciManifestBytes() > 4 * 1024 * 1024
        || properties.getMaximumConfigBytes() == null
        || properties.getMaximumConfigBytes() < 128
        || properties.getMaximumConfigBytes() > 1024 * 1024
        || properties.getMaximumSkillManifestBytes() == null
        || properties.getMaximumSkillManifestBytes() < 1024
        || properties.getMaximumSkillManifestBytes() > 4 * 1024 * 1024) {
      throw new IllegalStateException(
          "Skill Artifact verification limits are invalid"
      );
    }
  }

  private void assertContentType(
      final String actual,
      final String expected,
      final String name
  ) {
    String mediaType = nullToEmpty(actual).split(";", 2)[0].trim();
    if (!expected.equalsIgnoreCase(mediaType)) {
      throw new IllegalArgumentException(name + " media type is invalid");
    }
  }

  private void assertBlobContentType(
      final String actual,
      final String expected,
      final String name
  ) {
    String mediaType = nullToEmpty(actual).split(";", 2)[0].trim();
    if (!expected.equalsIgnoreCase(mediaType)
        && !"application/octet-stream".equalsIgnoreCase(mediaType)) {
      throw new IllegalArgumentException(name + " media type is invalid");
    }
  }

  private void assertDigest(
      final byte[] value,
      final String expected,
      final String name
  ) {
    String actual = "sha256:" + sha256Hex(value);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(name + " digest does not match");
    }
  }

  private String contentOnlyPolicyHash() {
    return "sha256:" + sha256Hex(String.join(
        "\n",
        "simplepoint-skill-content-v1",
        OCI_MANIFEST_MEDIA_TYPE,
        SKILL_ARTIFACT_TYPE,
        SKILL_CONFIG_MEDIA_TYPE,
        SKILL_MANIFEST_MEDIA_TYPE
    ).getBytes(StandardCharsets.UTF_8));
  }

  private String sha256Hex(final byte[] value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value)
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private String requireDigest(final String value, final String name) {
    String digest = nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
    if (!DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return digest;
  }

  private Set<String> normalizedSet(final List<String> values) {
    if (values == null) {
      return Set.of();
    }
    return values.stream()
        .filter(StringUtils::hasText)
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private boolean hasRegistryCredential() {
    return StringUtils.hasText(properties.getRegistryUsername())
        && StringUtils.hasText(properties.getRegistryPassword());
  }

  private String basicAuthorization() {
    if (!hasRegistryCredential()) {
      throw new IllegalArgumentException(
          "OCI Registry credentials are not configured"
      );
    }
    String value = properties.getRegistryUsername().trim()
        + ":" + properties.getRegistryPassword();
    return "Basic " + Base64.getEncoder().encodeToString(
        value.getBytes(StandardCharsets.UTF_8)
    );
  }

  private String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  private void close(final InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // The response has already been rejected.
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OciManifest(
      int schemaVersion,
      String mediaType,
      String artifactType,
      Descriptor config,
      List<Descriptor> layers
  ) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Descriptor(
      String mediaType,
      String digest,
      long size
  ) {
  }

  private record RegistryResponse(
      byte[] body,
      String contentType,
      String digest
  ) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SignatureResponse(
      boolean admitted,
      boolean signatureVerified,
      String policyHash,
      Instant checkedAt
  ) {
  }

  private record SignatureDecision(
      boolean verified,
      String policyHash,
      Instant checkedAt
  ) {
  }
}
