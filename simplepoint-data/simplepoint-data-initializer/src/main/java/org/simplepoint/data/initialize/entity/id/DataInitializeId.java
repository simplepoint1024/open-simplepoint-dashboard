package org.simplepoint.data.initialize.entity.id;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.Data;

/**
 * DataInitializeId class representing the identifier for DataInitialize entity.
 */
@Data
@Embeddable
public class DataInitializeId implements Serializable {

  private String serviceName;

  private String moduleName;
}
