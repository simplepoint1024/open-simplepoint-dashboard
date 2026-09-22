import type {LocalFields, LocalField} from '@simplepoint/components/DataTable';

type Column = {name: string; typeName?: string | null; jdbcType?: number | null};

function scalarType(column: Column): LocalField['type'] | undefined {
  const name = column.typeName?.trim().toUpperCase().replace(/\(.*\)/, '') ?? '';
  const code = column.jdbcType;
  if (code === 16 || code === -7 || /^(BOOL|BOOLEAN|BIT)$/.test(name)) return 'boolean';
  if ([-6, 5, 4, -5].includes(code ?? NaN) || /^(TINYINT|SMALLINT|INT|INTEGER|BIGINT|SERIAL|BIGSERIAL)$/.test(name)) return 'integer';
  if ([2, 3, 6, 7, 8].includes(code ?? NaN) || /^(NUMERIC|DECIMAL|REAL|FLOAT|DOUBLE|DOUBLE PRECISION)$/.test(name)) return 'number';
  if (code === 91 || name === 'DATE') return 'date';
  if (code === 2014 || /^(TIMESTAMPTZ|TIMESTAMP WITH TIME ZONE)$/.test(name)) return 'date-time';
  if (code === 93 || /^(TIMESTAMP|DATETIME|TIMESTAMP WITHOUT TIME ZONE)$/.test(name)) return 'local-date-time';
  if ([1, 12, -1, -15, -9, -16, 2005, 2011, 92, 2013].includes(code ?? NaN)
    || /^(CHAR|VARCHAR|TEXT|NVARCHAR|NCHAR|LONGVARCHAR|CLOB|NCLOB|UUID|TIME|TIMETZ)$/.test(name)) return 'string';
  // Binary, object and array cells are not scalar query fields; never infer from one sample row.
  return undefined;
}

export function sqlQueryFields(columns: readonly Column[]): LocalFields {
  const fields: LocalFields = {};
  columns.forEach((column, index) => {
    const type = scalarType(column);
    if (!type) return;
    fields[`column${index}`] = {type, path: ['values', index], columnKey: `${column.name}-${index}`,
      label: column.typeName ? `${column.name} (${column.typeName})` : column.name};
  });
  return fields;
}
