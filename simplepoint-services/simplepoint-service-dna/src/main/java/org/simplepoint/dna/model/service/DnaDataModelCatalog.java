package org.simplepoint.dna.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.dna.model.entity.DnaDataModel;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Platform catalog for draft DNA SQL and native SQL query models. */
@Service
@Transactional(readOnly = true)
public class DnaDataModelCatalog {
  private static final ObjectMapper FILTER_JSON = new ObjectMapper();
  private final EntityManager em;

  public DnaDataModelCatalog(EntityManager em) {
    this.em = em;
  }

  public record Command(String name, String description, String modelType,
                        String dataSourceId, String sqlText, Long version) {}

  public record Summary(String id, String name, String description, String modelType,
                        String dataSourceName, String status, long version,
                        Instant createdAt, Instant updatedAt) {}

  public record Detail(Summary model, String dataSourceId, String sqlText) {}

  public record Action(String key, String title, String text, String icon, String color,
                       int sort, int argumentMinSize, int argumentMaxSize, boolean danger) {}

  public record Permissions(boolean canCreate, boolean canEdit, boolean canDelete,
                            List<Action> actions) {}

  public record SourceOption(String id, String name) {}

  public record TableSchema(Map<String, Object> schema, List<Action> buttons) {}

  /** Standard table metadata: the advertised query contract is compiled by ProjectionQuery. */
  public TableSchema schema() {
    platformScope();
    ObjectNode schema = FILTER_JSON.createObjectNode();
    schema.put("type", "object");
    schema.put("title", "i18n:dna.models.title");
    ObjectNode properties = schema.putObject("properties");
    addColumn(properties, "id", null, "string", false, 0);
    addColumn(properties, "name", "i18n:dna.models.name", "string", true, 10);
    var typeOptions = addColumn(properties, "modelType", "i18n:dna.models.type",
        "string", true, 20).putArray("oneOf");
    typeOptions.addObject().put("const", "DNA_SQL").put("title", "i18n:dna.models.type.dnaSql");
    typeOptions.addObject().put("const", "NATIVE_SQL").put("title", "i18n:dna.models.type.nativeSql");
    addColumn(properties, "dataSourceName", "i18n:dna.models.dataSource", "string", true, 30);
    addColumn(properties, "dataSourceId", "i18n:dna.models.dataSource", "string", false, 31);
    addColumn(properties, "description", "i18n:dna.models.description", "string", true, 40);
    addColumn(properties, "sqlText", "i18n:dna.models.sql", "string", false, 41);
    addColumn(properties, "status", "i18n:dna.models.status", "string", true, 50)
        .putArray("oneOf").addObject().put("const", "DRAFT")
        .put("title", "i18n:dna.models.draft");
    addColumn(properties, "updatedAt", "i18n:dna.models.updatedAt", "string", true, 60)
        .put("format", "date-time");
    QueryCapabilities.applySchema(DnaDataModel.class, schema);
    return new TableSchema(FILTER_JSON.convertValue(schema, new TypeReference<>() {}),
        permissions().actions());
  }

  private static ObjectNode addColumn(ObjectNode properties, String name, String title,
                                      String type, boolean visible, int order) {
    ObjectNode column = properties.putObject(name);
    column.put("type", type);
    if (title != null) column.put("title", title);
    column.put("x-order", order);
    column.putObject("x-ui").put("x-list-visible", visible);
    return column;
  }

