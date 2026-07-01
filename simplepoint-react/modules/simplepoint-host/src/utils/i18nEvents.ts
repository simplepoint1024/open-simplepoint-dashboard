export type I18nEventType =
  | 'sp-set-locale'
  | 'sp-i18n-updated'
  | 'sp-i18n-missing-batch'
  | 'sp-refresh-route';

/**
 * 异步派发自定义事件，避免在 React 渲染期间触发副作用
 */
export const emitAsync = (type: I18nEventType, detail?: any) => {
  try {
    window.setTimeout(() => {
      try {
        window.dispatchEvent(new CustomEvent(type, { detail }));
      } catch (err) {
        console.warn(`[i18n] Failed to dispatch event: ${type}`, err);
      }
    }, 0);
  } catch (err) {
    console.warn(`[i18n] Failed to schedule event: ${type}`, err);
  }
};
