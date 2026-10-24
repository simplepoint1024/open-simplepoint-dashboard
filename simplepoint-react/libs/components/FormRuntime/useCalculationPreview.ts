import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {useEffect, useMemo, useState} from 'react';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {ApplicationFormDocument} from './contracts';
import type {FormValues} from './sceneState';
import {calculationInputs, hasCalculatedFields, mergeCalculationPreview, type CalculationPreviewLoader} from './calculationPreview';

/** One cancelled/fenced read per active scene. Prospective values never replace the owning edit buffer. */
export function useCalculationPreview(document: ApplicationFormDocument, values: FormValues,
  loader: CalculationPreviewLoader | undefined, enabled: boolean) {
  const selected = usePageSelected(), scope = useQueryScope();
  const active = enabled && selected && Boolean(loader) && hasCalculatedFields(document);
  const key = JSON.stringify(calculationInputs(document, values));
  const inputs = useMemo(() => JSON.parse(key) as FormValues, [key]);
  const token = useMemo(() => ({}), [active, inputs, scope, document, loader]);
  const [state, setState] = useState<{token: object; key: string; scope: typeof scope; document: ApplicationFormDocument;
    loader: CalculationPreviewLoader; values?: FormValues; failed?: boolean}>();
  useEffect(() => {
    if (!active || !loader) return;
    const request = new AbortController();
    let current = true;
    const currentScope = () => current && !request.signal.aborted && JSON.stringify(scope) === getQueryScopeSnapshot();
    const timer = setTimeout(() => {
      if (!currentScope()) return;
      void Promise.resolve().then(() => {
        if (!currentScope()) throw new Error('Calculation preview scope changed');
        return loader(inputs, request.signal);
      }).then(result => {
        const projected = mergeCalculationPreview(document, inputs, result);
        if (currentScope()) setState({token, key, scope, document, loader, values: projected});
      }).catch(() => {
        if (currentScope()) setState({token, key, scope, document, loader, failed: true});
      });
    }, 300);
    return () => {current = false; clearTimeout(timer); request.abort();};
  }, [active, inputs, key, scope, document, loader, token]);
  const result = active && state?.token === token && state.key === key && state.scope === scope && state.document === document && state.loader === loader ? state : undefined;
  return {values: !active ? values : result?.values ?? mergeCalculationPreview(document, values),
    loading: active && !result, failed: Boolean(result?.failed)};
}
