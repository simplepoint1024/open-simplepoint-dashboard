package org.simplepoint.app.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.api.locale.LocalizedTexts;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationNavigation;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable local intent. RPC is deliberately owned by the separate nontransactional coordinator. */
@Service
@Transactional(readOnly = true)
public class ApplicationNavigationState {
  private final EntityManager em;
  private final ObjectMapper mapper;

  public ApplicationNavigationState(EntityManager em, ObjectMapper mapper) {
    this.em = em;
    this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public record View(long revision, boolean pending, ApplicationNavigation configuration) {}

  public record Update(long expectedRevision, ApplicationNavigation configuration) {}

  public record Work(
      String tenantId,
      String applicationId,
      long revision,
      String json,
      ManagedNavigationCommand command) {}

  public static String tenant() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null
        || context.getScopeType() != AuthorizationScopeType.TENANT
        || context.getAttribute("X-Tenant-Id") == null)
      throw new AccessDeniedException("Tenant workspace required");
    return context.getAttribute("X-Tenant-Id");
  }

  private static void requireEditor() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null
        || !Boolean.TRUE.equals(context.getIsAdministrator())
            && (context.getResources() == null
                || !context.getResources().contains("app.workbench.forms.edit")))
      throw new AccessDeniedException("Application navigation edit permission required");
  }

  public View view(String applicationId) {
    requireEditor();
    var app = application(tenant(), applicationId, false);
    var pages = pages(app);
    ApplicationNavigation config =
        app.getNavigationPending() != null
            ? decode(app.getNavigationPending())
            : app.getNavigationDefinition() != null
                ? decode(app.getNavigationDefinition())
                : new ApplicationNavigation(
                    null, name(app.getName(), app.getNameLocalization()), List.of(), List.of());
    // Pages can be created after the last layout. Their initial parent is the application root.
    var byId = new HashMap<String, ApplicationNavigation.Page>();
    config.pages().forEach(page -> byId.put(page.id(), page));
    var pageViews =
        pages.stream()
            .map(
                page ->
                    byId.getOrDefault(
                        page.getId(),
                        new ApplicationNavigation.Page(
                            page.getId(), null, name(page.getName(), page.getNameLocalization()))))
            .toList();
    return new View(
        app.getNavigationRevision(),
        app.getNavigationPending() != null,
        new ApplicationNavigation(
            config.tenantParentCode(), config.name(), config.directories(), pageViews));
  }

  @Transactional
  public Work begin(String applicationId, Update update) {
    requireEditor();
    String tenant = tenant();
    var app = application(tenant, applicationId, true);
    if (update == null || "DELETING".equals(app.getStatus())) throw conflict();
    var config = validate(update.configuration(), pages(app));
    String json = encode(config);
    if (app.getNavigationPending() == null && update.expectedRevision() + 1 == app.getNavigationRevision()
        && Objects.equals(app.getNavigationDefinition(), json)) {
      long revision = app.getNavigationRevision();
      return new Work(tenant, applicationId, revision, json, command(tenant, applicationId, revision, config));
    }
    if (update.expectedRevision() != app.getNavigationRevision()) throw conflict();
    if (app.getNavigationPending() != null && !app.getNavigationPending().equals(json))
      throw conflict();
    app.setNavigationPending(json);
    long revision = app.getNavigationRevision() + 1;
    return new Work(
        tenant, applicationId, revision, json, command(tenant, applicationId, revision, config));
  }

  @Transactional
  public View complete(Work work) {
    var app = application(work.tenantId(), work.applicationId(), true);
    if (app.getNavigationRevision() == work.revision()
        && Objects.equals(app.getNavigationDefinition(), work.json()))
      return view(work.applicationId());
    if (app.getNavigationRevision() + 1 != work.revision()
        || !Objects.equals(app.getNavigationPending(), work.json())) throw conflict();
    var config = decode(work.json());
    app.setName(config.name().defaultValue());
    app.setNameLocalization(config.name());
    var pageNames = new HashMap<String, LocalizedText>();
    config.pages().forEach(page -> pageNames.put(page.id(), page.name()));
    for (var page : pages(app)) {
      var name = pageNames.get(page.getId());
      if (name == null) throw conflict();
      page.setName(name.defaultValue());
      page.setNameLocalization(name);
      page.setUpdatedAt(Instant.now());
    }
    app.setNavigationDefinition(work.json());
    app.setNavigationPending(null);
    app.setNavigationRevision(work.revision());
    app.setUpdatedAt(Instant.now());
    return view(work.applicationId());
  }

  @Transactional
  public View cancelled(Work work) {
    var app = application(work.tenantId(), work.applicationId(), true);
    if (app.getNavigationRevision() + 1 != work.revision()
        || !Objects.equals(app.getNavigationPending(), work.json())) throw conflict();
    app.setNavigationRevision(work.revision());
    app.setNavigationPending(null);
    app.setUpdatedAt(Instant.now());
    return view(work.applicationId());
  }

  public static ApplicationNavigation validate(
      ApplicationNavigation config, List<WorkspaceApplicationPage> pages) {
    if (config == null || config.directories().size() > 99 || config.pages().size() > 500)
      throw invalid();
    var directories = new LinkedHashMap<String, ApplicationNavigation.Directory>();
    for (var node : config.directories()) {
      if (node.id() == null
          || !node.id().matches("[a-zA-Z0-9_-]{1,64}")
          || directories.containsKey(node.id())
          || node.icon() != null && !node.icon().matches("[A-Za-z0-9]{1,60}")) throw invalid();
      directories.put(
          node.id(),
          new ApplicationNavigation.Directory(
              node.id(), node.parentId(), validatedName(node.name()), node.icon()));
    }
    for (var node : directories.values()) {
      var seen = new HashSet<String>();
      var cursor = node;
      while (cursor != null) {
        if (!seen.add(cursor.id()) || seen.size() > 7) throw invalid();
        if (cursor.parentId() == null) break;
        cursor = directories.get(cursor.parentId());
        if (cursor == null) throw invalid();
      }
    }
    var available =
        pages.stream()
            .map(WorkspaceApplicationPage::getId)
            .collect(java.util.stream.Collectors.toSet());
    var seen = new HashSet<String>();
    var pageValues = new ArrayList<ApplicationNavigation.Page>();
    for (var page : config.pages()) {
      if (!available.contains(page.id())
          || !seen.add(page.id())
          || page.directoryId() != null && !directories.containsKey(page.directoryId()))
        throw invalid();
      pageValues.add(
          new ApplicationNavigation.Page(
              page.id(), page.directoryId(), validatedName(page.name())));
    }
    if (!seen.equals(available)) throw conflict();
    if (config.tenantParentCode() != null
        && !config.tenantParentCode().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) throw invalid();
    return new ApplicationNavigation(
        config.tenantParentCode(),
        validatedName(config.name()),
        List.copyOf(directories.values()),
        pageValues);
  }

  public static ManagedNavigationCommand command(
      String tenant, String app, long revision, ApplicationNavigation config) {
    var directories = new ArrayList<ManagedNavigationCommand.Directory>();
    String root = root(app);
    directories.add(
        new ManagedNavigationCommand.Directory(
            root,
            "app.runtime.group." + suffix(app),
            null,
            config.name().defaultValue(),
            config.name(),
            "AppstoreOutlined",
            100));
    int sort = 0;
    for (var directory : config.directories())
      directories.add(
          new ManagedNavigationCommand.Directory(
              key(app, directory.id()),
              "app.runtime.group." + suffix(key(app, directory.id())),
              key(app, directory.parentId()),
              directory.name().defaultValue(),
              directory.name(),
              directory.icon(),
              sort++));
    return new ManagedNavigationCommand(
        tenant,
        root,
        revision,
        config.tenantParentCode(),
        directories,
        config.pages().stream()
            .map(
                page ->
                    new ManagedNavigationCommand.Page(
                        "page:" + page.id(),
                        key(app, page.directoryId()),
                        page.name().defaultValue(),
                        page.name()))
            .toList());
  }

  public static String root(String app) {
    return "application:" + app;
  }

  public static String key(String app, String id) {
    return id == null ? root(app) : root(app) + ":directory:" + id;
  }

  private static String suffix(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString()
        .replace("-", "");
  }

  private static LocalizedText name(String label, LocalizedText localized) {
    return localized != null ? localized : new LocalizedText(label, "zh-CN", Map.of());
  }

  private static LocalizedText validatedName(LocalizedText value) {
    try {
      return LocalizedTexts.validate(value, 100, 32);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private WorkspaceApplication application(String tenant, String id, boolean lock) {
    var value =
        lock
            ? em.find(WorkspaceApplication.class, id, LockModeType.PESSIMISTIC_WRITE)
            : em.find(WorkspaceApplication.class, id);
    if (value == null || !tenant.equals(value.getTenantId()))
      throw new ApplicationFormException(
          ApplicationFormErrorCode.APPLICATION_NOT_FOUND, "Application not found");
    return value;
  }

  private List<WorkspaceApplicationPage> pages(WorkspaceApplication app) {
    return em.createQuery(
            "from WorkspaceApplicationPage p where p.tenantId = :tenant and p.applicationId = :app"
                + " order by p.sortOrder, p.id",
            WorkspaceApplicationPage.class)
        .setParameter("tenant", app.getTenantId())
        .setParameter("app", app.getId())
        .setMaxResults(501)
        .getResultList();
  }

  private String encode(ApplicationNavigation value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private ApplicationNavigation decode(String json) {
    try {
      return mapper.readValue(json, ApplicationNavigation.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Stored navigation is invalid", failure);
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DOCUMENT_INVALID, "Invalid application navigation");
  }

  private static ApplicationFormException conflict() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT,
        "Navigation changed or a previous update is pending");
  }
}