  public Permissions permissions() {
    platformScope();
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    var resources = context.getResources() == null ? List.<String>of() : context.getResources();
    boolean admin = Boolean.TRUE.equals(context.getIsAdministrator());
    boolean canCreate = admin || resources.contains("dna.models.data-models.create");
    boolean canEdit = admin || resources.contains("dna.models.data-models.edit");
    boolean canDelete = admin || resources.contains("dna.models.data-models.delete");
    List<Action> actions = new java.util.ArrayList<>();
    actions.add(new Action("view", "i18n:dna.models.view", "i18n:dna.models.view",
        "EyeOutlined", "blue", 0, 1, 1, false));
    if (canCreate) actions.add(new Action("add", "i18n:dna.models.create", "i18n:dna.models.create",
        "PlusCircleOutlined", "blue", 1, 0, 0, false));
    if (canEdit) actions.add(new Action("edit", "i18n:table.button.edit", "i18n:table.button.edit",
        "EditOutlined", "orange", 2, 1, 1, false));
    if (canDelete) actions.add(new Action("delete", "i18n:table.button.delete", "i18n:table.button.delete",
        "MinusCircleOutlined", "danger", 3, 1, 1, true));
    return new Permissions(canCreate, canEdit, canDelete, List.copyOf(actions));
  }

  public Page<Summary> list(String keyword, String modelType, int page, int size) {
    platformScope();
    int boundedSize = Math.max(1, Math.min(100, size));
    int boundedPage = Math.max(0, Math.min(page, Integer.MAX_VALUE / boundedSize));
    String search = keyword == null ? "" : keyword.trim().toLowerCase();
    if (search.length() > 100) throw badRequest("搜索词不能超过 100 个字符");
    String type = modelType == null || modelType.isBlank() ? "" : validType(modelType);
    String condition = "m.deletedAt is null and (:type = '' or m.modelType = :type)"
        + " and (:search = '' or lower(m.name) like :pattern escape '!'"
        + " or lower(coalesce(m.description, '')) like :pattern escape '!')";
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    List<DnaDataModel> models = em.createQuery("select m from DnaDataModel m where " + condition
            + " order by m.updatedAt desc, m.id desc", DnaDataModel.class)
        .setParameter("type", type).setParameter("search", search).setParameter("pattern", pattern)
        .setFirstResult(boundedPage * boundedSize).setMaxResults(boundedSize).getResultList();
    long total = em.createQuery("select count(m) from DnaDataModel m where " + condition, Long.class)
        .setParameter("type", type).setParameter("search", search).setParameter("pattern", pattern)
        .getSingleResult();
    return summaries(models, PageRequest.of(boundedPage, boundedSize), total);
  }

  /** Schema-driven page query; filters, advanced groups, sort and count share one validated contract. */
  public Page<Summary> query(Map<String, String> filters, Pageable requested) {
    platformScope();
    Pageable page = requested.getSort().isSorted() ? requested : PageRequest.of(
        requested.getPageNumber(), requested.getPageSize(), Sort.by(Sort.Direction.DESC, "updatedAt"));
    ProjectionQuery query = ProjectionQuery.prepare(DnaDataModel.class, filters, page);
    var cb = em.getCriteriaBuilder();
    var selection = cb.createQuery(DnaDataModel.class);
    Root<DnaDataModel> root = selection.from(DnaDataModel.class);
    selection.select(root).where(cb.isNull(root.get("deletedAt")),
        query.predicate(cb, queryFields(root)));
    selection.orderBy(query.orders(cb, queryFields(root), root.get("id")));
    List<DnaDataModel> models = em.createQuery(selection)
        .setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    var countQuery = cb.createQuery(Long.class);
    Root<DnaDataModel> countRoot = countQuery.from(DnaDataModel.class);
    countQuery.select(cb.count(countRoot)).where(cb.isNull(countRoot.get("deletedAt")),
        query.predicate(cb, queryFields(countRoot)));
    return summaries(models, page, em.createQuery(countQuery).getSingleResult());
  }

  private static Map<String, Expression<?>> queryFields(Root<DnaDataModel> root) {
    Map<String, Expression<?>> fields = new LinkedHashMap<>();
    for (String name : List.of("name", "modelType", "description", "status", "updatedAt")) {
      fields.put(name, root.get(name));
    }
    return fields;
  }

