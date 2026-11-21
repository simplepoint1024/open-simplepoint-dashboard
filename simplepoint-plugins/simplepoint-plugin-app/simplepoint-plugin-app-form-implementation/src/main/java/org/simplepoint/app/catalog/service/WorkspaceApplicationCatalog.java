package org.simplepoint.app.catalog.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftRevision;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationReadModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistence boundary for tenant-owned application and page metadata. */
@Service
@Transactional(readOnly = true)
public class WorkspaceApplicationCatalog {
  private final EntityManager em;
  private final ApplicationFormService forms;

  /** Creates the transaction-scoped application catalog. */
  public WorkspaceApplicationCatalog(EntityManager em, ApplicationFormService forms) {
    this.em = em;
    this.forms = forms;
  }

  /** Input for an application and its required main page. */
  public record CreateApplication(
      String name, String description, String mainPageName, boolean navigationGroup,
      org.simplepoint.api.locale.LocalizedText nameLocalization, org.simplepoint.api.locale.LocalizedText mainPageNameLocalization) {
    public CreateApplication(String name, String description, String mainPageName, boolean navigationGroup) {
      this(name, description, mainPageName, navigationGroup, null, null);
    }
    /** Keeps callers without an application directory source compatible. */
    public CreateApplication(String name, String description, String mainPageName) {
      this(name, description, mainPageName, false);
    }
  }

  /** Input for an additional application page. */
  public record CreatePage(String name, String description, org.simplepoint.api.locale.LocalizedText nameLocalization) {
    public CreatePage(String name, String description) {this(name, description, null);}
  }

  public record CatalogRevision(String id, Instant expectedUpdatedAt) {}

  /** Scalar list contract including projected page metadata. */
  @TableQuery(
      value = {
        @QueryField("name"),
        @QueryField("description"),
        @QueryField("mainPageName"),
        @QueryField("attachedPageCount"),
        @QueryField("status"),
        @QueryField("updatedAt")
      },
      maxPageSize = TablePagination.MAX_PAGE_SIZE)
  public record ApplicationSummary(
      String id,
      String name,
      String description,
      String icon,
      String status,
      String mainPageId,
      String mainPageName,
      long attachedPageCount,
      String createdBy,
      Instant createdAt,
      Instant updatedAt, org.simplepoint.api.locale.LocalizedText nameLocalization,
      org.simplepoint.api.locale.LocalizedText mainPageNameLocalization) {}

  /** Read-only page directory item. */
  public record PageSummary(
      String id,
      String name,
      String description,
      String kind,
      String status,
      int sortOrder,
      boolean resourceRegistered,
      String runtimePath,
      String formId, org.simplepoint.api.locale.LocalizedText nameLocalization, Instant updatedAt) {}

  /** Application metadata and its page directory. */
  public record ApplicationDetail(ApplicationSummary application, List<PageSummary> pages) {}

  /** Bounded publication selector; identities are still validated again when publishing. */
  @TableQuery(
      value = {
        @QueryField("id"),
        @QueryField("name"),
        @QueryField("kind"),
        @QueryField("status"),
        @QueryField("formId"),
        @QueryField("formVersion"),
        @QueryField("sortOrder")
      },
      maxPageSize = TablePagination.MAX_PAGE_SIZE)
  public record PublicationPage(
      String id,
      String name,
      String kind,
      String status,
      String formId,
      Integer formVersion,
      int sortOrder) {}

  /** Queries publication destinations without materializing the application's page directory. */
  public Page<PublicationPage> publicationPages(
      String applicationId, Map<String, String> parameters, Pageable page) {
    String tenant = tenantId();
    findApplication(tenant, applicationId);
    return ProjectionPages.query(
        em,
        WorkspaceApplicationPage.class,
        PublicationPage.class,
        parameters,
        page,
        "sortOrder",
        Set.of("name"),
        (cb, criteria, root) ->
            cb.and(
                cb.equal(root.get("tenantId"), tenant),
                cb.equal(root.get("applicationId"), applicationId)),
        row ->
            new PublicationPage(
                row.getId(),
                row.getName(),
                row.getKind(),
                row.getStatus(),
                row.getFormId(),
                row.getFormVersion(),
                row.getSortOrder()));
  }

