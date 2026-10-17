package org.simplepoint.calculation;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.simplepoint.calculation.grammar.CalculationBaseVisitor;
import org.simplepoint.calculation.grammar.CalculationLexer;
import org.simplepoint.calculation.grammar.CalculationParser;

/** ANTLR is the only text parser; serialization produces a canonical, reparsable formula. */
public final class FormulaSyntax {
  private FormulaSyntax() {}

  /** Parses bounded DSL text with precise syntax diagnostics. */
  public static FormulaNode parse(String expression) {
    if (expression == null || expression.isBlank() || expression.length() > 8192) {
      throw new FormulaException("EXPRESSION_SIZE");
    }
    var errors =
        new BaseErrorListener() {
          @Override
          public void syntaxError(
              Recognizer<?, ?> recognizer,
              Object symbol,
              int line,
              int position,
              String message,
              RecognitionException cause) {
            throw new FormulaException("SYNTAX", line, position);
          }
        };
    var lexer = new CalculationLexer(CharStreams.fromString(expression));
    lexer.removeErrorListeners();
    lexer.addErrorListener(errors);
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    if (tokens.size() > 2048) {
      throw new FormulaException("EXPRESSION_SIZE");
    }
    int nesting = 0;
    for (var token : tokens.getTokens()) {
      if (token.getType() == CalculationLexer.LP && ++nesting > 64) {
        throw new FormulaException("EXPRESSION_DEPTH");
      }
      if (token.getType() == CalculationLexer.RP) {
        nesting--;
      }
    }
    var parser = new CalculationParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(errors);
    FormulaNode result = new Builder().visit(parser.formula());
    validateSize(result);
    return result;
  }

  /** Rejects excessive depth or node counts, including external visual trees. */
  public static void validateSize(FormulaNode root) {
    record Entry(FormulaNode node, int depth) {}

    var pending = new ArrayDeque<Entry>();
    pending.push(new Entry(root, 1));
    int count = 0;
    while (!pending.isEmpty()) {
      var entry = pending.pop();
      if (++count > 1024 || entry.depth() > 64) {
        throw new FormulaException("EXPRESSION_DEPTH");
      }
      entry.node().children().forEach(child -> pending.push(new Entry(child, entry.depth() + 1)));
    }
  }

  /** Collects the field identities referenced by an expression. */
  public static Set<String> references(FormulaNode root) {
    validateSize(root);
    var result = new LinkedHashSet<String>();
    var pending = new ArrayDeque<FormulaNode>();
    pending.push(root);
    while (!pending.isEmpty()) {
      var node = pending.pop();
      if (node.kind() == FormulaNode.Kind.FIELD) {
        result.add(node.text());
      }
      node.children().forEach(pending::push);
    }
    return Set.copyOf(result);
  }

  /** Serializes a tree as a canonical reparsable expression. */
  public static String print(FormulaNode root) {
    validateSize(root);
    return "=" + render(root);
  }

  private static String render(FormulaNode node) {
    return switch (node.kind()) {
      case STRING -> "\"" + node.text().replace("\"", "\"\"") + "\"";
      case FIELD -> "[" + node.text().replace("]", "]]") + "]";
      case NUMBER, BOOLEAN, NULL -> node.text();
      case CALL ->
          node.text()
              + "("
              + String.join(",", node.children().stream().map(FormulaSyntax::render).toList())
              + ")";
      case UNARY ->
          node.text().equals("%")
              ? "(" + render(node.children().getFirst()) + ")%"
              : node.text() + "(" + render(node.children().getFirst()) + ")";
      case BINARY ->
          "("
              + render(node.children().getFirst())
              + node.text()
              + render(node.children().get(1))
              + ")";
    };
  }

  private static final class Builder extends CalculationBaseVisitor<FormulaNode> {
    private FormulaNode chain(ParserRuleContext context) {
      FormulaNode value = visit(context.getChild(0));
      for (int index = 1; index < context.getChildCount(); index += 2) {
        value =
            new FormulaNode(
                FormulaNode.Kind.BINARY,
                context.getChild(index).getText(),
                List.of(value, visit(context.getChild(index + 1))));
      }
      return value;
    }

    @Override
    public FormulaNode visitFormula(CalculationParser.FormulaContext ctx) {
      return visit(ctx.comparison());
    }

    @Override
    public FormulaNode visitComparison(CalculationParser.ComparisonContext ctx) {
      return chain(ctx);
    }

    @Override
    public FormulaNode visitConcatenation(CalculationParser.ConcatenationContext ctx) {
      return chain(ctx);
    }

    @Override
    public FormulaNode visitAddition(CalculationParser.AdditionContext ctx) {
      return chain(ctx);
    }

    @Override
    public FormulaNode visitMultiplication(CalculationParser.MultiplicationContext ctx) {
      return chain(ctx);
    }

    @Override
    public FormulaNode visitPower(CalculationParser.PowerContext ctx) {
      return chain(ctx);
    }

    @Override
    public FormulaNode visitPercentage(CalculationParser.PercentageContext ctx) {
      FormulaNode value = visit(ctx.unary());
      for (int index = 0; index < ctx.PERCENT().size(); index++) {
        value = new FormulaNode(FormulaNode.Kind.UNARY, "%", List.of(value));
      }
      return value;
    }

    @Override
    public FormulaNode visitUnary(CalculationParser.UnaryContext ctx) {
      FormulaNode value = visit(ctx.atom());
      for (int index = ctx.getChildCount() - 2; index >= 0; index--) {
        value =
            new FormulaNode(FormulaNode.Kind.UNARY, ctx.getChild(index).getText(), List.of(value));
      }
      return value;
    }

    @Override
    public FormulaNode visitAtom(CalculationParser.AtomContext ctx) {
      if (ctx.NUMBER() != null) {
        return FormulaNode.leaf(FormulaNode.Kind.NUMBER, ctx.NUMBER().getText());
      }
      if (ctx.STRING() != null) {
        String text = ctx.STRING().getText();
        return FormulaNode.leaf(
            FormulaNode.Kind.STRING, text.substring(1, text.length() - 1).replace("\"\"", "\""));
      }
      if (ctx.FIELD() != null) {
        String text = ctx.FIELD().getText();
        return FormulaNode.leaf(
            FormulaNode.Kind.FIELD, text.substring(1, text.length() - 1).replace("]]", "]"));
      }
      if (ctx.TRUE() != null || ctx.FALSE() != null) {
        return FormulaNode.leaf(FormulaNode.Kind.BOOLEAN, ctx.getText().toUpperCase(Locale.ROOT));
      }
      if (ctx.NULL() != null) {
        return FormulaNode.leaf(FormulaNode.Kind.NULL, "NULL");
      }
      if (ctx.IDENTIFIER() != null) {
        return new FormulaNode(
            FormulaNode.Kind.CALL,
            ctx.IDENTIFIER().getText().toUpperCase(Locale.ROOT),
            ctx.comparison().stream().map(this::visit).toList());
      }
      return visit(ctx.comparison(0));
    }
  }
}
