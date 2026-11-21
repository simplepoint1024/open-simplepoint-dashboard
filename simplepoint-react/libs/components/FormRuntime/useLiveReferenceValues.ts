import {useContext, useEffect, useMemo, useState} from 'react';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {ReferenceRuntimeContext} from '../FormComponents/Reference/RuntimeContext';
import type {ApplicationFormDocument} from './contracts';
import type {FormValues} from './sceneState';
import {clearLiveReferences, liveReferenceKey, liveSceneFields, mergeLiveReferences, planLiveReferences, validateLiveBatch,
  type LiveReferenceQuery, type LiveReferenceView} from './liveReferenceValues';

/** Current display overlay only. The owning SForm remains the sole editable business buffer. */
export function useLiveReferenceValues(document: ApplicationFormDocument, sceneId: string, values: FormValues) {
  const sources = useContext(ReferenceRuntimeContext), selected = usePageSelected(), scope = useQueryScope();
  const planned = useMemo(() => {
    try {return {plan: planLiveReferences(document, values, liveSceneFields(document, sceneId))};}
    catch {return {failed: true};}
  }, [document, sceneId, values]);
  const key = JSON.stringify(planned.plan?.queries ?? []);
  const queries = useMemo(() => JSON.parse(key) as LiveReferenceQuery[], [key]);
  const active = selected && Boolean(sources) && queries.length > 0 && !planned.failed;
  const [revision, setRevision] = useState(0);
  const token = useMemo(() => ({}), [active, queries, scope, sources, document, sceneId, revision]);
  const [state, setState] = useState<{token: object; values?: Map<string, LiveReferenceView>; failed?: boolean}>();
  useEffect(() => {
    if (!active || !sources) return;
    const request = new AbortController();
    let current = true;
    const valid = () => current && !request.signal.aborted && JSON.stringify(scope) === getQueryScopeSnapshot();
    const timer = setTimeout(() => {
      const run = async () => {
        const result = new Map<string, LiveReferenceView>();
        const batches = queries.flatMap(query => Array.from({length: Math.ceil(query.ids.length / 100)}, (_, index) =>
          ({relationId: query.relationId, ids: query.ids.slice(index * 100, (index + 1) * 100)})));
        // Bound browser concurrency; each relation still uses server-side row and field policies.
        for (let offset = 0; offset < batches.length; offset += 4) {
          if (!valid()) return;
          await Promise.all(batches.slice(offset, offset + 4).map(async query => {
            const load = sources(query.relationId).live;
            if (!load) throw new Error('Live reference adapter unavailable');
            const rows = validateLiveBatch(query.ids, await load(query.ids, request.signal));
            for (const row of rows) result.set(liveReferenceKey(query.relationId, row.targetRecordId!), row);
          }));
        }
        if (valid()) setState({token, values: result});
      };
      void run().catch(() => {if (valid()) setState({token, failed: true});});
    }, 150);
    return () => {current = false; clearTimeout(timer); request.abort();};
  }, [active, sources, queries, scope, token]);
  const result = active && state?.token === token ? state : undefined;
  let display = planned.failed ? clearLiveReferences(document, values) : values, failed = Boolean(planned.failed || result?.failed);
  if (planned.plan) {
    try {display = mergeLiveReferences(values, planned.plan, result?.values);}
    catch {display = mergeLiveReferences(values, planned.plan); failed = true;}
  }
  return {values: display, loading: active && !result, failed, refreshable: active,
    refresh: () => {if (active) setRevision(value => value + 1);}};
}
