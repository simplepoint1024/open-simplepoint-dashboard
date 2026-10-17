package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.permission.PermissionExpressionEvaluator;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.springframework.stereotype.Component;

/** Compiles bounded Record requests into parameterized Calcite SQL. */
@Component
public class RecordSqlCompiler {

  private static final String CATALOG = "runtime";
  private static final Map<String, SystemType> SYSTEM_TYPES = Map.ofEntries(
      Map.entry("id", SystemType.STRING),
      Map.entry("tenant_id", SystemType.STRING),
      Map.entry("record_version", SystemType.LONG),
      Map.entry("created_by", SystemType.STRING),
      Map.entry("created_org_dept_id", SystemType.STRING),
      Map.entry("created_at", SystemType.INSTANT),
      Map.entry("updated_by", SystemType.STRING),
      Map.entry("updated_at", SystemType.INSTANT),
      Map.entry("deleted_at", SystemType.INSTANT)
  );

  private final LogicalValueConverter valueConverter;

  /** Creates the compiler with the platform logical value normalizer. */
  public RecordSqlCompiler(final LogicalValueConverter valueConverter) {
    this.valueConverter = valueConverter;
  }

  /** Compiles an authorized page query and a deterministic identity tie-breaker. */
  RecordSelectPlan page(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordPageQuery query
  ) {
    List<String> fields = projection(model, access, query.fields());
    RecordSqlParameters parameters = new RecordSqlParameters();
    String where = where(model, access, query.filter(), parameters);
    String orderBy = orderBy(model, access, query.sorts());
    String sql = "SELECT " + selectList(model, fields) + " FROM " + table(model)
        + " WHERE " + where + " ORDER BY " + orderBy
        + " OFFSET " + ((long) query.page() * query.size()) + " ROWS"
        + " FETCH NEXT " + query.size() + " ROWS ONLY";
    return new RecordSelectPlan(sql, parameters.values(), valueFields(fields), query.size(),
        RecordWireValues.requiresPhysicalProjection(model, fields));
  }

  /** Compiles an authorized count using the same predicate builder as page queries. */
  RecordSelectPlan count(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordFilter filter
  ) {
    RecordSqlParameters parameters = new RecordSqlParameters();
    String sql = "SELECT COUNT(*) AS " + quote("record_count") + " FROM " + table(model)
        + " WHERE " + where(model, access, filter, parameters);
    return new RecordSelectPlan(sql, parameters.values(), List.of(), 1);
  }

  /** Compiles aggregate-only completeness checks using the same tenant and filter predicates. */
  RecordSelectPlan completeness(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordCompletenessQuery query
  ) {
    Map<String, LogicalFieldDefinition> definitions = fields(model);
    RecordSqlParameters parameters = new RecordSqlParameters();
    List<String> aggregates = new ArrayList<>();
    aggregates.add("COUNT(*) AS " + quote("record_count"));
    for (String field : query.fields()) {
      LogicalFieldDefinition definition = definitions.get(field);
      if (definition == null || !access.readableFields().contains(field) || access.maskedFields().contains(field)) {
        throw invalid("Completeness inspection requires a known, readable model field");
      }
      String empty = RecordCompletenessPredicate.compile(
          quote(model.activeCode(field)), definition.type(), parameters, query.jsonSemantics());
      // Keep the projection numeric: COUNT(CASE ...) can become an aggregate FILTER,
      // which some JDBC dialects cannot push down. A numeric SUM remains portable.
      aggregates.add("SUM(CAST(CASE WHEN " + empty
          + " THEN 1 ELSE 0 END AS BIGINT)) AS " + quote(field));
    }
    String where = where(model, access, query.filter(), parameters);
    if (query.condition() != null) {
      where += " AND " + RecordValueConditionCompiler.compile(model, access, query.condition(), parameters, query.jsonSemantics());
    }
    String sql = "SELECT " + String.join(", ", aggregates) + " FROM " + table(model) + " WHERE " + where;
    return new RecordSelectPlan(sql, parameters.values(), query.fields(), 1);
  }

