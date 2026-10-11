package org.simplepoint.plugin.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.constants.WorkflowVariableNames;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Binding;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Variable;

/** Validates bounded, provider-neutral form contracts and their typed runtime submissions. */
public final class WorkflowFormContracts {

  private static final Set<String> TYPES = Set.of(
      "string", "number", "boolean", "date", "date-time", "user", "users", "role", "roles");
  private final ObjectMapper mapper;

  /** Uses the application's JSON mapper, without accepting polymorphic Java objects. */
  public WorkflowFormContracts(final ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Reads an immutable snapshot; a null legacy snapshot means the explicit empty v1 contract. */
  public WorkflowFormContract read(final String json) {
    if (json == null || json.isBlank()) {
      return WorkflowFormContract.empty();
    }
    try {
      return normalize(mapper.readValue(json, WorkflowFormContract.class));
    } catch (JsonProcessingException exception) {
      throw invalid("流程表单契约无法读取");
    }
  }

  /** Canonical bounded representation used by persistence and version checksums. */
  public String write(final WorkflowFormContract contract) {
    try {
      String json = mapper.writeValueAsString(normalize(contract));
      if (json.length() > 64 * 1024) {
        throw invalid("流程表单契约不能超过 64 KB");
      }
      return json;
    } catch (JsonProcessingException exception) {
      throw invalid("流程表单契约无法序列化");
    }
  }

  /** Rejects ambiguous names, undeclared mappings and unsupported future contract versions. */
  public WorkflowFormContract normalize(final WorkflowFormContract contract) {
    if (contract == null) {
      return WorkflowFormContract.empty();
    }
    if (contract.schemaVersion() != 1) {
      throw invalid("不支持的流程表单契约版本");
    }
    List<Variable> variables = contract.variables() == null ? List.of() : contract.variables();
    if (variables.size() > 100) {
      throw invalid("流程变量不能超过 100 个");
    }
    Set<String> names = new LinkedHashSet<>();
    List<Variable> normalized = new ArrayList<>();
    for (Variable variable : variables) {
      if (variable == null || variable.name() == null
          || !variable.name().matches("[A-Za-z_][A-Za-z0-9_]{0,79}")
          || WorkflowVariableNames.isReserved(variable.name()) || !names.add(variable.name())) {
        throw invalid("流程变量名无效、重复或使用了系统保留名");
      }
      String label = text(variable.label(), 160, "变量名称");
      if (variable.type() == null || !TYPES.contains(variable.type())) {
        throw invalid("不支持的流程变量类型");
      }
      String scope = variable.scope() == null ? "both" : variable.scope();
      if (!Set.of("start", "task", "both").contains(scope)) {
        throw invalid("流程变量使用阶段无效");
      }
      validateValue(variable, variable.defaultValue());
      normalized.add(new Variable(variable.name(), label, variable.type(), scope,
          variable.required(), variable.defaultValue()));
    }
    Map<String, Binding> tasks = new java.util.TreeMap<>();
    if (contract.taskBindings() != null) {
      if (contract.taskBindings().size() > 100) {
        throw invalid("任务表单绑定不能超过 100 个");
      }
      contract.taskBindings().forEach((key, value) -> {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
          throw invalid("任务表单绑定的元素标识无效");
        }
        tasks.put(key, binding(value, names));
      });
    }
    return new WorkflowFormContract(1, List.copyOf(normalized),
        contract.startBinding() == null ? null : binding(contract.startBinding(), names), tasks);
  }

  /** A bound task must exist in the exact BPMN snapshot being saved or published. */
  public void validateTaskBindings(final WorkflowFormContract contract, final Set<String> userTaskIds) {
    if (!userTaskIds.containsAll(contract.taskBindings().keySet())) {
      throw invalid("表单绑定指向了不存在的用户任务，请调整绑定后重试");
    }
  }

