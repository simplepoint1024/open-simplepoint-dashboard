package org.simplepoint.plugin.dna.runtime.api.event;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;

/** Durable form-owned storage removal. The application provides logical identities only. */
public final class FormStorageRemovalEvents {
  public static final String REQUESTED = "form.storage-removal.requested";
  public static final String COMPLETED = "form.storage-removal.completed";
  public static final EventPublication REQUEST = new EventPublication(REQUESTED, 1);
  public static final EventPublication ACK = new EventPublication(COMPLETED, 1);
  public static final EventSubscription STORAGE = new EventSubscription("form-storage-remove", "app", REQUESTED, 1);
  public static final EventSubscription APPLICATION = new EventSubscription("form-storage-removed", "dna", COMPLETED, 1);
  public static final int MAX_MODELS = 200;
  public static final int MAX_FORMS = 100;
  public static final int MAX_BATCH_MODELS = 2000;

  private FormStorageRemovalEvents() {}

  public record Member(String formId, int version, UUID operation, String parentModelId, Set<String> formIdentities) {}
  public record Selection(String applicationId, List<Member> members) {}

  public static EventMessage requested(String tenant, String application, List<Member> members) {
    var first = members.getFirst();
    var payload = JsonNodeFactory.instance.objectNode().put("applicationId", application);
    var array = payload.putArray("members");
    for (var member : members) {
      var row = array.addObject().put("formId", member.formId()).put("version", member.version())
          .put("operation", member.operation().toString()).put("parentModelId", member.parentModelId());
      var ids = row.putArray("formIdentities");
      new TreeSet<>(member.formIdentities()).forEach(ids::add);
    }
    String operation = first.operation().toString();
    var event = new EventMessage(first.operation(), REQUESTED, 1, "app", tenant, first.formId(), first.version(),
        operation, operation, Instant.now(), payload);
    selection(event);
    return event;
  }

  public static Selection selection(EventMessage event) {
    STORAGE.validate(event);
    verifyScope(event);
    if (!event.eventId().toString().equals(event.operationId())) throw invalid();
    var payload = event.payload();
    String application = id(payload.path("applicationId").asText());
    var rows = payload.path("members");
    if (!rows.isArray() || rows.isEmpty() || rows.size() > MAX_FORMS) throw invalid();
    var members = new ArrayList<Member>();
    var selected = new HashSet<String>();
    var operations = new HashSet<UUID>();
    var parents = new HashSet<String>();
    for (var row : rows) {
      String form = id(row.path("formId").asText());
      String parent = id(row.path("parentModelId").asText());
      UUID operation = UUID.fromString(id(row.path("operation").asText()));
      var version = row.path("version");
      var ids = row.path("formIdentities");
      if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() <= 0
          || !operations.add(operation) || !parents.add(parent)
          || !ids.isArray() || ids.isEmpty() || ids.size() > MAX_MODELS) throw invalid();
      var distinct = new HashSet<String>();
      for (var value : ids) {
        if (!value.isTextual()) throw invalid();
        String id = id(value.asText());
        if (!selected.add(id)) throw invalid();
        distinct.add(id);
      }
      if (!distinct.contains(form) || selected.size() > MAX_BATCH_MODELS) throw invalid();
      members.add(new Member(form, version.asInt(), operation, parent, Set.copyOf(distinct)));
    }
    var first = members.getFirst();
    if (!event.resourceId().equals(first.formId()) || event.generation() != first.version()
        || !event.eventId().equals(first.operation())) throw invalid();
    return new Selection(application, List.copyOf(members));
  }

  public static EventMessage completed(String tenant, String form, int version, String operation) {
    return new EventMessage(UUID.randomUUID(), COMPLETED, 1, "dna", tenant, form, version,
        operation, operation, Instant.now(), JsonNodeFactory.instance.objectNode());
  }

  public static void requireAcknowledgement(EventMessage event) {
    APPLICATION.validate(event);
    verifyScope(event);
  }

  private static void verifyScope(EventMessage event) {
    id(event.resourceId());
    id(event.operationId());
    if (!event.operationId().equals(event.correlationId()) || event.tenantId().length() > 64
        || event.generation() <= 0 || event.generation() > Integer.MAX_VALUE) throw invalid();
  }

  private static String id(String value) {
    if (value.length() != 36
        || !UUID.fromString(value).toString().equals(value)) throw invalid();
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid published form storage removal request");
  }
}