  /** Current actor's creation capabilities. */
  public record Permissions(
      boolean canCreateApplication, boolean canAddPage, boolean canDesignForm,
      boolean canDeleteApplication, boolean canDeletePage, boolean canManageForms) {}

  /** Resolves workspace-bound application and page creation rights. */
  public Permissions permissions() {
    tenantId();
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    Collection<String> resources =
        context.getResources() == null ? List.of() : context.getResources();
    boolean administrator = Boolean.TRUE.equals(context.getIsAdministrator());
    return new Permissions(
        administrator || resources.contains("app.workbench.apps.create"),
        administrator || resources.contains("app.workbench.pages.create"),
        administrator || resources.contains("app.workbench.forms.edit"),
        administrator || resources.contains("app.workbench.apps.delete"),
        administrator || resources.contains("app.workbench.pages.delete"),
        administrator || List.of("create", "edit", "publish", "delete").stream()
            .anyMatch(action -> resources.contains("app.workbench.forms." + action)));
  }

  /** Returns readable scalar fields and allowed query operators. */
  public Map<String, Object> schema() {
    tenantId();
    return ProjectionTableSchema.schema(
        ApplicationSummary.class,
        "app.application",
        Map.of(
            "status",
            List.of(
                new ProjectionTableSchema.Choice("DRAFT", "i18n:app.status.draft"),
                new ProjectionTableSchema.Choice("PUBLISHED", "i18n:app.status.published"),
                new ProjectionTableSchema.Choice("OFFLINING", "i18n:app.resource.offlining"),
                new ProjectionTableSchema.Choice("OFFLINE", "i18n:app.resource.offline"),
                new ProjectionTableSchema.Choice("DELETING", "i18n:app.application.deleting"))));
  }

  /** Adapts the legacy literal keyword query to bounded pagination. */
  public Page<ApplicationSummary> list(String keyword, int page, int size) {
    int boundedSize = Math.max(1, Math.min(TablePagination.MAX_PAGE_SIZE, size));
    int boundedPage = Math.max(0, Math.min(page, Integer.MAX_VALUE / boundedSize));
    return list(
        keyword == null ? Map.of() : Map.of("keyword", keyword),
        PageRequest.of(boundedPage, boundedSize));
  }