  /** Declared forms are stage-specific; legacy JSON forms retain only safe user-owned names. */
  public Map<String, Object> project(
      final WorkflowFormContract contract, final Map<String, Object> source, final String stage
  ) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (contract.variables().isEmpty()) {
      source.forEach((name, value) -> {
        if (name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,79}") && !WorkflowVariableNames.isReserved(name)) {
          values.put(name, value);
        }
      });
      return values;
    }
    for (Variable variable : contract.variables()) {
      if (editable(variable, stage)) {
        Object value = source.containsKey(variable.name())
            ? source.get(variable.name()) : variable.defaultValue();
        if (value != null) {
          values.put(variable.name(), value);
        }
      }
    }
    return values;
  }

  /** Checks required fields against merged runtime values, while returning only the submitted update. */
  public Map<String, Object> submission(
      final WorkflowFormContract contract,
      final Map<String, Object> submitted,
      final Map<String, Object> current,
      final String stage
  ) {
    // Compatibility for existing advanced-only processes without declarations.
    if (contract.variables().isEmpty()) {
      return submitted;
    }
    Map<String, Variable> allowed = new LinkedHashMap<>();
    contract.variables().stream().filter(variable -> editable(variable, stage))
        .forEach(variable -> allowed.put(variable.name(), variable));
    submitted.forEach((name, value) -> {
      Variable variable = allowed.get(name);
      if (variable == null) {
        throw invalid("当前阶段不允许填写变量: " + name);
      }
      validateValue(variable, value);
    });
    Map<String, Object> result = new LinkedHashMap<>(submitted);
    for (Variable variable : allowed.values()) {
      Object value = result.containsKey(variable.name()) ? result.get(variable.name())
          : current.containsKey(variable.name()) ? current.get(variable.name()) : variable.defaultValue();
      if (variable.required() && (value == null || value instanceof String text && text.isBlank()
          || value instanceof List<?> list && list.isEmpty())) {
        throw invalid("请填写流程变量: " + variable.label());
      }
      if (!current.containsKey(variable.name()) && !result.containsKey(variable.name()) && value != null) {
        result.put(variable.name(), value);
      }
    }
    return result;
  }

  private static boolean editable(final Variable variable, final String stage) {
    return "both".equals(variable.scope()) || stage.equals(variable.scope());
  }

  private static Binding binding(final Binding binding, final Set<String> names) {
    if (binding == null || binding.version() < 1) {
      throw invalid("表单绑定必须指定不可变版本");
    }
    String provider = text(binding.provider(), 80, "表单提供方");
    if (!provider.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
      throw invalid("表单提供方标识无效");
    }
    String id = text(binding.formId(), 160, "表单标识");
    Map<String, String> mappings = new java.util.TreeMap<>();
    if (binding.variableMappings() != null) {
      if (binding.variableMappings().size() > 100) {
        throw invalid("表单变量映射不能超过 100 个");
      }
      binding.variableMappings().forEach((field, variable) -> {
        text(field, 160, "表单字段");
        if (!names.contains(variable)) {
          throw invalid("表单映射必须指向已声明的流程变量");
        }
        mappings.put(field, variable);
      });
    }
    return new Binding(provider, id, binding.version(), mappings);
  }

  private static void validateValue(final Variable variable, final Object value) {
    if (value == null) {
      return;
    }
    boolean valid = switch (variable.type()) {
      case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
      case "boolean" -> value instanceof Boolean;
      case "users", "roles" -> value instanceof List<?> list && list.size() <= 50
          && list.stream().allMatch(item -> item instanceof String text
              && ("roles".equals(variable.type()) ? roleValue(text) : directoryValue(text)))
          && new LinkedHashSet<>(list).size() == list.size();
      case "user" -> value instanceof String text && directoryValue(text);
      case "role" -> value instanceof String text && roleValue(text);
      case "date", "date-time" -> temporal(value, variable.type());
      case "string" -> value instanceof String text && text.length() <= 4000;
      default -> false;
    };
    if (!valid) {
      throw invalid("流程变量类型或长度无效: " + variable.label());
    }
  }

  private static boolean directoryValue(final String value) {
    return value.matches("[A-Za-z0-9_][A-Za-z0-9_.:@-]{0,99}");
  }

  private static boolean roleValue(final String value) {
    return !value.isBlank() && value.length() <= 100 && value.equals(value.trim())
        && !value.contains(",") && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static boolean temporal(final Object value, final String type) {
    if (!(value instanceof String text) || text.length() > 40) {
      return false;
    }
    try {
      if ("date".equals(type)) {
        return LocalDate.parse(text).toString().equals(text);
      }
      Instant.parse(text);
      return true;
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private static String text(final String value, final int limit, final String label) {
    if (value == null || value.isBlank() || value.length() > limit || !value.equals(value.trim())) {
      throw invalid(label + "不能为空、包含首尾空格或超过长度限制");
    }
    return value;
  }

  private static WorkflowException invalid(final String message) {
    return new WorkflowException(WorkflowException.Code.VALIDATION, message);
  }
}