  /** Counts records violating a typed invariant; the database returns only one aggregate. */
  RecordSelectPlan violations(final RuntimeRecordModel model, final RecordAccessContext access, final RecordViolationQuery query) {
    RecordSqlParameters parameters = new RecordSqlParameters();
    String where = where(model, access, query.filter(), parameters) + " AND "
        + RecordValueConditionCompiler.compile(model, access, query.condition(), parameters, query.jsonSemantics());
    return new RecordSelectPlan("SELECT COUNT(*) AS " + quote("violation_count") + " FROM " + table(model)
        + " WHERE " + where, parameters.values(), List.of(), 1);
  }

  /** Compiles an authorized active-record lookup. */
  RecordSelectPlan lookup(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordLookupQuery query
  ) {
    List<String> fields = projection(model, access, query.fields());
    RecordSqlParameters parameters = new RecordSqlParameters();
    String sql = "SELECT " + selectList(model, fields) + " FROM " + table(model)
        + " WHERE " + baseWhere(access, parameters) + " AND " + quote("id")
        + " = " + parameters.add(query.recordId()) + " AND " + scope(model, access, parameters);
    return new RecordSelectPlan(sql, parameters.values(), valueFields(fields), 1,
        RecordWireValues.requiresPhysicalProjection(model, fields));
  }

  /** Compiles an authorization-scoped identity/version probe used after transaction rollback. */
  RecordSelectPlan version(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final String recordId
  ) {
    RecordSqlParameters parameters = new RecordSqlParameters();
    String sql = "SELECT " + quote("id") + ", " + quote("record_version")
        + " FROM " + table(model) + " WHERE " + baseWhere(access, parameters)
        + " AND " + quote("id") + " = " + parameters.add(recordId)
        + " AND " + scope(model, access, parameters);
    return new RecordSelectPlan(sql, parameters.values(), List.of(), 1);
  }

  /** Compiles a tenant-scoped probe for a server-derived create identity. */
  RecordSelectPlan creation(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final String recordId
  ) {
    RecordSqlParameters parameters = new RecordSqlParameters();
    String sql = "SELECT " + quote("id")
        + " FROM " + table(model) + " WHERE " + quote("tenant_id")
        + " = " + parameters.add(access.actor().tenantId())
        + " AND " + quote("id") + " = " + parameters.add(recordId);
    return new RecordSelectPlan(sql, parameters.values(), List.of(), 1);
  }

  /** Compiles one authorized mutation with server-owned identity and audit values. */
  RecordSqlStatement mutation(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordMutation mutation,
      final Instant now
  ) {
    return mutation(model, access, mutation, now, null);
  }

  /** Compiles one mutation, optionally using a precomputed identity for a create. */
  RecordSqlStatement mutation(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordMutation mutation,
      final Instant now,
      final String createRecordId
  ) {
    if (mutation instanceof RecordCreateMutation create) {
      return create(model, access, create, now, createRecordId);
    }
    if (mutation instanceof RecordUpdateMutation update) {
      return update(model, access, update, now);
    }
    if (mutation instanceof RecordDeleteMutation delete) {
      return delete(model, access, delete, now);
    }
    throw invalid("Unsupported record mutation");
  }

