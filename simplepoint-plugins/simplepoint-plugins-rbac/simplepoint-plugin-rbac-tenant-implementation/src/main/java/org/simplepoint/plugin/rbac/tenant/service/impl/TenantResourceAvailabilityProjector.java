package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceView;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceView.Availability;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceView.EntitlementKind;
import org.simplepoint.security.entity.ResourceType;

/** Bounded page/ancestor projection, sharing the exact runtime dependency and entitlement rules. */
final class TenantResourceAvailabilityProjector {
  private final EntityManager em;
  private final String tenant;
  private final Instant now;

  TenantResourceAvailabilityProjector(EntityManager em, String tenant, Instant now) {
    this.em = em;
    this.tenant = tenant;
    this.now = now;
  }

  List<TenantResource> project(List<TenantResource> resources) {
    if (resources.isEmpty()) {
      return List.of();
    }
    if (resources.stream().anyMatch(row -> !tenant.equals(row.getTenantId()))) {
      throw new IllegalArgumentException("Resource projection must remain tenant scoped");
    }
    var ids = resources.stream().map(TenantResource::getId).distinct().toList();
    var eligible = new TenantResourceReadQuery(em, tenant, now).eligibleResourceIds(ids);
    var balances = balances(ids);
    var result = new ArrayList<TenantResource>(resources.size());
    for (var resource : resources) {
      var view = new TenantResourceView();
      org.springframework.beans.BeanUtils.copyProperties(resource, view);
      Balance balance = balances.get(resource.getId());
      EntitlementKind kind = kind(resource, balance);
      view.setEntitlementKind(kind);
      if (kind == EntitlementKind.TIMED || kind == EntitlementKind.UNLIMITED) {
        view.setEntitlementStartsAt(balance.starts());
        view.setEntitlementExpiresAt(balance.expires());
      }
      view.setAvailability(
          availability(resource, balance, kind, eligible.contains(resource.getId())));
      result.add(view);
    }
    return result;
  }

  private record Balance(Instant starts, Instant expires) {}

  private Map<String, Balance> balances(List<String> ids) {
    var result = new LinkedHashMap<String, Balance>();
    for (int offset = 0; offset < ids.size(); offset += 100) {
      var rows =
          em.createQuery(
                  "select e.id as id, e.startsAt as starts, e.expiresAt as expires from"
                      + " TenantResourceEntitlement e where e.tenantId = :tenant and e.id in :ids",
                  Tuple.class)
              .setParameter("tenant", tenant)
              .setParameter("ids", ids.subList(offset, Math.min(offset + 100, ids.size())))
              .setMaxResults(100)
              .getResultList();
      for (var row : rows) {
        result.put(
            row.get("id", String.class),
            new Balance(row.get("starts", Instant.class), row.get("expires", Instant.class)));
      }
    }
    return result;
  }

  private EntitlementKind kind(TenantResource resource, Balance balance) {
    if (!resource.isPlatformSource()
        || !resource.isGrantable()
            && (resource.getType() == ResourceType.GROUP
                || resource.getType() == ResourceType.MODULE)) {
      return EntitlementKind.NOT_REQUIRED;
    }
    if (resource.isBaselineEntitled()) {
      return EntitlementKind.BASELINE;
    }
    if (balance == null) {
      return EntitlementKind.NONE;
    }
    return balance.expires() == null ? EntitlementKind.UNLIMITED : EntitlementKind.TIMED;
  }

  private Availability availability(
      TenantResource row, Balance balance, EntitlementKind kind, boolean eligible) {
    if (row.isFormallyOffline()) {
      return Availability.OFFLINE;
    }
    if (row.isManualDisabled()) {
      return Availability.DISABLED;
    }
    if (!row.isSourceAvailable()) {
      return Availability.SOURCE_UNAVAILABLE;
    }
    if (!row.isEnabled()) {
      return Availability.DISABLED;
    }
    if (kind == EntitlementKind.NONE) {
      return Availability.NO_ENTITLEMENT;
    }
    if (kind == EntitlementKind.TIMED || kind == EntitlementKind.UNLIMITED) {
      if (balance.starts().isAfter(now)) {
        return Availability.NOT_STARTED;
      }
      if (balance.expires() != null && !balance.expires().isAfter(now)) {
        return Availability.EXPIRED;
      }
    }
    return eligible ? Availability.AVAILABLE : Availability.DEPENDENCY_UNAVAILABLE;
  }

  /** Display-only columns have no query capability; backend filters and sorts reject them. */
  @SuppressWarnings("unchecked")
  static void appendSchema(Map<String, Object> result) {
    var schema = (Map<String, Object>) result.get("schema");
    var properties = (Map<String, Object>) schema.get("properties");
    int order = properties.size();
    for (String field :
        List.of("availability", "entitlementKind", "entitlementStartsAt", "entitlementExpiresAt")) {
      String access = FieldPermissionPolicy.access(TenantResourceView.class, field);
      if (FieldPermissionPolicy.hidden(access)) {
        continue;
      }
      var column = new LinkedHashMap<String, Object>();
      column.put("type", "string");
      column.put("title", "i18n:tenant.resources." + field);
      column.put("readOnly", true);
      column.put("x-order", order++);
      column.put("x-ui", Map.of("x-list-visible", true));
      if (access != null) {
        column.put("x-field-access", access);
      }
      if (field.endsWith("At")) {
        column.put("format", "date-time");
      }
      properties.put(field, column);
    }
  }
}
