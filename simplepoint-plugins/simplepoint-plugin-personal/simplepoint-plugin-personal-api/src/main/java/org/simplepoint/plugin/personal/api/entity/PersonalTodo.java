package org.simplepoint.plugin.personal.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Durable personal inbox entry with an idempotent business-source identity. */
@Data
@Entity
@Table(
    name = "simpoint_personal_todos",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_personal_todos_source",
            columnNames = {"owner_id", "workspace", "source", "source_key"}),
    indexes = {
      @Index(name = "idx_personal_todos_owner", columnList = "owner_id,workspace,created_at"),
      @Index(
          name = "idx_personal_todos_pending",
          columnList = "owner_id,workspace,deleted_at,status,due_at")
    })
@EqualsAndHashCode(callSuper = true)
@org.simplepoint.core.query.TableQuery(
    value = {
      @org.simplepoint.core.query.QueryField("title"),
      @org.simplepoint.core.query.QueryField("priority"),
      @org.simplepoint.core.query.QueryField("source"),
      @org.simplepoint.core.query.QueryField("dueAt")
    },
    maxPageSize = 100)
public class PersonalTodo extends OwnedRecord {
  @Column(nullable = false, length = 200)
  private String title;

  @Column(length = 8000)
  private String content;

  private int priority;

  @Column(nullable = false, length = 64)
  private String source;

  @Column(nullable = false, length = 160)
  private String sourceKey;

  @Column(length = 512)
  private String linkPath;

  @Column(nullable = false, length = 24)
  private String status = "PENDING";

  private Instant dueAt;
  private Instant completedAt;
}
