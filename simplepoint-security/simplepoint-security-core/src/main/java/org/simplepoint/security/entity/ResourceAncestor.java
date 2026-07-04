package org.simplepoint.security.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.Data;
import org.simplepoint.security.entity.id.ResourceAncestorId;

/**
 * Resource ancestor-child relation used to query full trees efficiently.
 */
@Data
@Entity
@IdClass(ResourceAncestorId.class)
@Table(name = "simpoint_ac_resource_ancestors_rel")
public class ResourceAncestor implements Serializable {

  @Id
  private String childId;

  @Id
  private String ancestorId;
}
