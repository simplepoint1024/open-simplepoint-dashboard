/**
 * 初始化 <html> 标签的语言、方向和主题属性
 * - data-theme: 根据 sp.theme 和系统偏好设置 light/dark
 * - lang: 根据 sp.locale 设置语言
 * - dir: 根据语言判断是否为 rtl（如阿拉伯语、希伯来语等）
 */
export function applyInitialHtmlAttributes() {
  try {
    const html = document.documentElement;

    // 主题设置
    const themePref = localStorage.getItem('sp.theme') as 'light' | 'dark' | 'system' | null;
    const systemDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
    const resolvedTheme =
      themePref === 'dark'
        ? 'dark'
        : themePref === 'system'
          ? systemDark ? 'dark' : 'light'
          : 'light';
    html.setAttribute('data-theme', resolvedTheme);

    // 语言与方向设置
    const rawLocale = localStorage.getItem('sp.locale') || 'zh-CN';
    const low = rawLocale.toLowerCase();
    html.setAttribute('lang', rawLocale);
    html.setAttribute('dir', /^(ar|he|fa|ur)(-|$)/i.test(low) ? 'rtl' : 'ltr');
  } catch (err) {
    console.warn('[applyInitialHtmlAttributes] Failed to initialize <html> attributes:', err);
  }
}