  private Page<Summary> summaries(List<DnaDataModel> models, Pageable page, long total) {
    List<String> sourceIds = models.stream().map(DnaDataModel::getDataSourceId)
        .filter(id -> id != null && !id.isBlank()).distinct().toList();
    Map<String, String> sourceNames = new HashMap<>();
    if (!sourceIds.isEmpty()) {
      for (JdbcDataSourceDefinition source : em.createQuery(
              "select s from JdbcDataSourceDefinition s where s.id in :ids",
              JdbcDataSourceDefinition.class)
          .setParameter("ids", sourceIds).getResultList()) {
        sourceNames.put(source.getId(), source.getName());
      }
    }
    return new PageImpl<>(models.stream().map(model -> summary(model,
            sourceNames.get(model.getDataSourceId()))).toList(),
        page, total);
  }

  /** Limited source picker for model editors; never exposes JDBC credentials or connection details. */
  public Page<SourceOption> dataSources(String nameFilter, String idFilter, int page, int size) {
    platformScope();
    int boundedSize = Math.max(1, Math.min(100, size));
    int boundedPage = Math.max(0, Math.min(page, Integer.MAX_VALUE / boundedSize));
    String name = filterValue(nameFilter, "name", "like");
    List<String> ids = idValues(idFilter);
    String condition = "s.deletedAt is null and s.enabled = true";
    if (name != null) condition += " and lower(s.name) like :pattern escape '!'";
    if (ids != null) condition += " and s.id in :ids";
    var query = em.createQuery("select s from JdbcDataSourceDefinition s where " + condition
        + " order by s.name, s.id", JdbcDataSourceDefinition.class);
    var count = em.createQuery("select count(s) from JdbcDataSourceDefinition s where " + condition,
        Long.class);
    if (name != null) {
      String pattern = "%" + name.toLowerCase().replace("!", "!!")
          .replace("%", "!%").replace("_", "!_") + "%";
      query.setParameter("pattern", pattern);
      count.setParameter("pattern", pattern);
    }
    if (ids != null) {
      query.setParameter("ids", ids);
      count.setParameter("ids", ids);
    }
    List<SourceOption> content = query.setFirstResult(boundedPage * boundedSize)
        .setMaxResults(boundedSize).getResultList().stream()
        .map(source -> new SourceOption(source.getId(), source.getName())).toList();
    return new PageImpl<>(content, PageRequest.of(boundedPage, boundedSize), count.getSingleResult());
  }

  public Detail detail(String id) {
    platformScope();
    DnaDataModel model = find(id);
    return new Detail(summary(model), model.getDataSourceId(), model.getSqlText());
  }

  @Transactional
  public Detail create(Command command) {
    platformScope();
    if (command == null) throw badRequest("请填写模型信息");
    DnaDataModel model = new DnaDataModel();
    model.setId(UUID.randomUUID().toString());
    apply(model, command);
    model.setStatus("DRAFT");
    model.setCreatedBy(AuthorizationContextHolder.getContext().getUserId());
    model.setCreatedAt(Instant.now());
    model.setUpdatedAt(model.getCreatedAt());
    em.persist(model);
    return new Detail(summary(model), model.getDataSourceId(), model.getSqlText());
  }

