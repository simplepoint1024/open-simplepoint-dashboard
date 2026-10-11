import {format, type SqlLanguage} from 'sql-formatter';

const dialectMatchers: ReadonlyArray<readonly [readonly string[], SqlLanguage]> = [
  [['postgres', 'cockroach'], 'postgresql'],
  [['mariadb'], 'mariadb'],
  [['mysql'], 'mysql'],
  [['sql server', 'microsoft'], 'transactsql'],
  [['oracle'], 'plsql'],
  [['sqlite'], 'sqlite'],
  [['duckdb'], 'duckdb'],
  [['clickhouse'], 'clickhouse'],
  [['snowflake'], 'snowflake'],
  [['redshift'], 'redshift'],
  [['singlestore'], 'singlestoredb'],
  [['tidb'], 'tidb'],
  [['trino', 'presto'], 'trino'],
  [['spark'], 'spark'],
  [['hive'], 'hive'],
  [['db2 for i', 'db2/i'], 'db2i'],
  [['db2'], 'db2'],
];

export const sqlFormatterLanguageFor = (databaseProductName?: string): SqlLanguage => {
  const product = databaseProductName?.trim().toLowerCase() ?? '';
  return dialectMatchers.find(([markers]) => markers.some(marker => product.includes(marker)))?.[1] ?? 'sql';
};

/**
 * Format SQL entirely in the browser. The selected JDBC product determines the
 * dialect; unknown products use the formatter's conservative common SQL mode.
 */
export const formatSqlText = (sql: string, databaseProductName?: string): string => {
  if (!sql.trim() || /^\s*FLUSH\s+CACHE\s*;?\s*$/iu.test(sql)) return sql;
  return format(sql, {
    language: sqlFormatterLanguageFor(databaseProductName),
    tabWidth: 2,
    useTabs: false,
    keywordCase: 'upper',
    identifierCase: 'preserve',
    dataTypeCase: 'upper',
    functionCase: 'upper',
    indentStyle: 'standard',
    logicalOperatorNewline: 'before',
    expressionWidth: 100,
    linesBetweenQueries: 1,
    denseOperators: false,
    newlineBeforeSemicolon: false,
  });
};
