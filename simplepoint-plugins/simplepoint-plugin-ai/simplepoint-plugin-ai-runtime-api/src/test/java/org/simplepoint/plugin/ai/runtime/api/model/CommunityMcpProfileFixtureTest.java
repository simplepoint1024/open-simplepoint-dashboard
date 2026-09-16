package org.simplepoint.plugin.ai.runtime.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CommunityMcpProfileFixtureTest {

  private static final Pattern PINNED_IMAGE = Pattern.compile(
      "^[^@\\s]+@sha256:[a-f0-9]{64}$"
  );

  private static final List<String> SERVICES = List.of(
      "github", "filesystem", "git", "postgresql", "dockerhub", "playwright"
  );

  private final ObjectMapper objectMapper = new ObjectMapper()
      .findAndRegisterModules();

  @Test
  void communityProfilesAreValidPinnedOriginalArtifacts() throws IOException {
    Path directory = repositoryRoot().resolve(
        "simplepoint-services/simplepoint-service-tool-runtime-node/testdata/"
            + "community-mcp-profiles"
    );
    JsonNode provenance = objectMapper.readTree(
        directory.resolve("provenance.json").toFile()
    );
    Map<String, String> digests = new HashMap<>();
    provenance.path("images").forEach(image -> digests.put(
        image.path("id").asText(), image.path("manifestDigest").asText()
    ));

    assertThat(digests.keySet()).containsExactlyInAnyOrderElementsOf(SERVICES);
    for (String service : SERVICES) {
      RuntimeMcpProfileSpec profile = objectMapper.readValue(
          directory.resolve(service + ".profile.json").toFile(),
          RuntimeMcpProfileSpec.class
      );
      String reference = profile.artifact().reference();
      assertThat(reference).matches(PINNED_IMAGE);
      assertThat(reference).endsWith("@" + digests.get(service));
      assertThat(profile.artifact().source()).isIn(
          RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
          RuntimeMcpProfileSpec.ArtifactSource.CATALOG_ORIGINAL
      );
      assertThat(reference).doesNotContain("somesimpled/open-simplepoint");
      assertThat(profile.secrets()).allSatisfy(binding ->
          assertThat(binding.secretReference())
              .doesNotContain("ghp_", "github_pat_", "postgres://")
      );
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir"))
        .toAbsolutePath().normalize();
    while (current != null
        && !Files.exists(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Repository root is unavailable");
    }
    return current;
  }
}