  @Transactional
  public Detail update(String id, Command command) {
    platformScope();
    if (command == null || command.version() == null) throw badRequest("缺少模型版本");
    DnaDataModel model = find(id);
    if (command.version() != model.getVersion()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "模型已被其他人修改，请刷新后重试");
    }
    apply(model, command);
    model.setUpdatedAt(Instant.now());
    em.flush();
    return new Detail(summary(model), model.getDataSourceId(), model.getSqlText());
  }

  @Transactional
  public void delete(String id, long version) {
    platformScope();
    DnaDataModel model = find(id);
    if (version != model.getVersion()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "模型已被其他人修改，请刷新后重试");
    }
    model.setDeletedAt(Instant.now());
    model.setUpdatedAt(model.getDeletedAt());
    em.flush();
  }

  private void apply(DnaDataModel model, Command command) {
    if (command.name() == null || command.name().isBlank()) throw badRequest("请填写模型名称");
    String name = command.name().trim();
    if (name.length() > 120) throw badRequest("模型名称不能超过 120 个字符");
    String description = command.description() == null ? null : command.description().trim();
    if (description != null && description.length() > 500) throw badRequest("描述不能超过 500 个字符");
    String type = validType(command.modelType());
    String sourceId = command.dataSourceId();
    if ("NATIVE_SQL".equals(type)) {
      if (sourceId == null || sourceId.isBlank()) throw badRequest("原生 SQL 模型必须选择数据源");
      JdbcDataSourceDefinition source = em.find(JdbcDataSourceDefinition.class, sourceId);
      if (source == null || source.getDeletedAt() != null
          || !Boolean.TRUE.equals(source.getEnabled())) {
        throw badRequest("数据源不存在或未启用");
      }
    } else {
      sourceId = null;
    }
    String sql = command.sqlText() == null ? null : command.sqlText().trim();
    if (sql != null && sql.length() > 100_000) throw badRequest("SQL 不能超过 100000 个字符");
    model.setName(name);
    model.setDescription(description == null || description.isBlank() ? null : description);
    model.setModelType(type);
    model.setDataSourceId(sourceId);
    model.setSqlText(sql == null || sql.isBlank() ? null : sql);
  }

  private Summary summary(DnaDataModel model) {
    String sourceName = null;
    if (model.getDataSourceId() != null) {
      JdbcDataSourceDefinition source = em.find(JdbcDataSourceDefinition.class, model.getDataSourceId());
      sourceName = source == null || source.getDeletedAt() != null ? null : source.getName();
    }
    return summary(model, sourceName);
  }

  private Summary summary(DnaDataModel model, String sourceName) {
    return new Summary(model.getId(), model.getName(), model.getDescription(),
        model.getModelType(), sourceName, model.getStatus(), model.getVersion(),
        model.getCreatedAt(), model.getUpdatedAt());
  }

  private DnaDataModel find(String id) {
    DnaDataModel model = em.find(DnaDataModel.class, id);
    if (model == null || model.getDeletedAt() != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据模型不存在");
    }
    return model;
  }

  private static String validType(String type) {
    if (!"DNA_SQL".equals(type) && !"NATIVE_SQL".equals(type)) {
      throw badRequest("不支持的数据模型类型");
    }
    return type;
  }

  private static String filterValue(String raw, String field, String operation) {
    if (raw == null || raw.isBlank()) return null;
    JsonNode where = filterWhere(raw, field, operation);
    JsonNode value = where.path("value");
    if (!value.isTextual() || value.asText().length() > 100) throw badRequest("无效的搜索条件");
    return value.asText().trim();
  }

  private static List<String> idValues(String raw) {
    if (raw == null || raw.isBlank()) return null;
    JsonNode root;
    try {
      root = FILTER_JSON.readTree(raw);
    } catch (IOException ex) {
      throw badRequest("无效的数据源筛选条件");
    }
    JsonNode where = root.path("where");
    if (!"id".equals(where.path("field").asText())) throw badRequest("无效的数据源筛选条件");
    String op = where.path("op").asText();
    JsonNode value = where.path("value");
    if ("equals".equals(op) && value.isTextual()) return List.of(value.asText());
    if ("in".equals(op) && value.isArray() && !value.isEmpty() && value.size() <= 100) {
      List<String> values = new java.util.ArrayList<>();
      for (JsonNode item : value) {
        if (!item.isTextual() || item.asText().length() > 64) throw badRequest("无效的数据源筛选条件");
        values.add(item.asText());
      }
      return values;
    }
    throw badRequest("无效的数据源筛选条件");
  }

  private static JsonNode filterWhere(String raw, String field, String operation) {
    try {
      JsonNode where = FILTER_JSON.readTree(raw).path("where");
      if (field.equals(where.path("field").asText())
          && operation.equals(where.path("op").asText())) return where;
    } catch (IOException ex) {
      throw badRequest("无效的搜索条件");
    }
    throw badRequest("无效的搜索条件");
  }

  private static void platformScope() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || context.getScopeType() != AuthorizationScopeType.PLATFORM) {
      throw new AccessDeniedException("请切换到平台工作空间");
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
