package org.simplepoint.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded design metadata and authorized runtime entry discovery for the registered APP adapter. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormWorkflowCatalog {
  private final EntityManager entityManager;
  private final FormRequestContext actors;
  private final FormDocumentCodec documents;
  public ApplicationFormWorkflowCatalog(EntityManager entityManager, FormRequestContext actors, FormDocumentCodec documents) {
    this.entityManager = entityManager;
    this.actors = actors;
    this.documents = documents;
  }

  public record Choice(String id, String name, String formId, int version) { }
  public record Field(String fieldId, String label, String type) { }
  public record Scene(String sceneId, String name, String type) { }
  public record Detail(Choice choice, List<Field> fields, List<Scene> scenes) { }
  public record RuntimePage(String id, String name, String applicationId, String pageId) { }

  public Page<Choice> forms(String keyword, int page, int size) {
    String tenant = actors.requireActor().tenantId();
    var bounds = bounds(page, size);
    String from = " from ApplicationFormEntity form, ApplicationFormVersionEntity version, WorkspaceApplication application "
        + "where form.tenantId = :tenant and version.tenantId = :tenant and application.tenantId = :tenant "
        + "and form.applicationId = application.id and version.formId = form.id and version.physicalModelVersion is not null "
        + "and form.name like :keyword escape '!' and exists (select page.id from WorkspaceApplicationPage page "
        + "where page.tenantId = :tenant and page.formId = form.id and page.formVersion = version.versionNumber and page.status = 'PUBLISHED')";
    var query = entityManager.createQuery("select form.id, form.name, version.versionNumber, application.name" + from
        + " order by application.name, form.name, form.id, version.versionNumber desc", Object[].class);
    var count = entityManager.createQuery("select count(version)" + from, Long.class);
    for (var target : List.of(query, count)) target.setParameter("tenant", tenant).setParameter("keyword", search(keyword));
    var values = query.setFirstResult((int) bounds.getOffset()).setMaxResults(bounds.getPageSize()).getResultList().stream().map(row ->
        new Choice(row[0] + ":" + row[2], row[3] + " / " + row[1] + " · v" + row[2], (String) row[0], (Integer) row[2])).toList();
    return new PageImpl<>(values, bounds, count.getSingleResult());
  }

  public Detail detail(String identity) {
    String[] parts = identity == null ? new String[0] : identity.split(":");
    if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9-]{1,36}") || !parts[1].matches("[1-9][0-9]{0,8}")) throw unavailable();
    String tenant = actors.requireActor().tenantId();
    var row = entityManager.createQuery("select version, form.name, application.name from ApplicationFormVersionEntity version, "
        + "ApplicationFormEntity form, WorkspaceApplication application where version.tenantId = :tenant and form.tenantId = :tenant "
        + "and application.tenantId = :tenant and version.formId = form.id and form.applicationId = application.id "
        + "and form.id = :form and version.versionNumber = :version and version.physicalModelVersion is not null "
        + "and exists (select publication.id from ApplicationFormPublicationEntity publication where publication.tenantId = :tenant "
        + "and publication.formId = form.id and publication.formVersion = version.versionNumber and publication.status in :states)", Object[].class)
        .setParameter("tenant", tenant).setParameter("form", parts[0]).setParameter("version", Integer.parseInt(parts[1]))
        .setParameter("states", List.of(org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus.ACTIVE,
            org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus.RETIRED))
        .getResultStream().findFirst().orElseThrow(ApplicationFormWorkflowCatalog::unavailable);
    var version = (ApplicationFormVersionEntity) row[0];
    var document = documents.decode(version.getDocumentJson());
    return new Detail(new Choice(identity, row[2] + " / " + row[1] + " · v" + version.getVersionNumber(), parts[0], version.getVersionNumber()),
        document.fields().stream().filter(field -> Set.of(FormStorageMode.STORED, FormStorageMode.REFERENCE).contains(field.storageMode()))
            .map(field -> new Field(field.fieldId(), field.label(), field.type().name())).toList(),
        document.scenes().stream().map(scene -> new Scene(scene.sceneId(), scene.name(), scene.type().name())).toList());
  }

  public List<Choice> options(List<String> identities) {
    if (identities == null || identities.size() > 50) throw unavailable();
    return identities.stream().distinct().map(this::detail).map(Detail::choice).toList();
  }

  public Page<RuntimePage> pages(String formId, int version, String keyword, int page, int size) {
    var actor = actors.requireActor();
    var authority = AuthorizationContextHolder.getContext();
    var bounds = bounds(page, size);
    boolean admin = Boolean.TRUE.equals(authority.getIsAdministrator());
    Set<String> resources = authority.getResources() == null ? Set.of() : Set.copyOf(authority.getResources());
    if (!admin && resources.isEmpty()) return new PageImpl<>(List.of(), bounds, 0);
    String from = " from WorkspaceApplicationPage page where page.tenantId = :tenant and page.formId = :form "
        + "and page.formVersion = :version and page.status = 'PUBLISHED' and page.name like :keyword escape '!'"
        + (admin ? "" : " and page.resourceCode in :resources and concat(page.resourceCode, '.process.start') in :resources");
    var query = entityManager.createQuery("select page" + from + " order by page.name, page.id", WorkspaceApplicationPage.class);
    var count = entityManager.createQuery("select count(page)" + from, Long.class);
    for (var target : List.of(query, count)) {
      target.setParameter("tenant", actor.tenantId()).setParameter("form", formId).setParameter("version", version).setParameter("keyword", search(keyword));
      if (!admin) target.setParameter("resources", resources);
    }
    var rows = query.setFirstResult((int) bounds.getOffset()).setMaxResults(bounds.getPageSize()).getResultList().stream()
        .map(item -> new RuntimePage(item.getId(), item.getName(), item.getApplicationId(), item.getId())).toList();
    return new PageImpl<>(rows, bounds, count.getSingleResult());
  }

  private static PageRequest bounds(int page, int size) {
    if (page < 0 || page > 10_000 || size < 1 || size > 50) throw new IllegalArgumentException("Invalid form directory page");
    return PageRequest.of(page, size);
  }
  private static String search(String value) {
    if (value == null) return "%";
    if (value.length() > 120) throw new IllegalArgumentException("Search is too long");
    return "%" + value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Published form version is unavailable");
  }
}
