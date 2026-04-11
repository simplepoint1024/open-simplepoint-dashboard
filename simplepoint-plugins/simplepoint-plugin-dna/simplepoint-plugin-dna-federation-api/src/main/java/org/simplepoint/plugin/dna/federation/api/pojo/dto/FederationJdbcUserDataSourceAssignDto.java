package org.simplepoint.plugin.dna.federation.api.pojo.dto;

import java.io.Serializable;
import java.util.Set;
import lombok.Data;

/**
 * Datasource-assignment payload for one JDBC user.
 */
@Data
public class FederationJdbcUserDataSourceAssignDto implements Serializable {

  private String userId;

  private Set<String> dataSourceIds;
}
