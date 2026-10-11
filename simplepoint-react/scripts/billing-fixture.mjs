import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';
export const billingRoute = {id: 'billing-standard', code: 'ai.billing.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/billing-standard', component: 'ai/workbench/Billing', label: '计费标准测试', scopeTypes: ['PLATFORM', 'TENANT']};
const base = '/ai/workbench/billing';
export function createBillingFixture() {
  let enabled = false, fail = false, delay = 0;
  const reads = [], fields = {modelId: {type: 'string'}, startedAt: {type: 'string', format: 'date-time'}, totalCost: {type: 'number'}, billingCurrency: {type: 'string'}};
  const schema = localQuerySchema(fields);
  Object.values(schema.properties).forEach(field => {field['x-ui'] = {'x-list-visible': true};});
  return {reads, enable() {enabled = true;}, failNext() {fail = true;}, delayNext(value) {delay = value;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled || !url.pathname.startsWith(base)) return false;
      if (req.method !== 'GET') throw Error('Billing must be read-only');
      const path = url.pathname, params = new URLSearchParams(url.searchParams), tenant = req.headers['x-tenant-id'];
      reads.push({path, params, tenant});
      if (path === base + '/invocations/schema') return respond({schema, buttons: []}), true;
      if (path === base + '/summary') {
        const from = params.get('from'), to = params.get('to'), duration = Date.parse(to) - Date.parse(from);
        if (duration <= 0 || duration > 366 * 86400000) throw Error('Invalid billing summary period');
        const shouldFail = fail, wait = delay; fail = false; delay = 0;
        await new Promise(resolve => setTimeout(resolve, wait || 100));
        if (shouldFail) {expectedFailure(503); return respond({errorCode: 'AI_BILLING_QUERY_FAILED'}, 503), true;}
        const currencies = [{currency: 'USD', totalCost: '9007199254740993.010203040506'}, {currency: 'CNY', totalCost: '0.000000000001'}];
        return respond({from, to, invocationCount: tenant === '__platform__' ? 521 : 37,
          pricedInvocationCount: tenant === '__platform__' ? 501 : 30, unpricedInvocationCount: 7, currencies,
          models: Array.from({length: 20}, (_, i) => ({modelDefinitionId: 'model-' + i, modelId: (tenant === '__platform__' ? 'PlatformModel' : 'TenantModel') + i,
            currency: i % 2 ? 'CNY' : 'USD', totalCost: i === 0 ? currencies[0].totalCost : '0.00000001', invocationCount: 20, inputTokens: 100, outputTokens: 100}))}), true;
      }
      if (path === base + '/invocations') {
        const document = JSON.parse(params.get('$query'));
        if (document.version !== 1 || document.where.op !== 'and') throw Error('Invocation period is not constrained');
        const [start, end] = document.where.children;
        if (start.field !== 'startedAt' || start.op !== 'than:equal:greater' || end.field !== 'startedAt' || end.op !== 'than:less') throw Error('Invocation period boundaries differ');
        const rows = Array.from({length: 521}, (_, i) => ({id: 'invocation-' + i, modelId: 'Invocation' + i,
          startedAt: new Date(Date.parse(start.value) + i * 1000).toISOString(), billingCurrency: 'USD', totalCost: '0.00000001'}));
        rows.push({id: 'outside', modelId: 'Outside period', startedAt: end.value, billingCurrency: 'USD', totalCost: '999'});
        const query = compileLocalQuery(fields, {filters: Object.fromEntries([...params].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: params.getAll('sort')});
        const result = rows.filter(query.matches).sort(query.compare), page = Number(params.get('page')), size = Number(params.get('size'));
        if (size < 1 || size > 100) throw Error('Unbounded billing invocation page');
        return respond({content: result.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}}), true;
      }
      throw Error('Unexpected billing read ' + path);
    }};
}
