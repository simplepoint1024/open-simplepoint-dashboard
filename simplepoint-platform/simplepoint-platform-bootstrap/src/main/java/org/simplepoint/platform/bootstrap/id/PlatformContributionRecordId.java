package org.simplepoint.platform.bootstrap.id;

import java.io.Serializable;
import lombok.Data;

/**
 * Composite identifier for platform contribution records.
 */
@Data
public class PlatformContributionRecordId implements Serializable {

  private String serviceName;

  private String moduleCode;

  private String contributionType;

  private String contributionKey;
}
