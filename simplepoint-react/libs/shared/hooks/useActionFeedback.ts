import {useCallback, useMemo} from 'react';
import {App} from 'antd';
import {createActionRunner, isFormValidationError} from '../api/actionRunner';
import {isHttpError, resolveApiErrorMessage} from '../api/client';
import {useI18n} from './useI18n';

/** Callers keep success/domain logic; failures never become unhandled event promises. */
export function useActionFeedback() {
  const {message} = App.useApp();
  const {t} = useI18n();
  const run = useMemo(createActionRunner, []);
  return useCallback(<T,>(key: string, action: () => Promise<T>) => run(key, action, error => {
    if (isFormValidationError(error)) return; // Ant Form already renders field errors.
    if (isHttpError(error) && (error.kind === 'abort' || error.__notified)) return;
    message.error(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
  }), [message, run, t]);
}
