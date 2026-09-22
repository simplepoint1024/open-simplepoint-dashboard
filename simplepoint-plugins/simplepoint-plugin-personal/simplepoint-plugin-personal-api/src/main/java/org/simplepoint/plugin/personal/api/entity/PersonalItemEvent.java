package org.simplepoint.plugin.personal.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable, owner-scoped history and notes for personal items. */
@Data
@Entity
@Table(
    name = "simpoint_personal_item_events",
    indexes =
        @Index(
            name = "idx_personal_item_event",
            columnList = "owner_id,workspace,item_id,created_at"))
@EqualsAndHashCode(callSuper = true)
public class PersonalItemEvent extends OwnedRecord {
  @Column(nullable = false, length = 64)
  private String itemId;

  @Column(nullable = false, length = 32)
  private String action;

  @Column(length = 2000)
  private String detail;
}
