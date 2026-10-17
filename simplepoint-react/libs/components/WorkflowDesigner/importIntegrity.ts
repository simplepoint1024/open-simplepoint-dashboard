import type {TranslateFn} from '@simplepoint/shared/i18n/translator';

export type WorkflowImportState = 'loading' | 'ready' | 'blocked';
export type ImportDiagnostic = {kind: 'reference' | 'unsupported'; elementId?: string; value?: string};
export type ImportProblem = {failed: boolean; count: number; diagnostics: ImportDiagnostic[]};

const record = (value: unknown): Record<string, unknown> =>
  value != null && typeof value === 'object' ? value as Record<string, unknown> : {};
const text = (value: unknown): string | undefined => typeof value === 'string' ? value.slice(0, 256) : undefined;

/** Moddle removes unresolved references and may skip unrecognized elements. No warning is
 * silently accepted: the partial canvas is a preview, never a replacement for the source XML.
 * Keep only bounded plain-text diagnostics, not cyclic moddle objects or internal stack traces. */
export function importProblem(warnings: readonly unknown[] = [], failed = false): ImportProblem | undefined {
  if (!failed && !warnings.length) return undefined;
  return {failed, count: warnings.length, diagnostics: warnings.slice(0, 20).map(warning => {
    const item = record(warning), context = record(item.context);
    return {
      kind: text(item.message)?.startsWith('unresolved reference <') ? 'reference' : 'unsupported',
      elementId: text(record(item.element ?? context.element).id),
      value: text(item.value),
    };
  })};
}

export function importProblemMessage(problem: ImportProblem, t: TranslateFn): string {
  return problem.failed
    ? t('workflow.import.failed', '流程图无法完整加载，原始内容已保留，不能保存或发布')
    : t('workflow.import.incomplete', '流程图存在 {count} 项导入问题，当前仅供查看，不能保存或发布', {count: problem.count});
}

export function importDiagnosticMessage(diagnostic: ImportDiagnostic, t: TranslateFn): string {
  return diagnostic.kind === 'reference'
    ? t('workflow.import.reference', '元素 {element} 引用的对象 {value} 无法解析',
      {element: diagnostic.elementId ?? '—', value: diagnostic.value ?? '—'})
    : t('workflow.import.unsupported', '部分内容无法完整解析或显示，请检查原始 BPMN 文件');
}
