import {http, HttpResponse} from 'msw';

const languageMap: Record<string, any> = {
  'zh-CN': require("./local/zh-CN.json"),
  'en-US': require("./local/en-US.json"),
  'ja-JP': require("./local/ja-JP.json"),
}

export default [
  // 语言列表
  http.get('/common/i18n/languages/mapping', () => {
    return HttpResponse.json([
      {code: 'zh-CN', name: '中文（简体）'},
      {code: 'en-US', name: 'English'},
      {code: 'ja-JP', name: '日本語'},
    ]);
  }),

  // 指定语言的消息键值对（支持可选命名空间 ns=profile,settings）
  http.get('/common/i18n/messages/mapping', ({request}) => {
    const url = new URL(request.url);
    const locale = url.searchParams.get('locale') || 'zh-CN';
    const nsParam = url.searchParams.get('ns');
    const nsList = nsParam ? nsParam.split(',').map(s => s.trim()).filter(Boolean) : undefined;
    const pack = languageMap[locale] || {};

    let messages: Record<string, any> = {};

    if (nsList && nsList.length) {
      for (const ns of nsList) {
        const data = pack[ns];
        if (data && typeof data === 'object') {
          Object.assign(messages, data);
        }
      }
    } else {
      messages = {...pack.common, ...pack.menu};
    }
    return HttpResponse.json(messages);
  }),
];
