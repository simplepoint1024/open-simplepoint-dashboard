import {expressionConstant, expressionVariable} from './model.ts';
import {quoteExpressionString} from './literals.ts';

export type ValueExpressionMode = 'literal' | 'variable' | 'advanced';

/** A selected real identity containing template markers is a literal, never a variable assignment. */
export function fixedExpressionValue(value?: string): string | undefined {
  return value && (value.includes('${') || value.includes('#{'))
    ? '${' + quoteExpressionString(value) + '}' : value;
}

/** Presentation only: no inferred mode, preview or conversion may write the saved value. */
export function valueExpressionPresentation(value: string, names: ReadonlySet<string>) {
  const expression = /^(?:\$|#)\{/.test(value.trim());
  const variable = expression ? expressionVariable(value) : undefined;
  const declaredVariable = variable != null && names.has(variable);
  const constant = expression ? expressionConstant(value) : undefined;
  const numericLiteral = constant != null
    && /^\$\{\s*-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\s*\}$/.test(value.trim());
  const simple = !expression || variable != null || constant != null;
  const defaultMode: ValueExpressionMode = declaredVariable ? 'variable' : expression ? 'advanced' : 'literal';
  let advancedValue = value;
  if (value && !expression) {
    // An invalid draft remains visible and repairable; presentation must not throw during typing.
    try {advancedValue = '${' + quoteExpressionString(value) + '}';} catch { /* keep the original */ }
  }
  return {expression, variable, declaredVariable, constant, numericLiteral, simple, defaultMode,
    advancedValue,
    literalValue: expression ? constant : value || undefined};
}

export function valueExpressionModeAllowed(mode: ValueExpressionMode, simple: boolean, variableCount: number) {
  return mode === 'advanced' || simple && (mode !== 'variable' || variableCount > 0);
}
