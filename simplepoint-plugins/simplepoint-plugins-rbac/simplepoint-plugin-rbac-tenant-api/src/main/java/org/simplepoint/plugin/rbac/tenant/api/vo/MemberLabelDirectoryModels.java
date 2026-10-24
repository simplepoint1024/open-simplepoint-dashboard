package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Bounded scalar protocol for rebuilding an owned display-name projection. */
public final class MemberLabelDirectoryModels {
  public static final int MAX_PAGE_SIZE = 200;

  private MemberLabelDirectoryModels() {}

  /** Epochs are incomparable; a changed epoch requires a complete projection rebuild. */
  public record Revision(String epoch, long sequence) {
    /** Rejects malformed or negative revision tokens at either transport boundary. */
    public Revision {
      if (epoch == null || !UUID.fromString(epoch).toString().equals(epoch) || sequence < 0) {
        throw new IllegalArgumentException("Invalid member label revision");
      }
    }
  }

  /** The cursor is an exact ID from the previous page, never a client-side name sort key. */
  public record Request(String tenantId, Revision expectedRevision, String afterUserId, int limit) {
    /** A continuation always binds a revision; limits apply before any database work. */
    public Request {
      requireId(tenantId);
      if (afterUserId != null) {
        requireId(afterUserId);
        if (expectedRevision == null) {
          throw new IllegalArgumentException("A continuation requires its source revision");
        }
      }
      if (limit < 1 || limit > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException("Invalid member label page size");
      }
    }
  }

  /** Null labels retain the source's unnamed-member semantics without inventing ID labels. */
  public record Label(String userId, String name) {
    /** Validates scalar output without normalizing opaque identities. */
    public Label {
      requireId(userId);
      if (name != null && (name.isEmpty() || name.codePointCount(0, name.length()) > 767)) {
        throw new IllegalArgumentException("Invalid member label");
      }
    }
  }

  /** A restart contains no rows; callers must discard their entire incomplete generation. */
  public record Page(
      String tenantId,
      Revision revision,
      boolean restartRequired,
      boolean tenantActive,
      List<Label> labels,
      String nextUserId) {
    /** Copies rows and rejects ambiguous/inconsistent page envelopes. */
    public Page {
      requireId(tenantId);
      if (revision == null || labels == null || labels.size() > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException("Invalid member label page");
      }
      labels = List.copyOf(labels);
      var ids = new HashSet<String>();
      for (var label : labels) {
        if (!ids.add(label.userId())) {
          throw new IllegalArgumentException("Duplicate member label");
        }
      }
      if ((!tenantActive || restartRequired) && (!labels.isEmpty() || nextUserId != null)
          || restartRequired && tenantActive
          || nextUserId != null
              && (labels.isEmpty() || !labels.getLast().userId().equals(nextUserId))) {
        throw new IllegalArgumentException("Inconsistent member label page");
      }
    }
  }

  private static void requireId(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 255
        || !value.equals(value.strip())
        || "***".equals(value)) {
      throw new IllegalArgumentException("Invalid member label identity");
    }
  }
}