  private RecordSqlStatement create(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordCreateMutation mutation,
      final Instant now,
      final String requestedRecordId
  ) {
    Map<String, LogicalFieldDefinition> definitions = fields(model);
    validateWriteFields(access, definitions, mutation.values().keySet());
    for (LogicalFieldDefinition field : definitions.values()) {
      if (!field.nullable() && field.defaultValue() == null
          && !mutation.values().containsKey(field.code())) {
        throw invalid("Required record field is missing: " + field.code());
      }
    }
    String recordId = requestedRecordId == null
        ? UUID.randomUUID().toString() : requestedRecordId;
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    for (LogicalFieldDefinition field : model.requestedSchema().fields()) {
      if (mutation.values().containsKey(field.code())) {
        values.put(model.activeCode(field.code()), logical(field, mutation.values().get(field.code())));
      }
    }
    values.put("id", recordId);
    values.put("tenant_id", access.actor().tenantId());
    values.put("record_version", 0L);
    values.put("created_by", access.actor().actorId());
    values.put("created_org_dept_id", access.organizationId());
    values.put("created_at", Timestamp.from(now));
    values.put("updated_by", access.actor().actorId());
    values.put("updated_at", Timestamp.from(now));
    values.put("deleted_at", null);
    if (!scopeAllowsWrite(access.rowScope(), field -> values.get(model.permissionCode(field)))) {
      throw denied("The created record is outside the active data scope");
    }
    RecordSqlParameters parameters = new RecordSqlParameters();
    String columns = values.keySet().stream().map(RecordSqlCompiler::quote)
        .collect(Collectors.joining(", "));
    String placeholders = values.values().stream().map(parameters::add)
        .collect(Collectors.joining(", "));
    return new RecordSqlStatement(
        "INSERT INTO " + table(model) + " (" + columns + ") VALUES (" + placeholders + ")",
        parameters.values(),
        RecordMutationType.CREATE,
        recordId,
        0L,
        now
    );
  }

  private RecordSqlStatement update(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordUpdateMutation mutation,
      final Instant now
  ) {
    Map<String, LogicalFieldDefinition> definitions = fields(model);
    validateWriteFields(access, definitions, mutation.values().keySet());
    rejectScopeFieldMutation(
        model,
        access.rowScope(),
        mutation.values().keySet().stream().map(model::activeCode).collect(Collectors.toSet())
    );
    RecordSqlParameters parameters = new RecordSqlParameters();
    List<String> assignments = new ArrayList<>();
    for (LogicalFieldDefinition field : model.requestedSchema().fields()) {
      if (mutation.values().containsKey(field.code())) {
        assignments.add(quote(model.activeCode(field.code())) + " = "
            + parameters.add(logical(field, mutation.values().get(field.code()))));
      }
    }
    assignments.add(quote("updated_by") + " = " + parameters.add(access.actor().actorId()));
    assignments.add(quote("updated_at") + " = " + parameters.add(Timestamp.from(now)));
    assignments.add(quote("record_version") + " = "
        + parameters.add(mutation.expectedVersion() + 1));
    String sql = "UPDATE " + table(model) + " SET " + String.join(", ", assignments)
        + " WHERE " + mutationWhere(
        model,
        access,
        mutation.recordId(),
        mutation.expectedVersion(),
        parameters
    );
    return new RecordSqlStatement(
        sql,
        parameters.values(),
        RecordMutationType.UPDATE,
        mutation.recordId(),
        mutation.expectedVersion() + 1,
        now
    );
  }

  private RecordSqlStatement delete(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordDeleteMutation mutation,
      final Instant now
  ) {
    RecordSqlParameters parameters = new RecordSqlParameters();
    String sql = "UPDATE " + table(model) + " SET "
        + quote("deleted_at") + " = " + parameters.add(Timestamp.from(now)) + ", "
        + quote("updated_by") + " = " + parameters.add(access.actor().actorId()) + ", "
        + quote("updated_at") + " = " + parameters.add(Timestamp.from(now)) + ", "
        + quote("record_version") + " = " + parameters.add(mutation.expectedVersion() + 1)
        + " WHERE " + mutationWhere(
        model,
        access,
        mutation.recordId(),
        mutation.expectedVersion(),
        parameters
    );
    return new RecordSqlStatement(
        sql,
        parameters.values(),
        RecordMutationType.DELETE,
        mutation.recordId(),
        mutation.expectedVersion() + 1,
        now
    );
  }

  private String mutationWhere(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final String recordId,
      final long expectedVersion,
      final RecordSqlParameters parameters
  ) {
    return baseWhere(access, parameters)
        + " AND " + quote("id") + " = " + parameters.add(recordId)
        + " AND " + quote("record_version") + " = " + parameters.add(expectedVersion)
        + " AND " + scope(model, access, parameters);
  }

