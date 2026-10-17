import {useEffect, useRef, useState} from 'react';
import {get, post} from '@simplepoint/shared/api/methods';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {serverViolations} from '@simplepoint/shared/validation/server';
import {publishIntent, releaseIsActive, releaseIsCancelled, releaseIsBlocked, type PublicationIntent, type FormRelease, type FormVersion} from './publication';

export default function useFormPublication(baseUrl: string, enabled: boolean, visible: boolean) {
  const {t} = useI18n(), operation = useOperationScope(baseUrl);
  const intent = useRef<PublicationIntent | undefined>(undefined);
  const [release, setRelease] = useState<FormRelease>(), [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [invalidFields, setInvalidFields] = useState<string[]>([]);
  usePageRetention(!operation.busy && !pending, operation.busy || pending);
  const run = async (request?: Omit<PublicationIntent, 'idempotencyKey'>) => {
    if (!enabled) return;
    const controller = operation.begin(); if (!controller) return;
    if (request && !intent.current) intent.current = {...request, idempotencyKey: crypto.randomUUID()};
    const captured = intent.current;
    if (!captured) {operation.finish(controller); return;}
    setError(''); setInvalidFields([]);
    try {
      const options = {signal: controller.signal, notifyOnError: false};
      const result = await publishIntent(captured, {
        freeze: expectedRevision => post<FormVersion>(baseUrl + '/versions', {expectedRevision}, options),
        versions: () => get<FormVersion[]>(baseUrl + '/versions', undefined, options),
        publish: (version, command) => post<FormRelease>(`${baseUrl}/versions/${version}/publication`, command, options),
        current: () => operation.current(controller.signal),
        mayReconcile: cause => !isHttpError(cause) || cause.status === 0 || cause.status === 409 || cause.status >= 500,
      });
      if (!operation.current(controller.signal)) return;
      setRelease(result);
      const cancelled = releaseIsCancelled(result);
      const blocked = releaseIsBlocked(result);
      const failed = !cancelled && !blocked && (result.dataPublication?.status === 'FAILED' || result.pageBinding?.status === 'FAILED'
        || result.pageBinding?.status === 'RETIRED');
      if (cancelled) intent.current = undefined;
      setPending(!releaseIsActive(result) && !failed && !cancelled && !blocked);
      if (failed) setError(t('app.publication.failed', '发布未完成，可重试本次发布。'));
    } catch (cause) {
      if (!operation.current(controller.signal)) return;
      setPending(false);
      const reviewRequired = isHttpError(cause) && cause.code === 'PUBLICATION_REVIEW_REQUIRED';
      setError(reviewRequired ? t('app.publication.reviewRequired', '本次字段变更需要数据迁移审核，请调整设计或联系管理员。')
        : resolveApiErrorMessage(cause, t('app.publication.failed', '发布未完成，可重试本次发布。')));
      setInvalidFields(isHttpError(cause) ? (serverViolations(cause.data) ?? [])
        .map(violation => violation.field).filter(field => field.startsWith('fields.')).map(field => field.slice(7)) : []);
      // A definitively rejected freeze has no remote publication to recover; allow design repair.
      if (reviewRequired || !captured.version && isHttpError(cause) && cause.status >= 400 && cause.status < 500) intent.current = undefined;
    } finally {operation.finish(controller);}
  };
  const runRef = useRef(run); runRef.current = run;
  useEffect(() => {
    if (!pending || operation.busy || !operation.selected || !visible || !enabled) return;
    const timer = setTimeout(() => void runRef.current(), 3000);
    return () => clearTimeout(timer);
  }, [pending, operation.busy, operation.selected, visible, enabled]);
  return {release, error, invalidFields, pending, busy: operation.busy, intent: intent.current, active: releaseIsActive(release), cancelled: releaseIsCancelled(release), run,
    reset: () => {if (!operation.isBusy() && !pending) {intent.current = undefined; setRelease(undefined); setError('');}}};
}
