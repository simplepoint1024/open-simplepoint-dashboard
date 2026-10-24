package org.simplepoint.persistencefixture.beta;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/** An owned entity with the platform audit and identifier mappings. */
@Entity
@Table(name = "beta_records")
public class BetaRecord extends BaseEntityImpl<String> {
  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }
}
