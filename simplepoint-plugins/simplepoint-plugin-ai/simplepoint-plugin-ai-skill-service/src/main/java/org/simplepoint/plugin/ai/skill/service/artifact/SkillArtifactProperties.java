package org.simplepoint.plugin.ai.skill.service.artifact;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registry and supply-chain policy for OCI Skill Artifacts.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = SkillArtifactProperties.PREFIX)
public class SkillArtifactProperties {

  public static final String PREFIX = "simplepoint.ai.skill.artifact";

  private List<String> allowedRegistries =
      new ArrayList<>(List.of("docker.io", "ghcr.io"));

  private List<String> insecureRegistries = new ArrayList<>();

  private List<String> allowedTokenHosts =
      new ArrayList<>(List.of("auth.docker.io", "ghcr.io"));

  private Duration connectTimeout = Duration.ofSeconds(5);

  private Duration requestTimeout = Duration.ofSeconds(30);

  private Integer maximumOciManifestBytes = 1024 * 1024;

  private Integer maximumConfigBytes = 64 * 1024;

  private Integer maximumSkillManifestBytes = 512 * 1024;

  private String registryUsername;

  private String registryPassword;

  private Boolean signatureRequired = true;

  private String verifierUrl = "https://tool-image-verifier:2893";

  private Boolean verifierMtlsEnabled = true;

  private String verifierKeyStore;

  private String verifierKeyStorePassword;

  private String verifierTrustStore;

  private String verifierTrustStorePassword;
}
