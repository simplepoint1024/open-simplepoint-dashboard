package org.simplepoint.plugin.personal.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A private work item; large text is ordinary text, not a PostgreSQL large object. */
@Data
@Entity
@Table(
    name = "simpoint_personal_items",
    indexes = {
      @Index(
          name = "idx_personal_item_owner",
          columnList = "owner_id,workspace,deleted_at,archived,status,due_at"),
      @Index(name = "idx_personal_item_updated", columnList = "owner_id,workspace,updated_at")
    })
@EqualsAndHashCode(callSuper = true)
@org.simplepoint.core.query.TableQuery(
    value = {
      @org.simplepoint.core.query.QueryField("title"),
      @org.simplepoint.core.query.QueryField("status"),
      @org.simplepoint.core.query.QueryField("priority"),
      @org.simplepoint.core.query.QueryField("category"),
      @org.simplepoint.core.query.QueryField("dueAt")
    },
    maxPageSize = 100)
public class PersonalItem extends OwnedRecord {
  @Column(nullable = false, length = 200)
  private String title;

  @Column(length = 8000)
  private String description;

  @Column(length = 60)
  private String category;

  private int priority;

  @Column(nullable = false, length = 24)
  private String status = "TODO";

  private Instant startAt;
  private Instant dueAt;
  private Instant completedAt;
  private boolean pinned;
  private boolean archived;

  @Column(nullable = false, columnDefinition = "text")
  private String checklistJson = "[]";
}
