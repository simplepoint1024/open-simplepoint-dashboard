package org.simplepoint.app.catalog.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

  public WorkspaceApplicationCatalog(EntityManager em) {
    this.em = em;
  }

  public record CreateApplication(String name, String description, String mainPageName) {}

  public record CreatePage(String name, String description) {}

  public record ApplicationSummary(String id, String name, String description, String icon,
                                   String status, String mainPageId, String mainPageName,
                                   long attachedPageCount, String createdBy, Instant createdAt,
                                   Instant updatedAt) {}

  public record PageSummary(String id, String name, String description, String kind,
                            String status, int sortOrder, boolean resourceRegistered,
                            String runtimePath) {}

  public record ApplicationDetail(ApplicationSummary application, List<PageSummary> pages) {}

  public record Permissions(boolean canCreateApplication, boolean canAddPage) {}

  public Permissions permissions() {
    tenantId();
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    Collection<String> resources = context.getResources() == null ? List.of() : context.getResources();
    boolean administrator = Boolean.TRUE.equals(context.getIsAdministrator());
    return new Permissions(administrator || resources.contains("app.workbench.apps.create"),
        administrator || resources.contains("app.workbench.pages.create"));
  }

  public Page<ApplicationSummary> list(String keyword, int page, int size) {
    String tenantId = tenantId();
    int boundedSize = Math.max(1, Math.min(100, size));
    int boundedPage = Math.max(0, Math.min(page, Integer.MAX_VALUE / boundedSize));
    String search = keyword == null ? "" : keyword.trim().toLowerCase();
    if (search.length() > 100) throw badRequest("搜索词不能超过 100 个字符");
    String condition = "a.tenantId = :tenant and (:search = '' or lower(a.name) like :pattern escape '!'"
        + " or lower(coalesce(a.description, '')) like :pattern escape '!')";
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    List<WorkspaceApplication> applications = em.createQuery(
            "select a from WorkspaceApplication a where " + condition
                + " order by a.updatedAt desc, a.id desc", WorkspaceApplication.class)
        .setParameter("tenant", tenantId).setParameter("search", search)
        .setParameter("pattern", pattern)
        .setFirstResult(boundedPage * boundedSize).setMaxResults(boundedSize).getResultList();
    long total = em.createQuery("select count(a) from WorkspaceApplication a where " + condition,
            Long.class)
        .setParameter("tenant", tenantId).setParameter("search", search)
        .setParameter("pattern", pattern).getSingleResult();
    if (applications.isEmpty()) return new PageImpl<>(List.of(), PageRequest.of(boundedPage, boundedSize), total);

    List<String> appIds = applications.stream().map(WorkspaceApplication::getId).toList();
    List<String> mainIds = applications.stream().map(WorkspaceApplication::getMainPageId).toList();
    Map<String, String> mainNames = new HashMap<>();
    for (WorkspaceApplicationPage main : em.createQuery(
            "select p from WorkspaceApplicationPage p where p.tenantId = :tenant and p.id in :ids",
            WorkspaceApplicationPage.class)
        .setParameter("tenant", tenantId).setParameter("ids", mainIds).getResultList()) {
      mainNames.put(main.getId(), main.getName());
    }
    Map<String, Long> attachedCounts = new HashMap<>();
    for (Object[] count : em.createQuery(
            "select p.applicationId, count(p) from WorkspaceApplicationPage p"
                + " where p.tenantId = :tenant and p.applicationId in :ids and p.kind = 'ATTACHED'"
                + " group by p.applicationId", Object[].class)
        .setParameter("tenant", tenantId).setParameter("ids", appIds).getResultList()) {
      attachedCounts.put((String) count[0], (Long) count[1]);
    }
    return new PageImpl<>(applications.stream()
        .map(app -> summary(app, mainNames.get(app.getMainPageId()),
            attachedCounts.getOrDefault(app.getId(), 0L))).toList(),
        PageRequest.of(boundedPage, boundedSize), total);
  }

  public ApplicationDetail detail(String id) {
    String tenantId = tenantId();
    WorkspaceApplication app = findApplication(tenantId, id);
    List<WorkspaceApplicationPage> pages = pages(tenantId, id);
    String mainName = pages.stream().filter(p -> p.getId().equals(app.getMainPageId()))
        .map(WorkspaceApplicationPage::getName).findFirst().orElse("");
    long attachedCount = pages.stream().filter(p -> "ATTACHED".equals(p.getKind())).count();
    return new ApplicationDetail(summary(app, mainName, attachedCount),
        pages.stream().map(this::pageSummary).toList());
  }

  @Transactional
  public ApplicationDetail create(CreateApplication command) {
    String tenantId = tenantId();
    if (command == null) throw badRequest("请填写应用信息");
    String name = requiredName(command.name(), "应用名称");
    String mainName = requiredName(command.mainPageName(), "主页面名称");
    String description = description(command.description());
    Instant now = Instant.now();
    String appId = UUID.randomUUID().toString();
    String mainId = UUID.randomUUID().toString();
    WorkspaceApplication app = new WorkspaceApplication();
    app.setId(appId);
    app.setTenantId(tenantId);
    app.setName(name);
    app.setDescription(description);
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

  @Transactional
  public PageSummary addPage(String applicationId, CreatePage command) {
    String tenantId = tenantId();
    WorkspaceApplication app = findApplication(tenantId, applicationId);
    if (command == null) throw badRequest("请填写页面信息");
    String name = requiredName(command.name(), "页面名称");
    String description = description(command.description());
    int nextSort = em.createQuery(
            "select coalesce(max(p.sortOrder), 0) from WorkspaceApplicationPage p"
                + " where p.tenantId = :tenant and p.applicationId = :applicationId", Integer.class)
        .setParameter("tenant", tenantId).setParameter("applicationId", applicationId)
        .getSingleResult() + 10;
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
    return em.createQuery("select p from WorkspaceApplicationPage p"
            + " where p.tenantId = :tenant and p.applicationId = :applicationId"
            + " order by p.sortOrder, p.createdAt", WorkspaceApplicationPage.class)
        .setParameter("tenant", tenantId).setParameter("applicationId", applicationId)
        .getResultList();
  }

  private WorkspaceApplication findApplication(String tenantId, String id) {
    WorkspaceApplication app = em.find(WorkspaceApplication.class, id);
    if (app == null || !tenantId.equals(app.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在");
    }
    return app;
  }

  private ApplicationSummary summary(WorkspaceApplication app, String mainName, long attachedCount) {
    return new ApplicationSummary(app.getId(), app.getName(), app.getDescription(), app.getIcon(),
        app.getStatus(), app.getMainPageId(), mainName, attachedCount, app.getCreatedBy(),
        app.getCreatedAt(), app.getUpdatedAt());
  }

  private PageSummary pageSummary(WorkspaceApplicationPage page) {
    boolean registered = page.getResourceId() != null && !page.getResourceId().isBlank();
    return new PageSummary(page.getId(), page.getName(), page.getDescription(), page.getKind(),
        page.getStatus(), page.getSortOrder(), registered,
        registered ? "/app/runtime/" + page.getApplicationId() + "/" + page.getId() : null);
  }

  private static String tenantId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenantId = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (context == null || context.getScopeType() != AuthorizationScopeType.TENANT
        || tenantId == null || tenantId.isBlank()) {
      throw new AccessDeniedException("请切换到组织工作空间");
    }
    return tenantId;
  }

  private static String requiredName(String value, String label) {
    if (value == null || value.isBlank()) throw badRequest("请填写" + label);
    String trimmed = value.trim();
    if (trimmed.length() > 120) throw badRequest(label + "不能超过 120 个字符");
    return trimmed;
  }

  private static String description(String value) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    if (trimmed.length() > 500) throw badRequest("描述不能超过 500 个字符");
    return trimmed;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
