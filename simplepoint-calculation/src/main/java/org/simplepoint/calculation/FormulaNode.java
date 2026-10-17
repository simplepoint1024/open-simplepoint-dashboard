package org.simplepoint.calculation;

import java.util.List;
import java.util.Objects;

/** Portable immutable syntax tree. Number literals remain lossless decimal text. */
public record FormulaNode(Kind kind, String text, List<FormulaNode> children) {
  /** Portable node categories shared with the browser editor. */
  public enum Kind {
    NUMBER,
    STRING,
    BOOLEAN,
    NULL,
    FIELD,
    CALL,
    UNARY,
    BINARY
  }

  /** Copies children and validates local shape and size bounds. */
  public FormulaNode {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(text, "text");
    children = List.copyOf(children);
    int size = children.size();
    if (text.length() > 8192
        || size > 256
        || kind == Kind.UNARY && size != 1
        || kind == Kind.BINARY && size != 2
        || kind != Kind.CALL && kind != Kind.UNARY && kind != Kind.BINARY && size != 0) {
      throw new FormulaException("INVALID_AST");
    }
  }

  /** Constructs an immutable leaf. */
  public static FormulaNode leaf(Kind kind, String text) {
    return new FormulaNode(kind, text, List.of());
  }
}