  private String where(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordFilter filter,
      final RecordSqlParameters parameters
  ) {
    StringBuilder result = new StringBuilder(baseWhere(access, parameters))
        .append(" AND ").append(scope(model, access, parameters));
    if (filter != null) {
      result.append(" AND ").append(filter(model, access, filter, parameters));
    }
    return result.toString();
  }

  private static String baseWhere(
      final RecordAccessContext access,
      final RecordSqlParameters parameters
  ) {
    return quote("tenant_id") + " = " + parameters.add(access.actor().tenantId())
        + " AND " + quote("deleted_at") + " IS NULL";
  }

  private String filter(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordFilter filter,
      final RecordSqlParameters parameters
  ) {
    if (filter.operator().group()) {
      List<String> children = filter.children().stream()
          .map(child -> filter(model, access, child, parameters))
          .toList();
      return switch (filter.operator()) {
        case AND -> "(" + String.join(" AND ", children) + ")";
        case OR -> "(" + String.join(" OR ", children) + ")";
        case NOT -> "(NOT " + children.getFirst() + ")";
        default -> throw invalid("Invalid record filter group");
      };
    }
    requireReadableFilter(access, filter.field());
    Function<Object, Object> converter = converter(model, filter.field());
    List<Object> values = filter.values().stream().map(converter).toList();
    return predicate(
        quote(model.activeCode(filter.field())),
        filter.operator(),
        values,
        parameters
    );
  }

  private String scope(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordSqlParameters parameters
  ) {
    DataScopeCondition scope = access.rowScope();
    if (scope == null || scope.isAllData()) {
      return "1 = 1";
    }
    List<String> alternatives = new ArrayList<>();
    for (QueryDocument.Node expression : scope.getExpressions()) {
      alternatives.add(scopeExpression(model, expression, parameters));
    }
    if (scope.isSelf() || scope.isIncludeSelf()) {
      if (scope.getUserId() != null) {
        alternatives.add(quote(scope.getOwnerField()) + " = " + parameters.add(scope.getUserId()));
      }
    }
    if (Set.of("DEPT", "DEPT_AND_BELOW", "CUSTOM").contains(scope.getScopeType())
        && scope.getDeptIds() != null && !scope.getDeptIds().isEmpty()) {
      alternatives.add(inPredicate(quote(scope.getDeptField()), scope.getDeptIds(), parameters, false));
    }
    return alternatives.isEmpty() ? "1 = 0" : "(" + String.join(" OR ", alternatives) + ")";
  }

