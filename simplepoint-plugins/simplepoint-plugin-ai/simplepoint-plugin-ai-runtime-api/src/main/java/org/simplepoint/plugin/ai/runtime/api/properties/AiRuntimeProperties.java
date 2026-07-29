package org.simplepoint.plugin.ai.runtime.api.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI control-plane configuration for independent OCI runtime nodes.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AiRuntimeProperties.PREFIX)
public class AiRuntimeProperties {

  public static final String PREFIX = "simplepoint.ai.runtime";

  private String controlInternalHeader =
      "X-SimplePoint-Tool-Runtime-Control-Token";

  private String controlInternalToken;

  private Duration heartbeatInterval = Duration.ofSeconds(10);

  private Duration heartbeatTimeout = Duration.ofSeconds(35);

  private Duration staleScanInterval = Duration.ofSeconds(10);

  private String dispatchInternalHeader = "X-SimplePoint-Runtime-Token";

  private String dispatchInternalToken;

  private Duration dispatchTimeout = Duration.ofSeconds(30);

  private Boolean mtlsEnabled = false;

  private Integer mtlsControlPort = 2889;

  private String mtlsKeyStore;

  private String mtlsKeyStorePassword;

  private String mtlsTrustStore;

  private String mtlsTrustStorePassword;

  private String mtlsNodeIdentityPrefix =
      "spiffe://open-simplepoint/runtime-node/";

  private Duration workloadLeaseDuration = Duration.ofSeconds(45);

  private Duration workloadDispatchRetryInterval = Duration.ofSeconds(5);

  private Duration workloadObservationInterval = Duration.ofSeconds(5);

  private Duration workloadSchedulerInterval = Duration.ofSeconds(1);

  private Integer workloadSchedulerBatchSize = 16;

  private Duration poolSchedulerInterval = Duration.ofSeconds(2);

  private Integer poolSchedulerBatchSize = 16;

  private Duration poolPrewarmRetryInterval = Duration.ofSeconds(30);

  private Duration mcpActivationTimeout = Duration.ofSeconds(30);

  private Duration mcpSessionDirectoryTtl = Duration.ofMinutes(10);

  private Duration mcpFailureQuarantine = Duration.ofSeconds(10);

  private String mcpSessionDirectoryKeyPrefix =
      "simplepoint:ai:runtime:mcp:sessions:";

  private Integer mcpSessionIdMaxLength = 512;

  private Long defaultWorkloadMemoryBytes = 256L * 1024 * 1024;

  private Long defaultWorkloadNanoCpus = 500_000_000L;

  private Long defaultWorkloadPidsLimit = 128L;
}
