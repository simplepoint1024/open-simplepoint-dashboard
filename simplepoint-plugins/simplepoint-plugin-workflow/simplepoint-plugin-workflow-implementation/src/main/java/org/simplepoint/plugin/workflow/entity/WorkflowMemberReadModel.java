package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

/** Scoped scalar identity lookup, sharing exactly the list's live real-name projection. */
@Entity
@Immutable
@Subselect("select tenant_id, user_id, name from " + WorkflowReadSql.MEMBERS + " members")
@IdClass(WorkflowMemberReadModel.Key.class)
@Getter
public class WorkflowMemberReadModel {
  @Id
  @Column(name = "tenant_id")
  private String tenantId;

  @Id
  @Column(name = "user_id")
  private String userId;

  private String name;

  /** Unique user identity within an organization directory. */
  public record Key(String tenantId, String userId) implements Serializable {}
}