  /** Queries scoped application metadata before counting and pagination. */
  public Page<ApplicationSummary> list(Map<String, String> parameters, Pageable page) {
    String tenantId = tenantId();
    var filters = new LinkedHashMap<>(parameters == null ? Map.<String, String>of() : parameters);
    String keyword = filters.remove("keyword");
    String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    if (search.length() > 100) {
      throw badRequest("搜索词不能超过 100 个字符");
    }
    if (!search.isEmpty()) {
      QueryCapabilities.require(ApplicationSummary.class, "name", "like", false);
      QueryCapabilities.require(ApplicationSummary.class, "description", "like", false);
    }
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return ProjectionPages.query(
        em,
        WorkspaceApplicationReadModel.class,
        ApplicationSummary.class,
        filters,
        page,
        "updatedAt",
        Set.of("name", "description", "mainPageName"),
        (cb, criteria, root) ->
            cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                search.isEmpty()
                    ? cb.conjunction()
                    : cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '!'),
                        cb.like(cb.lower(root.get("description")), pattern, '!'))),
        row ->
            new ApplicationSummary(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getIcon(),
                row.getStatus(),
                row.getMainPageId(),
                row.getMainPageName(),
                row.getAttachedPageCount(),
                row.getCreatedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt(), row.getNameLocalization(), row.getMainPageNameLocalization()));
  }

  /** Loads the tenant-owned application and its page directory. */
  public ApplicationDetail detail(String id) {
    String tenantId = tenantId();
    WorkspaceApplication app = findApplication(tenantId, id);
    List<WorkspaceApplicationPage> pages = pages(tenantId, id);
    String mainName =
        pages.stream()
            .filter(p -> p.getId().equals(app.getMainPageId()))
            .map(WorkspaceApplicationPage::getName)
            .findFirst()
            .orElse("");
    long attachedCount = pages.stream().filter(p -> "ATTACHED".equals(p.getKind())).count();
    return new ApplicationDetail(
        summary(app, mainName, attachedCount, pages.stream().filter(page -> page.getId().equals(app.getMainPageId())).map(WorkspaceApplicationPage::getNameLocalization).filter(java.util.Objects::nonNull).findFirst().orElse(null)), pages.stream().map(this::pageSummary).toList());
  }

  /** Creates an application and main page in one transaction. */
  @Transactional
  public ApplicationDetail create(CreateApplication command) {
    String tenantId = tenantId();
    if (command == null) {
      throw badRequest("请填写应用信息");
    }
    String name = requiredName(command.name(), "应用名称");
    final String mainName = requiredName(command.mainPageName(), "主页面名称");
    String description = description(command.description());
    Instant now = Instant.now();
    String appId = UUID.randomUUID().toString();
    String mainId = UUID.randomUUID().toString();
    WorkspaceApplication app = new WorkspaceApplication();
    app.setId(appId);
    app.setTenantId(tenantId);
    app.setName(name);
    app.setNameLocalization(localizedName(name, command.nameLocalization()));
    app.setDescription(description);
    app.setNavigationGroup(command.navigationGroup());
    app.setStatus("DRAFT");
    app.setMainPageId(mainId);
    app.setCreatedAt(now);
    app.setUpdatedAt(now);
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    app.setCreatedBy(context.getUserId());
    WorkspaceApplicationPage main = new WorkspaceApplicationPage();
    main.setId(mainId);
    main.setTenantId(tenantId);
    main.setApplicationId(appId);
    main.setKind("MAIN");
    main.setName(mainName);
    main.setNameLocalization(localizedName(mainName, command.mainPageNameLocalization()));
    main.setSortOrder(0);
    main.setStatus("DRAFT");
    main.setCreatedAt(now);
    main.setUpdatedAt(now);
    em.persist(app);
    em.persist(main);
    return new ApplicationDetail(summary(app, mainName, 0, main.getNameLocalization()), List.of(pageSummary(main)));
  }

  /** Adds a page after verifying ownership and creation rights. */
  @Transactional
  public PageSummary addPage(String applicationId, CreatePage command) {
    String tenantId = tenantId();
    final WorkspaceApplication app = findApplication(tenantId, applicationId, LockModeType.PESSIMISTIC_WRITE);
    if ("DELETING".equals(app.getStatus())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED, "Application removal is in progress");
    }
    if (app.getNavigationPending() != null) throw new ApplicationFormException(ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT, "Navigation update is pending");
    if (command == null) {
      throw badRequest("请填写页面信息");
    }
    String name = requiredName(command.name(), "页面名称");
    String description = description(command.description());
    int nextSort =
        em.createQuery(
                    "select coalesce(max(p.sortOrder), 0) from WorkspaceApplicationPage p"
                        + " where p.tenantId = :tenant and p.applicationId = :applicationId",
                    Integer.class)
                .setParameter("tenant", tenantId)
                .setParameter("applicationId", applicationId)
                .getSingleResult()
            + 10;
    Instant now = Instant.now();
    WorkspaceApplicationPage page = new WorkspaceApplicationPage();
    page.setId(UUID.randomUUID().toString());
    page.setTenantId(tenantId);
    page.setApplicationId(applicationId);
    page.setKind("ATTACHED");
    page.setName(name);
    page.setNameLocalization(localizedName(name, command.nameLocalization()));
    page.setDescription(description);
    page.setSortOrder(nextSort);
    page.setStatus("DRAFT");
    page.setCreatedAt(now);
    page.setUpdatedAt(now);
    em.persist(page);
    app.setUpdatedAt(now);
    return pageSummary(page);
  }

  /** Removes an unpublished application, its draft forms and its draft pages in one transaction. */
  @Transactional
  public void deleteApplication(String applicationId, Instant expectedUpdatedAt) {
    String tenant = tenantId();
    WorkspaceApplication app = findApplication(tenant, applicationId, LockModeType.PESSIMISTIC_WRITE);
    requireUnchanged(app.getUpdatedAt(), expectedUpdatedAt);
    if (!"DRAFT".equals(app.getStatus()) || app.getNavigationPending() != null
        || app.getNavigationRevision() != 0 || app.getNavigationDefinition() != null) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
          "The application has publication history or managed navigation");
    }
    List<WorkspaceApplicationPage> pages = pages(tenant, applicationId);
    if (pages.stream().anyMatch(page -> !unpublished(page))) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
          "The application has pages with publication history");
    }
    var drafts = em.createQuery(
            "select new org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftRevision(f.id, f.draftRevision)"
                + " from ApplicationFormEntity f where f.tenantId = :tenant and f.applicationId = :app",
            ApplicationFormDraftRevision.class)
        .setParameter("tenant", tenant).setParameter("app", applicationId).getResultList();
    if (!drafts.isEmpty()) {
      forms.deleteDrafts(applicationId, drafts);
    }
    pages.forEach(em::remove);
    em.remove(app);
  }

  @Transactional
  public void deleteApplications(List<CatalogRevision> selection) {
    orderedSelection(selection).forEach(entry -> deleteApplication(entry.id(), entry.expectedUpdatedAt()));
  }

  /** The application lock serializes page removal with publication and navigation changes. */
  @Transactional
  public void deletePage(String applicationId, String pageId, Instant expectedUpdatedAt) {
    String tenant = tenantId();
    WorkspaceApplication app = findApplication(tenant, applicationId, LockModeType.PESSIMISTIC_WRITE);
    WorkspaceApplicationPage page = em.find(WorkspaceApplicationPage.class, pageId, LockModeType.PESSIMISTIC_WRITE);
    if (page == null || !tenant.equals(page.getTenantId()) || !applicationId.equals(page.getApplicationId())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PAGE_NOT_FOUND, "Page not found");
    }
    requireUnchanged(page.getUpdatedAt(), expectedUpdatedAt);
    if (!"ATTACHED".equals(page.getKind()) || !unpublished(page) || app.getNavigationPending() != null
        || "DELETING".equals(app.getStatus())
        || !em.createQuery(
                "select b.id from ApplicationFormPageBindingEntity b where b.tenantId = :tenant and b.pageId = :page",
                String.class)
            .setParameter("tenant", tenant).setParameter("page", pageId)
            .setMaxResults(1).getResultList().isEmpty()) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PAGE_DELETE_BLOCKED,
          "Only an unpublished attached page without a pending operation can be removed");
    }
    em.remove(page);
    app.setUpdatedAt(Instant.now());
  }

  @Transactional
  public void deletePages(String applicationId, List<CatalogRevision> selection) {
    orderedSelection(selection).forEach(entry -> deletePage(applicationId, entry.id(), entry.expectedUpdatedAt()));
  }

  private static List<CatalogRevision> orderedSelection(List<CatalogRevision> selection) {
    if (selection == null || selection.isEmpty() || selection.size() > TablePagination.MAX_PAGE_SIZE) {
      throw badRequest("Invalid catalog selection");
    }
    var byId = new java.util.TreeMap<String, CatalogRevision>();
    for (var entry : selection) {
      if (entry == null || entry.id() == null || entry.expectedUpdatedAt() == null
          || byId.putIfAbsent(entry.id(), entry) != null) {
        throw badRequest("Each selected item requires a distinct identity and update time");
      }
    }
    return List.copyOf(byId.values());
  }

  private static boolean unpublished(WorkspaceApplicationPage page) {
    return "DRAFT".equals(page.getStatus()) && page.getResourceId() == null
        && page.getResourceCode() == null && page.getFormId() == null;
  }

  private static void requireUnchanged(Instant updatedAt, Instant expected) {
    if (!updatedAt.equals(expected)) {
      throw new ApplicationFormException(ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT,
          "The catalog item changed after it was read");
    }
  }

  private List<WorkspaceApplicationPage> pages(String tenantId, String applicationId) {
    return em.createQuery(
            "select p from WorkspaceApplicationPage p"
                + " where p.tenantId = :tenant and p.applicationId = :applicationId"
                + " order by p.sortOrder, p.createdAt",
            WorkspaceApplicationPage.class)
        .setParameter("tenant", tenantId)
        .setParameter("applicationId", applicationId)
        .getResultList();
  }

  private WorkspaceApplication findApplication(String tenantId, String id) {
    return findApplication(tenantId, id, LockModeType.NONE);
  }

  private WorkspaceApplication findApplication(String tenantId, String id, LockModeType lock) {
    WorkspaceApplication app = lock == LockModeType.NONE
        ? em.find(WorkspaceApplication.class, id)
        : em.find(WorkspaceApplication.class, id, lock);
    if (app == null || !tenantId.equals(app.getTenantId())) {
      throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_NOT_FOUND, "应用不存在");
    }
    return app;
  }

  private ApplicationSummary summary(
      WorkspaceApplication app, String mainName, long attachedCount, org.simplepoint.api.locale.LocalizedText mainLocalization) {
    return new ApplicationSummary(
        app.getId(),
        app.getName(),
        app.getDescription(),
        app.getIcon(),
        app.getStatus(),
        app.getMainPageId(),
        mainName,
        attachedCount,
        app.getCreatedBy(),
        app.getCreatedAt(),
        app.getUpdatedAt(), app.getNameLocalization(),
        mainLocalization);
  }

  private PageSummary pageSummary(WorkspaceApplicationPage page) {
    boolean registered = page.getResourceId() != null && !page.getResourceId().isBlank();
    return new PageSummary(
        page.getId(),
        page.getName(),
        page.getDescription(),
        page.getKind(),
        page.getStatus(),
        page.getSortOrder(),
        registered,
        registered ? "/app/runtime/" + page.getApplicationId() + "/" + page.getId() : null,
        page.getFormId(), page.getNameLocalization(), page.getUpdatedAt());
  }

  private static String tenantId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenantId = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (context == null
        || context.getScopeType() != AuthorizationScopeType.TENANT
        || tenantId == null
        || tenantId.isBlank()) {
      throw new AccessDeniedException("请切换到组织工作空间");
    }
    return tenantId;
  }

  private static String requiredName(String value, String label) {
    if (value == null || value.isBlank()) {
      throw badRequest("请填写" + label);
    }
    String trimmed = value.trim();
    if (trimmed.codePointCount(0, trimmed.length()) > 100) {
      throw badRequest(label + "不能超过 100 个字符");
    }
    return trimmed;
  }

  private static org.simplepoint.api.locale.LocalizedText localizedName(String name, org.simplepoint.api.locale.LocalizedText value) {
    if (value == null) return null;
    try {
      var validated = org.simplepoint.api.locale.LocalizedTexts.validate(value, 100, 32);
      if (!name.equals(validated.defaultValue())) throw badRequest("Localized default must match name");
      return validated;
    } catch (IllegalArgumentException failure) {throw badRequest("Invalid localized name");}
  }

  private static String description(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > 500) {
      throw badRequest("描述不能超过 500 个字符");
    }
    return trimmed;
  }

  private static ApplicationFormException badRequest(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
