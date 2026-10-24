/** ANTLR columns count Unicode code points; DOM text selections use UTF-16 offsets. */
export function diagnosticSelection(source: string, line: number, column: number): readonly [number, number] {
  const lines = source.split('\n'), row = Math.max(0, Math.min(lines.length - 1, line - 1));
  const points = Array.from(lines[row]), offset = lines.slice(0, row).reduce((sum, text) => sum + text.length + 1, 0);
  const character = Math.max(0, Math.min(points.length, column));
  const start = offset + points.slice(0, character).join('').length;
  return [start, start + (points[character]?.length ?? 0)];
}
