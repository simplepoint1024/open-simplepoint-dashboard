package org.simplepoint.core.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.permission.antlr.*;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.core.query.QueryDocument.Node;
import org.simplepoint.core.query.QueryValues;

/** Bounded, non-executable permission language. Produces the platform's scalar query AST. */
public final class PermissionExpression {
  private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  public static final Set<String> CONTEXTS = Set.of("user.id", "user.organizationId", "user.organizationIds", "tenant.id");
  private static final Map<String, Node> CACHE = new LinkedHashMap<>(256, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Node> entry) { return size() > 256; }
  };
  private PermissionExpression() {}

  public static Node parse(String expression) {
    if (expression == null || expression.isBlank() || expression.length() > QueryDocument.MAX_LENGTH)
      throw invalid("表达式不能为空且不能超过 8192 字符");
    synchronized (CACHE) { var cached = CACHE.get(expression); if (cached != null) return cached; }
    var errors = new BaseErrorListener() {
      @Override public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int column,
          String message, RecognitionException error) {
        throw invalid("第 " + line + " 行第 " + (column + 1) + " 列: 表达式语法无效");
      }
    };
    var lexer = new PermissionRuleLexer(CharStreams.fromString(expression));
    lexer.removeErrorListeners(); lexer.addErrorListener(errors);
    var tokens = new CommonTokenStream(lexer); tokens.fill();
    if (tokens.size() > 1024) throw invalid("表达式复杂度超限");
    int depth = 0; int negations = 0;
    for (Token token : tokens.getTokens()) {
      if (token.getType() == PermissionRuleLexer.NOT && ++negations > 32) throw invalid("表达式复杂度超限");
      if ("(".equals(token.getText()) && ++depth > 16) throw invalid("表达式括号层级超限");
      if (")".equals(token.getText())) depth--;
    }
    var parser = new PermissionRuleParser(tokens);
    parser.removeErrorListeners(); parser.addErrorListener(errors);
    Node node = new Builder().visit(parser.rule_());
    budget(node, 1, new int[1]);
    synchronized (CACHE) { CACHE.put(expression, node); }
    return node;
  }

  public static Node validate(String expression, Map<String, PermissionModelDescriptor.Field> fields) {
    Node node = parse(expression); validate(node, fields); return node;
  }

  public static void validate(Node node, Map<String, PermissionModelDescriptor.Field> fields) {
    budget(node, 1, new int[1]);
    if (Set.of("and", "or", "not").contains(node.op())) {
      node.children().forEach(child -> validate(child, fields)); return;
    }
    var field = fields.get(node.field());
    if (field == null || !field.operators().contains(node.op())) throw invalid("字段或操作未开放: " + node.field());
    if (node.op().startsWith("is:")) return;
    boolean multiple = Set.of("in", "not:in").contains(node.op());
    JsonNode value = node.value();
    if (value == null || value.isNull()) throw invalid("请使用 IS NULL 判断空值");
    if (multiple != value.isArray() && !(multiple && reference(value, "user.organizationIds")))
      throw invalid("IN 需要列表，其余比较需要单值");
    if (value.isArray()) {
      if (value.isEmpty() || value.size() > 100) throw invalid("列表需要 1 至 100 个值");
      value.forEach(item -> {
        if (item.isObject()) throw invalid("列表内请使用固定值；组织列表变量请直接用于 IN");
        validateValue(item, field, false);
      });
    } else validateValue(value, field, multiple);
  }

  private static void validateValue(JsonNode value, PermissionModelDescriptor.Field field, boolean multiple) {
    if (value.isObject()) {
      String context = value.path("context").asText();
      if (value.size() != 1 || !CONTEXTS.contains(context)
          || !field.type().equals(String.class.getName())
          || context.equals("user.organizationIds") != multiple) throw invalid("不支持的上下文变量或字段类型");
      return;
    }
    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) throw invalid("只支持文本、数值和布尔值");
    if (!field.values().isEmpty()) {
      if (!value.isTextual() || !field.values().contains(value.textValue())) throw invalid("不支持的枚举值");
      return;
    }
    Class<?> type = scalarType(field.type());
    if (type == String.class && !value.isTextual() || type == Boolean.class && !value.isBoolean()
        || Number.class.isAssignableFrom(type) && !value.isNumber() && !value.isTextual()) throw invalid("字段值类型不匹配");
    try { QueryValues.convert(type, value.asText()); }
    catch (RuntimeException error) { throw invalid("字段值格式不正确"); }
  }

  private static Class<?> scalarType(String name) {
    return switch (name) {
      case "java.lang.String" -> String.class;
      case "boolean", "java.lang.Boolean" -> Boolean.class;
      case "int", "java.lang.Integer" -> Integer.class;
      case "long", "java.lang.Long" -> Long.class;
      case "short", "java.lang.Short" -> Short.class;
      case "byte", "java.lang.Byte" -> Byte.class;
      case "double", "java.lang.Double" -> Double.class;
      case "float", "java.lang.Float" -> Float.class;
      case "java.math.BigDecimal" -> java.math.BigDecimal.class;
      case "java.math.BigInteger" -> java.math.BigInteger.class;
      case "java.time.Instant" -> java.time.Instant.class;
      case "java.time.LocalDate" -> java.time.LocalDate.class;
      case "java.time.LocalTime" -> java.time.LocalTime.class;
      case "java.time.OffsetTime" -> java.time.OffsetTime.class;
      case "java.time.ZonedDateTime" -> java.time.ZonedDateTime.class;
      case "java.time.LocalDateTime" -> java.time.LocalDateTime.class;
      case "java.time.OffsetDateTime" -> java.time.OffsetDateTime.class;
      case "java.util.UUID" -> java.util.UUID.class;
      default -> throw invalid("不支持的字段类型");
    };
  }

  public static Node resolve(Node node, AuthorizationContext context) {
    return new Node(node.op(), node.field(), resolveValue(node.value(), context),
        node.children().stream().map(child -> resolve(child, context)).toList());
  }

  private static JsonNode resolveValue(JsonNode value, AuthorizationContext context) {
    if (value == null) return null;
    if (value.isArray()) {
      var array = JsonNodeFactory.instance.arrayNode(); value.forEach(item -> array.add(resolveValue(item, context))); return array;
    }
    if (!value.isObject()) return value;
    String key = value.path("context").asText();
    if (!CONTEXTS.contains(key) || context == null) throw invalid("权限上下文不可用");
    return switch (key) {
      case "user.id" -> JSON.valueToTree(context.getUserId());
      case "tenant.id" -> JSON.valueToTree(context.getAttribute("X-Tenant-Id"));
      case "user.organizationId" -> JSON.valueToTree(context.getAttribute("X-Org-Dept-Id"));
      // Context uses verified tenant membership; never client-provided organization headers.
      case "user.organizationIds" -> {
        String department = context.getAttribute("X-Org-Dept-Id");
        yield JSON.valueToTree(department == null ? List.of() : List.of(department));
      }
      default -> throw invalid("上下文变量无效");
    };
  }

  public static String format(Node node) {
    budget(node, 1, new int[1]);
    return render(node);
  }

  private static String render(Node node) {
    if (Set.of("and", "or").contains(node.op())) return "(" + String.join(" " + node.op().toUpperCase(Locale.ROOT) + " ",
        node.children().stream().map(PermissionExpression::render).toList()) + ")";
    if (node.op().equals("not")) return "NOT (" + render(node.children().getFirst()) + ")";
    String op = switch (node.op()) {
      case "equals" -> "="; case "not:equals" -> "!=";
      case "than:greater" -> ">"; case "than:equal:greater" -> ">=";
      case "than:less" -> "<"; case "than:equal:less" -> "<=";
      case "in" -> "IN"; case "not:in" -> "NOT IN";
      case "like" -> "CONTAINS"; case "not:like" -> "NOT CONTAINS";
      case "is:null" -> "IS NULL"; case "is:not:null" -> "IS NOT NULL";
      default -> throw invalid("不支持的操作符");
    };
    return node.field() + " " + op + (node.op().startsWith("is:") ? "" : " " + renderValue(node.value()));
  }

  private static String renderValue(JsonNode value) {
    if (value == null) throw invalid("缺少比较值");
    if (value.isObject()) {
      String context = value.path("context").asText();
      if (value.size() != 1 || !CONTEXTS.contains(context)) throw invalid("上下文变量无效");
      return "$" + context;
    }
    if (value.isArray()) {
      var items = new ArrayList<String>(); value.forEach(item -> items.add(renderValue(item)));
      return "[" + String.join(", ", items) + "]";
    }
    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) throw invalid("比较值无效");
    return value.toString();
  }

  private static boolean reference(JsonNode value, String name) {
    return value != null && value.isObject() && name.equals(value.path("context").asText());
  }

  private static void budget(Node node, int depth, int[] count) {
    if (node == null || node.op() == null || node.children() == null || ++count[0] > QueryDocument.MAX_NODES
        || depth > QueryDocument.MAX_DEPTH) throw invalid("表达式最多 32 个条件节点、4 层分组");
    if (Set.of("and", "or", "not").contains(node.op())) {
      if (node.field() != null || node.value() != null && !node.value().isNull() || node.children().isEmpty()
          || node.op().equals("not") && node.children().size() != 1) throw invalid("分组结构无效");
      node.children().forEach(child -> budget(child, depth + 1, count));
    } else if (node.value() != null && (node.value().isArray() && node.value().size() > 100
        || node.value().toString().length() > QueryDocument.MAX_LENGTH)) throw invalid("表达式值超出长度限制");
    else if (node.field() == null || !node.field().matches("[A-Za-z_][A-Za-z0-9_]*")
        || !node.children().isEmpty()) throw invalid("字段结构无效");
  }

  private static InvalidPermissionPolicyException invalid(String message) { return new InvalidPermissionPolicyException(message); }

  private static final class Builder extends PermissionRuleBaseVisitor<Node> {
    @Override public Node visitRule(PermissionRuleParser.RuleContext ctx) { return visit(ctx.disjunction()); }
    @Override public Node visitDisjunction(PermissionRuleParser.DisjunctionContext ctx) {
      var children = ctx.conjunction().stream().map(this::visit).toList();
      return children.size() == 1 ? children.getFirst() : new Node("or", null, null, children);
    }
    @Override public Node visitConjunction(PermissionRuleParser.ConjunctionContext ctx) {
      var children = ctx.unary().stream().map(this::visit).toList();
      return children.size() == 1 ? children.getFirst() : new Node("and", null, null, children);
    }
    @Override public Node visitUnary(PermissionRuleParser.UnaryContext ctx) {
      if (ctx.NOT() != null) return new Node("not", null, null, List.of(visit(ctx.unary())));
      return ctx.disjunction() != null ? visit(ctx.disjunction()) : visit(ctx.comparison());
    }
    @Override public Node visitComparison(PermissionRuleParser.ComparisonContext ctx) {
      String op;
      if (ctx.IS() != null) op = ctx.NOT() == null ? "is:null" : "is:not:null";
      else op = switch (ctx.operator().getText().toUpperCase(Locale.ROOT)) {
        case "=", "==" -> "equals"; case "!=", "<>" -> "not:equals";
        case ">" -> "than:greater"; case ">=" -> "than:equal:greater";
        case "<" -> "than:less"; case "<=" -> "than:equal:less";
        case "IN" -> "in"; case "NOTIN", "!IN" -> "not:in";
        case "CONTAINS" -> "like"; case "NOTCONTAINS", "!CONTAINS" -> "not:like";
        default -> throw invalid("操作符无效");
      };
      JsonNode value = null;
      if (ctx.value() != null) {
        var scalars = ctx.value().scalar();
        if (ctx.value().getText().startsWith("[")) {
          var array = JsonNodeFactory.instance.arrayNode(); scalars.forEach(item -> array.add(scalar(item))); value = array;
        } else value = scalar(scalars.getFirst());
      }
      return new Node(op, ctx.IDENT().getText(), value, List.of());
    }
    private JsonNode scalar(PermissionRuleParser.ScalarContext ctx) {
      String text = ctx.getText();
      if (ctx.CONTEXT() != null) return JsonNodeFactory.instance.objectNode().put("context", text.substring(1));
      try { return JSON.readTree(text); } catch (java.io.IOException error) { throw invalid("比较值无效"); }
    }
  }
}
