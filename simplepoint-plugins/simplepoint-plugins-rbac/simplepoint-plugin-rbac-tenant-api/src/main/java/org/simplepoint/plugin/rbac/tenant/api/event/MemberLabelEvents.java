package org.simplepoint.plugin.rbac.tenant.api.event;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;

/** A committed observation of directory revision, scoped to one actual current or former tenant. */
public final class MemberLabelEvents {
  public static final String TYPE = "identity.member-labels.observed";
  public static final String RESOURCE = "member-labels";
  public static final EventPublication PUBLICATION = new EventPublication(TYPE, 1);
  public static final EventSubscription WORKFLOW_SUBSCRIPTION =
      new EventSubscription("member-labels.refresh", "common", TYPE, 1);

  private MemberLabelEvents() {}

  /** Publication and the tenant's observation cursor commit in the same identity transaction. */
  public static EventMessage observed(String tenant, Revision revision, Instant occurredAt) {
    new Request(tenant, revision, null, 1);
    String operation = operation(tenant, revision);
    return new EventMessage(
        UUID.randomUUID(),
        TYPE,
        1,
        "common",
        tenant,
        RESOURCE,
        revision.sequence(),
        operation,
        operation,
        occurredAt,
        JsonNodeFactory.instance.objectNode().put("epoch", revision.epoch()));
  }

  /**
   * Rejects mismatched scope, producer, payload or operation before any consumer database effect.
   */
  public static Revision revision(EventMessage event) {
    WORKFLOW_SUBSCRIPTION.validate(event);
    new Request(event.tenantId(), null, null, 1);
    var payload = event.payload();
    var epoch = payload.get("epoch");
    if (!RESOURCE.equals(event.resourceId())
        || payload.size() != 1
        || epoch == null
        || !epoch.isTextual()) {
      throw new IllegalArgumentException("Invalid member label observation");
    }
    var revision = new Revision(epoch.textValue(), event.generation());
    if (!operation(event.tenantId(), revision).equals(event.operationId())
        || !event.operationId().equals(event.correlationId())) {
      throw new IllegalArgumentException("Invalid member label observation scope");
    }
    return revision;
  }

  private static String operation(String tenant, Revision revision) {
    return UUID.nameUUIDFromBytes(
            (tenant.length() + ":" + tenant + ":" + revision.epoch() + ":" + revision.sequence())
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }
}
