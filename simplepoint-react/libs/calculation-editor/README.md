# Independent calculation editor

Controlled React component with no SimplePoint UI, authorization, routing, i18n,
HTTP or persistence dependency. It consumes the sibling standalone syntax package
and React. A host supplies field IDs/labels, version-matched function descriptors,
translations and optional preview/help content.

```tsx
import {CalculationEditor} from '@simplepoint/calculation-editor';

<CalculationEditor value={expression} onChange={setExpression}
  fields={fields} functions={functions} labels={labels}/>
```

The default view is visual. Drag palette blocks into an expression slot, or select
a slot and activate a palette item with the keyboard. Existing blocks can be moved;
functions support optional argument insertion/removal. The expression tab supports
text editing and parsing back to the visual tree. Invalid expression text is kept
as the controlled value and cannot silently be replaced by a previous valid tree.
Hosts must validate before saving/publishing. Read-only mode blocks mutations.

`CalculatorLabels` supplies every fixed UI label and a diagnostic formatter.
Field/function titles come from the host catalog. The Java engine is the evaluator;
the editor does not execute expressions with JavaScript or call a platform server.

Native HTML controls are intentional inside this specialized standalone editor.
It is not a resource CRUD page and must not import platform table, action,
selection or form controllers. A platform adapter owns surrounding resource actions
and form-field policies.

## Independent browser demonstration and verification

Run from the frontend workspace:

```bash
corepack pnpm@11.15.1 exec rsbuild build -c libs/calculation-editor/demo/rsbuild.config.mjs
node scripts/smoke-calculation-editor.mjs
```

The demonstration only uses React, the editor and supplied sample catalogs. The
browser test runs without platform services. It covers actual pointer drag,
keyboard insertion, Unicode literals, expression/visual round trips, invalid draft
preservation and bounded layouts at 1280/768/390 pixels. `CALCULATION_SCREENSHOT`
can specify a screenshot output path.
