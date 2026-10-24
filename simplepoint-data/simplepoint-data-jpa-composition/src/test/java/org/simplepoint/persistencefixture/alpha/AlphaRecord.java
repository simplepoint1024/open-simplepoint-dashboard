package org.simplepoint.persistencefixture.alpha;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/** An owned entity with the platform audit and identifier mappings. */
@Entity
@Table(name = "alpha_records")
public class AlphaRecord extends BaseEntityImpl<String> {
  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }
}
