import {fixturePage, fixtureSchema} from './query-fixture.mjs';

const text = label => ({type: 'string', label});
const inboxFields = {title: text('事项标题'), taskName: text('当前 / 处理节点'), definitionName: text('流程名称'),
  starterName: text('发起人'), assigneeName: text('办理人'), actorName: text('实际操作人'),
  outcome: {...text('处理结果'), enum: ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELED', 'COMPLETED']},
  instanceStatus: text('流程状态'), priority: {type: 'integer'}, createdAt: {type: 'date-time'}, handledAt: {type: 'date-time'}};
const matterFields = {title: text('事项标题'), definitionName: text('流程名称'), definitionVersion: {type: 'integer'},
  starterName: text('发起人'), status: text('流程状态'), startedAt: {type: 'date-time'}, endedAt: {type: 'date-time'}};
export const definitionFields = {key: text('流程标识'), name: text('流程名称'), category: text('分类'),
  description: text('说明'), latestVersion: {type: 'integer'}, status: text('状态'), updatedAt: {type: 'date-time'}};
const now = '2026-10-11T01:00:00Z', later = '2026-10-11T02:00:00Z';

export function createWorkflowPersonalFixture({xml, contract, definition}) {
  const matters = ['1', 'reject', 'withdraw', 'cancel', 'claim'].map(key => ({id: 'instance' + key, definitionId: 'definition1',
    definitionName: '审批流程', definitionVersion: 1, title: '申请事项 ' + key, starterName: '张三', status: 'RUNNING',
    startedAt: now, canWithdraw: true, canCancel: true, canResubmit: false}));
  const tasks = ['1', 'reject', 'claim'].map(key => ({id: 'task' + key, taskId: 'task' + key, processInstanceId: 'instance' + key,
    rowType: 'TASK', title: '申请事项 ' + key, definitionName: '审批流程', taskName: '审批任务', starterName: '张三',
    assigneeName: key === 'claim' ? undefined : '李四', assigned: key !== 'claim', claimable: key === 'claim', actionable: key !== 'claim',
    priority: 50, createdAt: now, outcome: 'PENDING', instanceStatus: 'RUNNING', handledByMe: false}));
  const events = new Map(matters.map(item => [item.id, [{id: item.id + '-start', actionType: 'STARTED', actorName: '张三', occurredAt: now}]]));
  const failures = new Set();
  let listDelay = 0;
  let sequence = 0;
  let diagramXml = xml;
  const result = (rows, fields, url, fixed) => fixturePage(rows, fields, url, fixed);
  const addAction = (matter, action, comment, task) => {
    events.get(matter.id).push({id: 'event-' + ++sequence, actionType: action, actorName: '李四', comment,
      occurredAt: later, ...(task ? {activityId: 'review', activityName: '审批'} : {})});
  };
  const finish = (matter, action, comment, task) => {
    Object.assign(matter, {status: action === 'APPROVED' ? 'COMPLETED' : action, endedAt: later,
      canWithdraw: false, canCancel: false, canResubmit: action !== 'APPROVED'});
    if (task) Object.assign(task, {outcome: action, instanceStatus: matter.status, comment, actorName: '李四',
      handledAt: later, handledByMe: true, claimable: false, actionable: false});
    else tasks.push({id: 'action-' + matter.id, processInstanceId: matter.id, rowType: 'PROCESS_ACTION', title: matter.title,
      definitionName: matter.definitionName, starterName: matter.starterName, actorName: '李四', outcome: action,
      instanceStatus: matter.status, createdAt: now, handledAt: later, handledByMe: true, assigned: false, claimable: false, actionable: false});
    addAction(matter, action, comment, task);
  };
  return {matters, tasks, diagram(source) {diagramXml = source;}, fail(action) {failures.add(action);}, delayLists(milliseconds) {listDelay = milliseconds;}, async handle(req, url, data, respond, expectedFailure) {
    const path = url.pathname;
    if (/^\/workflow\/(inbox\/(pending|completed)|matters\/(started|copied|all))$/.test(path)) {
      if (listDelay) await new Promise(resolve => setTimeout(resolve, listDelay));
      if (failures.delete(path)) {expectedFailure(503); return respond({message: '列表暂时不可用（测试）'}, 503), true;}
    }
    if (path === '/workflow/overview') {
      const pending = tasks.filter(row => row.outcome === 'PENDING'), active = matters.filter(row => row.status === 'RUNNING');
      return respond({tenantWorkspace: req.headers['x-tenant-id'] !== '__platform__', canViewTodos: true, canViewMatters: true,
        pendingTodos: pending.length, activeMatters: active.length, todos: pending.slice(0, 5), matters: active.slice(0, 5)}), true;
    }
    if (path === '/workflow/runtime/permissions') return respond({canClaim: true, canComplete: true, canStartInstances: true,
      canViewAllInstances: true, canCancelInstances: true}), true;
    if (/^\/workflow\/inbox\/(pending|completed)(\/schema)?$/.test(path)) {
      if (path.endsWith('/schema')) return respond(fixtureSchema(inboxFields)), true;
      return respond(result(tasks.filter(row => (row.outcome === 'PENDING') === path.endsWith('/pending')), inboxFields, url)), true;
    }
    if (path.startsWith('/workflow/inbox/tasks/')) {
      const task = tasks.find(row => row.taskId === path.split('/').at(-1));
      return respond(task ?? {message: '待办不可访问'}, task ? 200 : 404), true;
    }
    if (path.startsWith('/workflow/matters/startable')) {
      if (path.endsWith('/schema')) return respond(fixtureSchema(definitionFields)), true;
      const rows = [{...definition, latestVersion: 2}];
      return respond(path.endsWith('/options') ? rows : result(rows, definitionFields, url)), true;
    }
    if (/^\/workflow\/matters\/(started|copied|all)(\/schema)?$/.test(path)) {
      if (path.endsWith('/schema')) return respond(fixtureSchema(matterFields)), true;
      return respond(result(path.includes('/copied') ? matters.slice(0, 1) : matters, matterFields, url)), true;
    }
    const taskMatch = path.match(/^\/workflow\/tasks\/(task[^/]+)\/(form|claim|complete|reject)$/);
    const instanceMatch = path.match(/^\/workflow\/instances\/([^/]+)\/(progress|nodes\/[^/]+|actions|resubmission-form|resubmit|withdraw|cancel)$/);
    if (!taskMatch && !instanceMatch) return false;
    const task = taskMatch && tasks.find(row => row.taskId === taskMatch[1]);
    const matter = matters.find(row => row.id === (taskMatch ? task?.processInstanceId : instanceMatch[1]));
    if (!matter) throw Error('Unknown personal workflow fixture: ' + path);
    const operation = taskMatch?.[2] ?? instanceMatch[2];
    if (operation === 'form') return respond({definitionVersion: 1, formContract: contract, values: {approved: false}}), true;
    if (operation === 'progress') return respond({matter, diagram: {instance: matter, bpmnXml: diagramXml,
      activities: [{id: matter.id + '-review', activityId: 'review', activityName: '审批', activityType: 'userTask',
        taskId: tasks.find(row => row.processInstanceId === matter.id)?.taskId, status: matter.status === 'RUNNING' ? 'ACTIVE' : 'COMPLETED',
        startedAt: now, endedAt: matter.endedAt}],
      activeActivityIds: matter.status === 'RUNNING' ? ['review'] : [], totalActivities: 1, historyTruncated: false}}), true;
    if (operation.startsWith('nodes/')) {
      const node = operation.slice(6), current = tasks.find(row => row.processInstanceId === matter.id);
      const rows = node === 'review' && !(url.searchParams.get('activeOnly') === 'true' && matter.status !== 'RUNNING') ? [{
        id: matter.id + '-review', activityId: 'review', activityName: '审批', activityType: 'userTask', taskId: current?.taskId,
        status: matter.status === 'RUNNING' ? 'ACTIVE' : 'COMPLETED', assigned: current?.assigned ?? false,
        assigneeName: current?.assigneeName, actorName: current?.actorName, outcome: current?.outcome, comment: current?.comment,
        startedAt: now, endedAt: matter.endedAt, candidateUsers: [{name: '李四'}], candidateRoles: [{name: '部门审批人'}],
      }] : [];
      return respond({content: rows, page: 0, size: 20, totalElements: rows.length}), true;
    }
    if (operation === 'actions') return respond({recordedFromStart: true,
      events: {content: [...events.get(matter.id)].reverse(), page: 0, size: 20, totalElements: events.get(matter.id).length}}), true;
    if (operation === 'resubmission-form') return respond({source: matter, ccUserIds: [],
      form: {definitionVersion: matter.definitionVersion, formContract: contract, values: {approver: 'member-1'}}}), true;
    if (req.method !== 'POST' || matter.status !== 'RUNNING' && operation !== 'resubmit') throw Error('Invalid workflow fixture command state');
    if (['reject', 'withdraw', 'cancel'].includes(operation) && (!data.reason?.trim() || data.reason.length > 500)) throw Error('Missing bounded reason');
    await new Promise(resolve => setTimeout(resolve, 250));
    if (failures.delete(operation)) {
      expectedFailure(503);
      return respond({message: {complete: '任务提交失败（测试）', cancel: '流程终止失败（测试）', reject: '驳回失败（测试）'}[operation] ?? '操作失败（测试）'}, 503), true;
    }
    if (operation === 'claim') {
      Object.assign(task, {assigned: true, assigneeName: '李四', claimable: false, actionable: true}); addAction(matter, 'CLAIMED', undefined, task);
    } else if (operation === 'resubmit') {
      if (!matter.canResubmit || data.version !== matter.definitionVersion) throw Error('Invalid linked resubmission');
      const next = {...matter, id: 'resubmitted-' + ++sequence, title: data.title, status: 'RUNNING', endedAt: undefined,
        canWithdraw: true, canCancel: true, canResubmit: false, previousInstanceId: matter.id};
      matter.nextInstanceId = next.id; matter.canResubmit = false; matters.push(next); events.set(next.id, []);
      addAction(next, 'RESUBMITTED'); return respond(next), true;
    } else finish(matter, {complete: 'APPROVED', reject: 'REJECTED', withdraw: 'WITHDRAWN', cancel: 'CANCELED'}[operation], data.reason ?? data.comment, task);
    return respond(matter), true;
  }};
}
