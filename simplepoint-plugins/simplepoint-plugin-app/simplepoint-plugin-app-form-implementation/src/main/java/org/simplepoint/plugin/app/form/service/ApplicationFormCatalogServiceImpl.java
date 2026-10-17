package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormCatalogPage;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDesignerContract;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCatalogService;
import org.simplepoint.plugin.app.form.api.service.FormComponentRegistry;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reuses platform query/schema contracts with mandatory tenant and application boundaries. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormCatalogServiceImpl implements ApplicationFormCatalogService {

  private final EntityManager entityManager;
  private final FormRequestContext context;
  private final ApplicationAccessPort applications;
  private final FormComponentRegistry components;

  /** Creates the directory over the same form storage used by draft and publication services. */
  public ApplicationFormCatalogServiceImpl(
      final EntityManager entityManager,
      final FormRequestContext context,
      final ApplicationAccessPort applications,
      final FormComponentRegistry components
  ) {
    this.entityManager = entityManager;
    this.context = context;
    this.applications = applications;
    this.components = components;
  }

  @Override
  public ApplicationFormCatalogPage page(
      final String applicationId,
      final Map<String, String> filters,
      final int page,
      final int size,
      final List<String> sorts
  ) {
    String tenant = authorize(applicationId);
    if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
      throw invalid("Invalid form catalog page");
    }
    var result = ProjectionPages.query(
        entityManager, ApplicationFormEntity.class, QueryFields.class,
        filters == null ? Map.of() : filters, PageRequest.of(page, size, sorting(sorts)),
        "updatedAt", Set.of("name", "code", "description"),
        (cb, query, root) -> cb.and(cb.equal(root.get("tenantId"), tenant),
            cb.equal(root.get("applicationId"), applicationId)),
        ApplicationFormViews::definition
    );
    return new ApplicationFormCatalogPage(result.getContent(), new ApplicationFormCatalogPage.Metadata(
        result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()
    ));
  }

  @Override
  public Map<String, Object> schema(final String applicationId) {
    final ApplicationFormDesignerContract capability = designer(applicationId);
    Map<String, Object> projection = ProjectionTableSchema.schema(QueryFields.class, "app.form",
        Map.of("status", java.util.Arrays.stream(ApplicationFormStatus.values())
            .map(status -> new ProjectionTableSchema.Choice(
                status.name(), "i18n:app.form.status." + status.name()
            )).toList()));
    Map<String, Object> schema = copy(projection.get("schema"));
    Map<String, Object> properties = copy(schema.get("properties"));
    enrich(properties, "code", Map.of("type", "string", "title", "i18n:app.form.code", "x-order", 0,
        "pattern", "^[a-z][a-z0-9_]{0,62}$", "maxLength", 63));
    enrich(properties, "name", Map.of("type", "string", "title", "i18n:app.form.name", "x-order", 1,
        "pattern", "\\S", "maxLength", 120));
    enrich(properties, "description", Map.of("type", "string", "title", "i18n:app.form.description",
        "maxLength", 500, "x-order", 2));
    properties.put("id", Map.of("type", "string", "readOnly", true,
        "x-ui", Map.of("x-list-visible", false)));
    for (String name : List.of("status", "draftRevision", "latestVersion", "publishedVersion", "updatedAt")) {
      enrich(properties, name, Map.of("readOnly", true));
    }
    schema.put("properties", properties);
    schema.put("required", List.of("code", "name").stream().filter(properties::containsKey).toList());
    List<Map<String, Object>> buttons = new ArrayList<>();
    if (capability.canCreate()) {
      buttons.add(button("add", "app.form.create", 0, 0));
    }
    buttons.add(button("design", "app.form.design", 1, 1));
    return Map.of("schema", schema, "buttons", List.copyOf(buttons));
  }

  @Override
  public ApplicationFormDesignerContract designer(final String applicationId) {
    authorize(applicationId);
    return new ApplicationFormDesignerContract(
        allowed("create"), allowed("edit"), allowed("publish"), List.copyOf(components.descriptors()),
        List.of(FormFieldWritePolicy.values())
    );
  }

  private String authorize(final String applicationId) {
    if (applicationId == null || applicationId.isBlank() || applicationId.length() > 36) {
      throw invalid("Application identity is required");
    }
    var actor = context.requireActor();
    applications.requireApplication(actor.tenantId(), applicationId);
    return actor.tenantId();
  }

  private static boolean allowed(final String action) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context != null && (Boolean.TRUE.equals(context.getIsAdministrator())
        || context.getResources() != null
        && context.getResources().contains("app.workbench.forms." + action));
  }

  private static Sort sorting(final List<String> values) {
    if (values == null || values.isEmpty()) {
      return Sort.unsorted();
    }
    if (values.size() > 3) {
      throw invalid("At most three catalog sort fields are supported");
    }
    List<Sort.Order> orders = new ArrayList<>();
    for (String value : values) {
      String[] parts = value == null ? new String[0] : value.split(",", -1);
      if (parts.length != 2 || !("asc".equals(parts[1]) || "desc".equals(parts[1]))) {
        throw invalid("Invalid form catalog sorting");
      }
      orders.add(new Sort.Order(Sort.Direction.fromString(parts[1]), parts[0]));
    }
    return Sort.by(orders);
  }

  private static Map<String, Object> button(
      final String key, final String label, final int minimum, final int maximum
  ) {
    return Map.of("key", key, "text", "i18n:" + label, "sort", minimum,
        "argumentMinSize", minimum, "argumentMaxSize", maximum);
  }

  private static void enrich(
      final Map<String, Object> properties, final String name, final Map<String, Object> metadata
  ) {
    if (properties.containsKey(name)) {
      Map<String, Object> property = copy(properties.get(name));
      property.putAll(metadata);
      properties.put(name, property);
    }
  }

  private static Map<String, Object> copy(final Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> source) {
      source.forEach((key, item) -> result.put(key.toString(), item));
    }
    return result;
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }

  /** Query declaration deliberately excludes physical identities and document payloads. */
  @PermissionResource(ApplicationFormView.class)
  @TableQuery(value = {
      @QueryField("code"), @QueryField("name"), @QueryField("description"),
      @QueryField("status"), @QueryField("draftRevision"), @QueryField("latestVersion"),
      @QueryField("publishedVersion"), @QueryField("updatedAt")
  }, maxPageSize = 100)
  public record QueryFields(String code, String name, String description, ApplicationFormStatus status,
      long draftRevision, int latestVersion, Integer publishedVersion, Instant updatedAt) {
  }
}
