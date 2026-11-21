package org.simplepoint.platform.resource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;

/** Exact authorization cleanup for one unpublished or formally offline application catalog. */
public final class ApplicationCatalogRemovalEvents {
  public static final String REQUESTED = "application.catalog-removal.requested";
  public static final String COMPLETED = "application.catalog-removal.completed";
  public static final EventPublication REQUEST = new EventPublication(REQUESTED, 1);
  public static final EventPublication ACK = new EventPublication(COMPLETED, 1);
  public static final EventSubscription AUTHORIZATION = new EventSubscription("application-catalog-remove", "app", REQUESTED, 1);
  public static final EventSubscription APPLICATION = new EventSubscription("application-catalog-removed", "common", COMPLETED, 1);

  private ApplicationCatalogRemovalEvents() {}

  public static EventMessage requested(String tenant, String application, long revision, UUID operation,
      Set<String> pages) {
    var payload = JsonNodeFactory.instance.objectNode();
    var nodes = payload.putArray("pages");
    new TreeSet<>(pages).forEach(nodes::add);
    var event = new EventMessage(operation, REQUESTED, 1, "app", tenant, application, revision,
        operation.toString(), operation.toString(), Instant.now(), payload);
    pages(event);
    return event;
  }

  public static Set<String> pages(EventMessage event) {
    AUTHORIZATION.validate(event);
    scope(event);
    if (!event.eventId().toString().equals(event.operationId())) throw invalid();
    var nodes = event.payload().path("pages");
    if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 500) throw invalid();
    var pages = new TreeSet<String>();
    nodes.forEach(value -> {if (!value.isTextual() || !pages.add(uuid(value.asText()))) throw invalid();});
    return Set.copyOf(pages);
  }

  public static EventMessage completed(EventMessage event) {
    pages(event);
    return new EventMessage(UUID.randomUUID(), COMPLETED, 1, "common", event.tenantId(),
        event.resourceId(), event.generation(), event.operationId(), event.correlationId(),
        Instant.now(), JsonNodeFactory.instance.objectNode());
  }

  public static void requireCompletion(EventMessage event) {
    APPLICATION.validate(event);
    scope(event);
  }

  public static String rootKey(String application) {
    return "application:" + uuid(application);
  }

  public static String pageKey(String page) {
    return "page:" + uuid(page);
  }

  private static void scope(EventMessage event) {
    uuid(event.resourceId());
    uuid(event.operationId());
    if (!event.operationId().equals(event.correlationId()) || event.tenantId().length() > 36) throw invalid();
  }

  private static String uuid(String value) {
    if (value.length() != 36 || !UUID.fromString(value).toString().equals(value)) throw invalid();
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid application catalog removal");
  }
}
