package org.simplepoint.plugin.personal.api;

import java.time.Instant;
import java.util.List;

/** Explicit HTTP and integration contracts; persistence ownership is not writable by clients. */
public final class PersonalModels {
  private PersonalModels() {}

  /** Stable checklist identity, title and completion flag. */
  public record ChecklistEntry(String id, String title, boolean done) {}

  /** Editable item fields; ownership and status are never accepted from clients. */
  public record ItemInput(
      Long version,
      String title,
      String description,
      String category,
      int priority,
      Instant startAt,
      Instant dueAt,
      boolean pinned,
      List<ChecklistEntry> checklist) {}

  /** Version-checked action; comments are used only for item notes. */
  public record ActionInput(Long version, String action, String comment) {}

  /** Manual todo fields; source and delivery identity are server-assigned. */
  public record TodoInput(String title, String content, int priority, Instant dueAt) {}

  /** Owned item projection, including deletion state for trash recovery. */
  public record ItemView(
      String id,
      Long version,
      String title,
      String description,
      String category,
      int priority,
      String status,
      Instant startAt,
      Instant dueAt,
      Instant completedAt,
      boolean pinned,
      boolean archived,
      Instant deletedAt,
      List<ChecklistEntry> checklist,
      Instant createdAt,
      Instant updatedAt) {}

  /** Todo projection without recipient or integration-key internals. */
  public record TodoView(
      String id,
      Long version,
      String title,
      String content,
      int priority,
      String status,
      Instant dueAt,
      Instant completedAt,
      String source,
      String linkPath,
      Instant createdAt) {}

  /** Inbox projection with explicit read and archive state. */
  public record MessageView(
      String id,
      Long version,
      String title,
      String content,
      int priority,
      String source,
      String linkPath,
      Instant readAt,
      boolean archived,
      Instant createdAt) {}

  /** Immutable item activity projection. */
  public record EventView(String id, String action, String detail, Instant createdAt) {}

  /** Counts and at most five rows per personal homepage panel. */
  public record Overview(
      long activeItems,
      long overdueItems,
      long pendingTodos,
      long unreadMessages,
      List<ItemView> items,
      List<TodoView> todos,
      List<MessageView> messages) {}

  /**
   * Only trusted backend adapters may choose recipients; never expose this as a public send API.
   */
  public record DeliveryTarget(String userId, String workspace) {}

  /** source + sourceKey identifies one delivery for one recipient in one workspace. */
  public record Delivery(
      String source,
      String sourceKey,
      String title,
      String content,
      int priority,
      Instant dueAt,
      String linkPath) {}
}
