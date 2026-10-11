package org.simplepoint.plugin.dna.runtime.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned placement targets keyed by logical policy name. */
@Getter
@Setter
@ConfigurationProperties(prefix = "simplepoint.dna.runtime.placement")
public class DnaRuntimePlacementProperties {

  private Map<String, Target> targets = new LinkedHashMap<>();

  /** One administrator-configured physical target. */
  @Getter
  @Setter
  public static class Target {
    private String dataSourceId;
    private String catalogName;
    private String schemaName;
    private PhysicalIsolationMode isolationMode = PhysicalIsolationMode.SHARED_DATABASE;
  }
}
