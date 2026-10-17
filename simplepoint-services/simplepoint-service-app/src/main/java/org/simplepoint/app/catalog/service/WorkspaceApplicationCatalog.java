package org.simplepoint.app.catalog.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationReadModel;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionPages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persistence boundary for tenant-owned application and page metadata. */
@Service
@Transactional(readOnly = true)
public class WorkspaceApplicationCatalog {
  private final EntityManager em;

  /** Creates the transaction-scoped application catalog. */
  public WorkspaceApplicationCatalog(EntityManager em) {
    this.em = em;
  }

  /** Input for an application and its required main page. */
  public record CreateApplication(String name, String description, String mainPageName,
      boolean navigationGroup) {
    /** Keeps callers without an application directory source compatible. */
    public CreateApplication(String name, String description, String mainPageName) {
      this(name, description, mainPageName, false);
    }
  }

  /** Input for an additional application page. */
  public record CreatePage(String name, String description) {}

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
      maxPageSize = 100)
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
      Instant updatedAt) {}

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
      String formId) {}

  /** Application metadata and its page directory. */
  public record ApplicationDetail(ApplicationSummary application, List<PageSummary> pages) {}

  /** Bounded publication selector; identities are still validated again when publishing. */
  @TableQuery(value = {
      @QueryField("id"), @QueryField("name"), @QueryField("kind"), @QueryField("status"),
      @QueryField("formId"), @QueryField("formVersion"), @QueryField("sortOrder")
  }, maxPageSize = 100)
  public record PublicationPage(
      String id, String name, String kind, String status, String formId, Integer formVersion,
      int sortOrder) {}

  /** Queries publication destinations without materializing the application's page directory. */
  public Page<PublicationPage> publicationPages(
      String applicationId, Map<String, String> parameters, Pageable page) {
    String tenant = tenantId();
    findApplication(tenant, applicationId);
    return ProjectionPages.query(
        em, WorkspaceApplicationPage.class, PublicationPage.class, parameters, page,
        "sortOrder", Set.of("name"),
        (cb, criteria, root) -> cb.and(
            cb.equal(root.get("tenantId"), tenant),
            cb.equal(root.get("applicationId"), applicationId)),
        row -> new PublicationPage(row.getId(), row.getName(), row.getKind(), row.getStatus(),
            row.getFormId(), row.getFormVersion(), row.getSortOrder()));
  }

  /** Current actor's creation capabilities. */
  public record Permissions(boolean canCreateApplication, boolean canAddPage, boolean canDesignForm) {}

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
        administrator || resources.contains("app.workbench.forms.edit"));
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
                new ProjectionTableSchema.Choice("PUBLISHED", "i18n:app.status.published"))));
  }

  /** Adapts the legacy literal keyword query to bounded pagination. */
  public Page<ApplicationSummary> list(String keyword, int page, int size) {
    int boundedSize = Math.max(1, Math.min(100, size));
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
                row.getUpdatedAt()));
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
        summary(app, mainName, attachedCount), pages.stream().map(this::pageSummary).toList());
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
    main.setSortOrder(0);
    main.setStatus("DRAFT");
    main.setCreatedAt(now);
    main.setUpdatedAt(now);
    em.persist(app);
    em.persist(main);
    return new ApplicationDetail(summary(app, mainName, 0), List.of(pageSummary(main)));
  }

  /** Adds a page after verifying ownership and creation rights. */
  @Transactional
  public PageSummary addPage(String applicationId, CreatePage command) {
    String tenantId = tenantId();
    final WorkspaceApplication app = findApplication(tenantId, applicationId);
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
    page.setDescription(description);
    page.setSortOrder(nextSort);
    page.setStatus("DRAFT");
    page.setCreatedAt(now);
    page.setUpdatedAt(now);
    em.persist(page);
    app.setUpdatedAt(now);
    return pageSummary(page);
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
    WorkspaceApplication app = em.find(WorkspaceApplication.class, id);
    if (app == null || !tenantId.equals(app.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在");
    }
    return app;
  }

  private ApplicationSummary summary(
      WorkspaceApplication app, String mainName, long attachedCount) {
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
        app.getUpdatedAt());
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
        page.getFormId());
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
    if (trimmed.length() > 120) {
      throw badRequest(label + "不能超过 120 个字符");
    }
    return trimmed;
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

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
