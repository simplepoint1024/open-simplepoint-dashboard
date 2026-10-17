import {useEffect, useRef, useState} from 'react';
import type {DesignDocument, FormDraft} from '@simplepoint/components/FormDesigner';
import {get, put} from '@simplepoint/shared/api/methods';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {sameDesignDocument} from '@simplepoint/components/FormDesigner/draftState';

type SaveRequest = {expectedRevision: number; document: DesignDocument};

/** Revision-controlled design session; response loss never causes a silent overwrite or draft reset. */
export default function useFormDraft(endpoint: string, initial: FormDraft, editable: boolean, autoSavePaused = false) {
  const {t} = useI18n(), operation = useOperationScope(endpoint);
  const [document, setDocument] = useState(initial.document), [saved, setSaved] = useState(initial.document);
  const [revision, setRevision] = useState(initial.revision), [reset, setReset] = useState(0);
  const [checksum, setChecksum] = useState(initial.checksum);
  const [error, setError] = useState(''), [conflict, setConflict] = useState(false), [retry, setRetry] = useState<SaveRequest>();
  const documentRef = useRef(document); documentRef.current = document;
  const dirty = !sameDesignDocument(document, saved);
  usePageRetention(!dirty && !operation.busy && !retry, dirty || operation.busy || Boolean(retry));
  const accept = (result: FormDraft, snapshot: DesignDocument) => {
    setRevision(result.revision); setChecksum(result.checksum); setSaved(snapshot); setRetry(undefined); setConflict(false); setError('');
  };
  const send = async (request: SaveRequest) => {
    if (!editable || conflict) return false;
    const controller = operation.begin(); if (!controller) return false;
    setError('');
    try {
      const result = await put<FormDraft>(endpoint, request, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return false;
      accept(result, request.document); return true;
    } catch (cause) {
      if (!operation.current(controller.signal)) return false;
      const uncertain = !isHttpError(cause) || cause.status === 0 || cause.status >= 500;
      const revisionConflict = isHttpError(cause) && cause.status === 409;
      if (uncertain || revisionConflict) {
        try {
          const remote = await get<FormDraft>(endpoint, undefined, {signal: controller.signal, notifyOnError: false});
          if (!operation.current(controller.signal)) return false;
          if (sameDesignDocument(remote.document, request.document)) {accept(remote, request.document); return true;}
          if (remote.revision !== request.expectedRevision) {
            setConflict(true); setRetry(undefined);
            setError(t('app.designer.conflict', '服务器草稿已被其他编辑更新。已保留你的修改，请核对后重新加载。'));
            return false;
          }
        } catch {/* Keep the original request when the reconciliation read is also unavailable. */}
        if (!operation.current(controller.signal)) return false;
        setRetry(request);
      }
      setError(resolveApiErrorMessage(cause, t('app.designer.saveFailed', '草稿保存失败，修改已保留。')));
      return false;
    } finally {operation.finish(controller);}
  };
  const save = () => send(retry ?? {expectedRevision: revision, document: structuredClone(documentRef.current)});
  const saveRef = useRef(save); saveRef.current = save;
  useEffect(() => {
    if (autoSavePaused || !dirty || !editable || !operation.selected || operation.busy || retry || conflict || error) return;
    const timer = setTimeout(() => void saveRef.current(), 1600);
    return () => clearTimeout(timer);
  }, [document, dirty, editable, autoSavePaused, operation.selected, operation.busy, retry, conflict, error]);
  const reload = async () => {
    const controller = operation.begin(); if (!controller) return;
    try {
      const remote = await get<FormDraft>(endpoint, undefined, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      documentRef.current = remote.document; setDocument(remote.document); accept(remote, remote.document); setReset(value => value + 1);
    } catch (cause) {
      if (operation.current(controller.signal)) setError(resolveApiErrorMessage(cause, t('app.designer.loadFailed', '草稿加载失败')));
    } finally {operation.finish(controller);}
  };
  return {document, dirty, revision, checksum, reset, error, conflict, retry, busy: operation.busy, selected: operation.selected,
    disabled: !editable || !operation.selected || conflict || Boolean(retry), save, reload,
    change: (next: DesignDocument) => {
      if (!editable || !operation.isSelected() || conflict || retry) return;
      documentRef.current = next; setDocument(next); setError('');
    }};
}