  private String scopeExpression(
      final RuntimeRecordModel model,
      final QueryDocument.Node node,
      final RecordSqlParameters parameters
  ) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      List<String> children = node.children().stream()
          .map(child -> scopeExpression(model, child, parameters))
          .toList();
      return switch (node.op()) {
        case "and" -> "(" + String.join(" AND ", children) + ")";
        case "or" -> "(" + String.join(" OR ", children) + ")";
        case "not" -> "(NOT " + children.getFirst() + ")";
        default -> throw invalid("Invalid data-scope expression");
      };
    }
    String field = model.permissionCode(node.field());
    ensureActiveField(model, field);
    RecordFilterOperator operator = scopeOperator(node.op());
    List<Object> values = jsonValues(node.value()).stream()
        .map(activeConverter(model, field))
        .toList();
    return predicate(quote(field), operator, values, parameters);
  }

  private static void rejectScopeFieldMutation(
      final RuntimeRecordModel model,
      final DataScopeCondition scope,
      final Set<String> changedFields
  ) {
    if (scope == null || scope.isAllData() || changedFields.isEmpty()) {
      return;
    }
    Set<String> protectedFields = new LinkedHashSet<>();
    scope.getExpressions().forEach(expression -> collectFields(expression, protectedFields));
    if (scope.isSelf() || scope.isIncludeSelf()) {
      protectedFields.add(scope.getOwnerField());
    }
    if (Set.of("DEPT", "DEPT_AND_BELOW", "CUSTOM").contains(scope.getScopeType())) {
      protectedFields.add(scope.getDeptField());
    }
    if (protectedFields.stream().map(model::permissionCode).anyMatch(changedFields::contains)) {
      throw denied("Fields used by the active data scope cannot be updated");
    }
  }

  private static void collectFields(
      final QueryDocument.Node node,
      final Set<String> fields
  ) {
    if (node.field() != null) {
      fields.add(node.field());
    }
    node.children().forEach(child -> collectFields(child, fields));
  }

  private static RecordFilterOperator scopeOperator(final String operator) {
    return switch (operator) {
      case "equals" -> RecordFilterOperator.EQUALS;
      case "not:equals" -> RecordFilterOperator.NOT_EQUALS;
      case "in" -> RecordFilterOperator.IN;
      case "not:in" -> RecordFilterOperator.NOT_IN;
      case "like" -> RecordFilterOperator.LIKE;
      case "not:like" -> RecordFilterOperator.NOT_LIKE;
      case "than:greater" -> RecordFilterOperator.GREATER_THAN;
      case "than:equal:greater" -> RecordFilterOperator.GREATER_THAN_OR_EQUAL;
      case "than:less" -> RecordFilterOperator.LESS_THAN;
      case "than:equal:less" -> RecordFilterOperator.LESS_THAN_OR_EQUAL;
      case "between" -> RecordFilterOperator.BETWEEN;
      case "not:between" -> RecordFilterOperator.NOT_BETWEEN;
      case "is:null" -> RecordFilterOperator.IS_NULL;
      case "is:not:null" -> RecordFilterOperator.IS_NOT_NULL;
      default -> throw invalid("Unsupported data-scope operator");
    };
  }

  private static String predicate(
      final String field,
      final RecordFilterOperator operator,
      final List<Object> values,
      final RecordSqlParameters parameters
  ) {
    return switch (operator) {
      case EQUALS -> field + " = " + parameters.add(values.getFirst());
      case NOT_EQUALS -> field + " <> " + parameters.add(values.getFirst());
      case IN -> inPredicate(field, values, parameters, false);
      case NOT_IN -> inPredicate(field, values, parameters, true);
      case LIKE -> field + " LIKE " + parameters.add("%" + values.getFirst() + "%");
      case NOT_LIKE -> field + " NOT LIKE " + parameters.add("%" + values.getFirst() + "%");
      case GREATER_THAN -> field + " > " + parameters.add(values.getFirst());
      case LESS_THAN -> field + " < " + parameters.add(values.getFirst());
      case GREATER_THAN_OR_EQUAL -> field + " >= " + parameters.add(values.getFirst());
      case LESS_THAN_OR_EQUAL -> field + " <= " + parameters.add(values.getFirst());
      case BETWEEN -> field + " BETWEEN " + parameters.add(values.get(0))
          + " AND " + parameters.add(values.get(1));
      case NOT_BETWEEN -> field + " NOT BETWEEN " + parameters.add(values.get(0))
          + " AND " + parameters.add(values.get(1));
      case IS_NULL -> field + " IS NULL";
      case IS_NOT_NULL -> field + " IS NOT NULL";
      default -> throw invalid("Unsupported record predicate");
    };
  }

  private static String inPredicate(
      final String field,
      final Collection<?> values,
      final RecordSqlParameters parameters,
      final boolean negated
  ) {
    if (values.isEmpty()) {
      return negated ? "1 = 1" : "1 = 0";
    }
    String placeholders = values.stream().map(parameters::add).collect(Collectors.joining(", "));
    return field + (negated ? " NOT IN (" : " IN (") + placeholders + ")";
  }

  private static boolean scopeAllowsWrite(
      final DataScopeCondition scope,
      final Function<String, Object> values
  ) {
    if (scope == null || scope.isAllData()) {
      return true;
    }
    if (scope.getExpressions().stream()
        .anyMatch(expression -> PermissionExpressionEvaluator.matches(expression, values))) {
      return true;
    }
    boolean self = (scope.isSelf() || scope.isIncludeSelf())
        && values.apply(scope.getOwnerField()) != null
        && values.apply(scope.getOwnerField()).toString().equals(scope.getUserId());
    if (scope.isSelf()) {
      return self;
    }
    boolean department = Set.of("DEPT", "DEPT_AND_BELOW", "CUSTOM")
        .contains(scope.getScopeType())
        && values.apply(scope.getDeptField()) != null
        && scope.getDeptIds().contains(values.apply(scope.getDeptField()).toString());
    return department || scope.isIncludeSelf() && self;
  }

  private List<String> projection(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final List<String> requested
  ) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    result.add("id");
    result.add("record_version");
    Collection<String> candidates = requested == null || requested.isEmpty()
        ? model.requestedFieldCodes()
        : requested;
    for (String field : candidates) {
      ensureField(model, field);
      if (!access.readableFields().contains(field)) {
        throw denied("Record field cannot be projected: " + field);
      }
      result.add(field);
    }
    return List.copyOf(result);
  }

  private static String selectList(
      final RuntimeRecordModel model,
      final List<String> fields
  ) {
    Set<String> names = new LinkedHashSet<>(SYSTEM_TYPES.keySet());
    model.activeSchema().fields().forEach(field -> names.add(field.code()));
    List<String> projections = new ArrayList<>();
    int aliasIndex = 0;
    for (String field : fields) {
      String column = quote(model.activeCode(field));
      String projection = RecordWireValues.projection(model, field, column);
      if (!projection.equals(column)) {
        // PostgreSQL derives a cast's output name from its source column. Calcite may also
        // project the native column for sorting, making ORDER BY ambiguous without an alias.
        // Keep sorting on native types; wire projection is positional and uses distinct names.
        String alias;
        do {
          alias = "sp_wire_" + aliasIndex++;
        } while (!names.add(alias));
        projection += " AS " + quote(alias);
      }
      projections.add(projection);
    }
    return String.join(", ", projections);
  }

  private static List<String> valueFields(final List<String> fields) {
    return fields.stream()
        .filter(field -> !field.equals("id") && !field.equals("record_version"))
        .toList();
  }

  private String orderBy(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final List<RecordSort> requested
  ) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    for (RecordSort sort : requested) {
      ensureField(model, sort.field());
      requireReadableFilter(access, sort.field());
      fields.put(
          sort.field(),
          quote(model.activeCode(sort.field())) + " " + sort.direction().name()
      );
    }
    if (requested.isEmpty()) {
      var ordinal = java.util.List.of("sort", "sort_order", "sequence", "ordinal", "position").stream()
          .filter(code -> model.requestedField(code) != null
              && java.util.Set.of("INT32", "INT64").contains(model.requestedField(code).type().name())
              && access.readableFields().contains(code) && !access.maskedFields().contains(code)).findFirst();
      if (ordinal.isPresent()) fields.put(ordinal.get(), quote(model.activeCode(ordinal.get())) + " ASC");
      else if (access.readableFields().contains("created_at") && !access.maskedFields().contains("created_at"))
        fields.put("created_at", quote("created_at") + " DESC");
    }
    fields.putIfAbsent("id", quote("id") + " ASC");
    return String.join(", ", fields.values());
  }

  private Function<Object, Object> converter(
      final RuntimeRecordModel model,
      final String field
  ) {
    LogicalFieldDefinition definition = fields(model).get(field);
    if (definition != null) {
      return value -> logical(definition, value);
    }
    SystemType type = SYSTEM_TYPES.get(field);
    if (type == null) {
      throw invalid("Unknown record field: " + field);
    }
    return type::convert;
  }

  private Function<Object, Object> activeConverter(
      final RuntimeRecordModel model,
      final String field
  ) {
    LogicalFieldDefinition definition = model.activeSchema().fields().stream()
        .filter(candidate -> candidate.code().equals(field))
        .findFirst()
        .orElse(null);
    if (definition != null) {
      return value -> logical(definition, value);
    }
    SystemType type = SYSTEM_TYPES.get(field);
    if (type == null) {
      throw invalid("Unknown active record field: " + field);
    }
    return type::convert;
  }

  private Object logical(final LogicalFieldDefinition field, final Object value) {
    LogicalValue normalized = valueConverter.normalize(field, value);
    if (normalized.canonicalValue() == null) {
      return null;
    }
    String raw = normalized.canonicalValue();
    return switch (field.type()) {
      case STRING, TEXT -> raw;
      case JSON -> new RecordJsonParameter(raw);
      case BOOLEAN -> Boolean.valueOf(raw);
      case INT32 -> Integer.valueOf(raw);
      case INT64 -> Long.valueOf(raw);
      case DECIMAL -> new BigDecimal(raw);
      case DATE -> Date.valueOf(LocalDate.parse(raw));
      case TIME -> LocalTime.parse(raw);
      case DATETIME -> Timestamp.from(Instant.parse(raw));
      case UUID -> raw;
      case BINARY -> Base64.getUrlDecoder().decode(raw);
    };
  }

  private static List<Object> jsonValues(final JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      node.forEach(value -> values.add(jsonValue(value)));
      return values;
    }
    return List.of(jsonValue(node));
  }

  private static Object jsonValue(final JsonNode value) {
    if (value.isBoolean()) {
      return value.booleanValue();
    }
    if (value.isIntegralNumber()) {
      return value.bigIntegerValue();
    }
    if (value.isFloatingPointNumber()) {
      return value.decimalValue();
    }
    return value.asText();
  }

  private static void validateWriteFields(
      final RecordAccessContext access,
      final Map<String, LogicalFieldDefinition> definitions,
      final Set<String> fields
  ) {
    for (String field : fields) {
      if (!definitions.containsKey(field)) {
        throw invalid("Unknown or server-owned record field: " + field);
      }
      if (!access.writableFields().contains(field)) {
        throw denied("Record field is not writable: " + field);
      }
    }
  }

  private static void requireReadableFilter(
      final RecordAccessContext access,
      final String field
  ) {
    if (!access.readableFields().contains(field) || access.maskedFields().contains(field)) {
      throw denied("Record field cannot be filtered or sorted: " + field);
    }
  }

  private static void ensureField(final RuntimeRecordModel model, final String field) {
    if (!SYSTEM_TYPES.containsKey(field) && model.requestedSchema().fields().stream()
        .noneMatch(definition -> definition.code().equals(field))) {
      throw invalid("Unknown record field: " + field);
    }
  }

  private static void ensureActiveField(final RuntimeRecordModel model, final String field) {
    if (!SYSTEM_TYPES.containsKey(field) && model.activeSchema().fields().stream()
        .noneMatch(definition -> definition.code().equals(field))) {
      throw invalid("Unknown active record field: " + field);
    }
  }

  private static Map<String, LogicalFieldDefinition> fields(final RuntimeRecordModel model) {
    return model.requestedSchema().fields().stream().collect(Collectors.toUnmodifiableMap(
        LogicalFieldDefinition::code,
        Function.identity()
    ));
  }

  private static String table(final RuntimeRecordModel model) {
    String table = model.target().metadataTarget().tableName();
    if (table == null || !table.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
      throw invalid("Physical model contains an invalid table binding");
    }
    return quote(CATALOG) + "." + quote(table);
  }

  private static String quote(final String identifier) {
    if (identifier == null || !identifier.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) {
      throw invalid("Invalid record identifier");
    }
    return '"' + identifier + '"';
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_QUERY_INVALID, message);
  }

  private static DnaRuntimeException denied(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, message);
  }

  private enum SystemType {
    STRING,
    LONG,
    INSTANT;

    Object convert(final Object value) {
      if (value == null) {
        return null;
      }
      return switch (this) {
        case STRING -> value.toString();
        case LONG -> value instanceof Number number
            ? number.longValue() : Long.valueOf(value.toString());
        case INSTANT -> value instanceof Instant instant
            ? Timestamp.from(instant) : Timestamp.from(Instant.parse(value.toString()));
      };
    }
  }
}
