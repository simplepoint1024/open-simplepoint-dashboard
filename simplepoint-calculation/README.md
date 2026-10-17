# Standalone field calculation

`simplepoint-calculation` is a Java 21 library. Its only runtime dependency is
ANTLR 4.13.2 runtime. It has no Spring, HTTP, persistence, tenant or platform dependency.
The owning application is responsible for authorization, snapshots, persistence and
conversion of calculated values to business field types.

## Use

```java
var engine = new FormulaEngine();
var formula = engine.compile("=ROUND([price]*[quantity],2)");
Object result = formula.evaluate(Map.of("price", new BigDecimal("12.35"), "quantity", 3));
```

Use `FormulaPlan` for ordered calculated fields in one record. Use
`FormulaAggregatePlan` for parent/detail calculations. Its `Scope` declarations
separate ordinary input fields from calculated fields. Original inputs must not
include calculated outputs. Results are immutable and preserve null values.

- Local references: `[fieldId]`.
- Parent formulas can aggregate a detail column: `SUM([detailId.fieldId])`.
- Detail formulas can reference the parent: `[parent.fieldId]`.
- Detail-to-detail references are deliberately rejected; there is no implicit
  cross-product or ambiguous selection of another row.
- Dependency validation covers all conditional branches and empty collections.
- `dependencies()` includes transitive dependencies for host authorization checks.
- The host must supply a complete version-consistent aggregate. This library
  does not read missing detail rows or authorize access to source values.

The engine copies its function registry. Construct it with a custom list of
`FormulaFunction` descriptors and an injected `Clock` to extend it. Functions are
trusted Java code and must be bounded, side-effect-free and safe for concurrent use.
Lazy memoized arguments support conditionals without evaluating unused branches.
A record/aggregate evaluation uses one clock snapshot.

## DSL v1 semantics

The leading `=` is optional. Function names and boolean/null literals are case
insensitive. Field identities are case sensitive. References use square brackets;
`]` within an identity is escaped as `]]`. Strings use double quotes and escape a
quote with `""`. Identifiers and text can contain Unicode.

Precedence from tightest to loosest: unary sign, postfix percent, exponentiation,
multiplication/division, addition/subtraction, string concatenation `&`, comparison.
Binary operators with equal precedence associate left to right, including power.
Parentheses override precedence. The syntax is Excel-like, not a claim of complete
Excel behavior. Spreadsheet cell ranges, macros, Java reflection, SQL and network
calls are not supported.

Decimal arithmetic uses 38 significant digits and HALF_UP arithmetic rounding;
input number literals remain decimal text. Integer powers and square root use
BigDecimal. Transcendental functions and fractional powers use StrictMath and
therefore have floating-point approximation accuracy. Use BigDecimal or decimal
strings for exact external numeric input; a floating-point input cannot recover
precision already lost by its caller.

Null and empty text coerce to zero in arithmetic. Boolean values coerce to 1/0.
Aggregates omit null/empty values and accept numeric text and booleans. An empty
SUM is zero; an empty AVERAGE is an error. Text functions count Unicode code points.
Comparisons of text are case insensitive; EXACT is case sensitive. Dates are ISO
calendar dates; NOW uses the injected clock's civil date/time. Hosts must explicitly
adapt dates and timestamps to their storage contracts.

### Function catalog

| Category | Functions |
| --- | --- |
| Logic | IF, IFERROR, AND, OR, NOT, ISBLANK, ISNUMBER, ISTEXT, COALESCE |
| Aggregates | SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, PRODUCT |
| Math | ABS, SIGN, INT, ROUND, ROUNDUP, ROUNDDOWN, TRUNC, MOD, POWER, SQRT, PI, SIN, COS, TAN, EXP, LN, LOG10 |
| Conversion | VALUE, NUMBER, BOOLEAN, STRING, TEXT |
| Text | LEN, LOWER, UPPER, TRIM, CLEAN, EXACT, CONCAT, CONCATENATE, TEXTJOIN, LEFT, RIGHT, MID, REPT, SUBSTITUTE, REPLACE, FIND, SEARCH |
| Date/time | DATE, DATEVALUE, TODAY, NOW, YEAR, MONTH, DAY, DAYS |

SEARCH performs case-insensitive literal matching, without wildcard or regex
execution. TEXT accepts `0`, `0.00` (up to 38 decimal places), optional `%`,
`yyyy-mm-dd`, `yyyy/mm/dd`, `yyyy`, `mm`, and `dd`. Unsupported formats fail explicitly.
IFERROR handles ordinary conversion, arithmetic, date, lookup and formatting errors;
it cannot suppress resource-limit or configuration errors.

### Bounds and diagnostics

Text length: 8,192; tokens: 2,048; AST nodes: 1,024; AST depth: 64; arguments: 256.
Numbers: at most 128 digits of precision and absolute scale 128. Result strings:
65,536 UTF-16 units. Collections: 10,000 values, one level only. Each evaluation
bounds visited items and cumulative text. Plans allow 256 formulas and 4,096 inputs.
Aggregates additionally bound total rows, estimated work and accumulated output.

`FormulaException` exposes a stable code, one-based line and zero-based column.
Messages contain no submitted values. HTTP adapters should map these to their own
namespaced localized envelopes without exposing source data.

## Browser syntax and editor

The grammar in `src/main/antlr/Calculation.g4` generates both parsers. Browser
packages are `simplepoint-react/libs/calculation` and `libs/calculation-editor`.
The editor is controlled React and needs field/function catalogs and an `onChange`
callback. It does not fetch platform services or evaluate JavaScript expressions.

```bash
./gradlew :simplepoint-calculation:generateTypeScriptGrammar
./gradlew :simplepoint-calculation:check
corepack pnpm@11.15.1 -C simplepoint-react --filter @simplepoint/calculation test
corepack pnpm@11.15.1 -C simplepoint-react --filter @simplepoint/calculation-editor typecheck
```

`check` verifies runtime isolation and regenerated browser grammar equality.
Both parser tests read `src/test/resources/formula-syntax.tsv`, including Unicode,
escaping, exact decimal text, precedence and canonical round trips.

## Platform integration status

The independent engine and editor are implemented. They are not yet connected to
published form fields or DNA record persistence. Existing COMPUTED fields still
have their legacy behavior until that integration is completed. Do not treat this
module's tests as evidence that published forms calculate values correctly.

Remaining integration must cover immutable optional formula metadata, server-owned
calculation writes, original request idempotency, optimistic aggregate versions,
transitive source-field permissions, historical schemas, formula-change backfill,
runtime preview and real published-form tests. See
`doc/design/application_form_engine_plan.md` for the overall work plan.
