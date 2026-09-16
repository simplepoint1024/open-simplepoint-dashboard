#!/usr/bin/env node

import {randomUUID} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';

const baseUrl = (process.env.SIMPLEPOINT_API_BASE_URL || 'http://127.0.0.1:8080/ai')
  .replace(/\/$/, '');
const cookie = process.env.SIMPLEPOINT_SESSION_COOKIE?.trim();
const contextId = process.env.SIMPLEPOINT_CONTEXT_ID?.trim();
const refreshMcp = process.env.SIMPLEPOINT_REFRESH_MCP === 'true';
const publishSkills = process.env.SIMPLEPOINT_PUBLISH_SKILLS === 'true';

if (!cookie) {
  throw new Error('SIMPLEPOINT_SESSION_COOKIE is required');
}

const headers = {
  Accept: 'application/json',
  Cookie: cookie,
  ...(contextId ? {'X-Authorization-Context-Id': contextId} : {}),
};

const request = async (method, path, body, allowNotFound = false) => {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...headers,
      ...(body === undefined ? {} : {'Content-Type': 'application/json'}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (allowNotFound && response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`${method} ${path} returned ${response.status}: ${text}`);
  }
  return text ? JSON.parse(text) : null;
};

const waitFor = async (load, terminalStatuses, label) => {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const current = await load();
    if (terminalStatuses.includes(current.status)) return current;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`${label} did not finish within 30 seconds`);
};

const objectSchema = (properties, required) => ({
  type: 'object',
  properties,
  required,
  additionalProperties: false,
});

const scenarios = [
  {
    code: 'inventory-availability-check',
    name: '库存可用性检查',
    description: '查询商品当前库存，判断需求数量是否可履约，并返回缺口和补货建议。',
    toolName: 'lookup_inventory',
    inputSchema: objectSchema({
      sku: {
        type: 'string',
        title: '商品 SKU',
        enum: ['SP-LAPTOP-14', 'SP-MONITOR-27', 'SP-DOCK-12'],
      },
      quantity: {type: 'integer', title: '需求数量', minimum: 1, maximum: 500},
    }, ['sku', 'quantity']),
    outputSchema: objectSchema({
      sku: {type: 'string'},
      productName: {type: 'string'},
      warehouse: {type: 'string'},
      requestedQuantity: {type: 'integer'},
      availableQuantity: {type: 'integer'},
      fulfillable: {type: 'boolean'},
      replenishmentAdvice: {type: 'string'},
    }, [
      'sku',
      'productName',
      'warehouse',
      'requestedQuantity',
      'availableQuantity',
      'fulfillable',
      'replenishmentAdvice',
    ]),
    liveInput: {sku: 'SP-MONITOR-27', quantity: 24},
    mockOutput: {
      sku: 'SP-MONITOR-27',
      productName: '27 英寸 4K 显示器',
      warehouse: '上海中心仓',
      requestedQuantity: 24,
      availableQuantity: 18,
      fulfillable: false,
      replenishmentAdvice: '当前缺口 6 件，建议拆单或发起补货',
    },
    assertion: {fieldPath: 'fulfillable', expected: false},
  },
  {
    code: 'order-taxed-quote',
    name: '订单含税报价',
    description: '根据商品、数量、目的省份和配送时效，生成可复核的含税报价及预计送达时间。',
    toolName: 'calculate_order_quote',
    inputSchema: objectSchema({
      sku: {
        type: 'string',
        title: '商品 SKU',
        enum: ['SP-LAPTOP-14', 'SP-MONITOR-27', 'SP-DOCK-12'],
      },
      quantity: {type: 'integer', title: '订购数量', minimum: 1, maximum: 100},
      destinationProvince: {type: 'string', title: '收货省份', minLength: 2, maxLength: 16},
      shippingSpeed: {
        type: 'string',
        title: '配送时效',
        enum: ['STANDARD', 'EXPRESS'],
      },
    }, ['sku', 'quantity', 'destinationProvince', 'shippingSpeed']),
    outputSchema: objectSchema({
      quoteNumber: {type: 'string'},
      productName: {type: 'string'},
      subtotal: {type: 'number'},
      freight: {type: 'number'},
      tax: {type: 'number'},
      total: {type: 'number'},
      currency: {type: 'string'},
      estimatedDeliveryDays: {type: 'integer'},
    }, [
      'quoteNumber',
      'productName',
      'subtotal',
      'freight',
      'tax',
      'total',
      'currency',
      'estimatedDeliveryDays',
    ]),
    liveInput: {
      sku: 'SP-LAPTOP-14',
      quantity: 3,
      destinationProvince: '浙江',
      shippingSpeed: 'EXPRESS',
    },
    mockOutput: {
      quoteNumber: 'Q-SPLAPTOP14-3-E',
      productName: '14 英寸商务笔记本',
      subtotal: 18897,
      freight: 44,
      tax: 2456.61,
      total: 21397.61,
      currency: 'CNY',
      estimatedDeliveryDays: 1,
    },
    assertion: {fieldPath: 'total', expected: 21397.61},
  },
  {
    code: 'service-incident-triage',
    name: '服务故障智能分诊',
    description: '根据服务、影响范围、客户等级和故障摘要确定优先级、响应 SLA 与升级动作。',
    toolName: 'triage_service_incident',
    inputSchema: objectSchema({
      service: {type: 'string', title: '受影响服务', minLength: 2, maxLength: 64},
      impact: {
        type: 'string',
        title: '影响范围',
        enum: ['SINGLE_USER', 'MULTIPLE_USERS', 'TENANT_WIDE', 'PLATFORM_WIDE'],
      },
      customerTier: {
        type: 'string',
        title: '客户等级',
        enum: ['STANDARD', 'PREMIUM'],
      },
      summary: {type: 'string', title: '故障摘要', minLength: 6, maxLength: 500},
    }, ['service', 'impact', 'customerTier', 'summary']),
    outputSchema: objectSchema({
      incidentKey: {type: 'string'},
      priority: {type: 'string'},
      queue: {type: 'string'},
      responseSlaMinutes: {type: 'integer'},
      escalationRequired: {type: 'boolean'},
      recommendedAction: {type: 'string'},
    }, [
      'incidentKey',
      'priority',
      'queue',
      'responseSlaMinutes',
      'escalationRequired',
      'recommendedAction',
    ]),
    liveInput: {
      service: 'OAuth 登录服务',
      impact: 'TENANT_WIDE',
      customerTier: 'PREMIUM',
      summary: '企业租户用户无法使用 GitHub 登录',
    },
    mockOutput: {
      incidentKey: 'INC-MOCK-0001',
      priority: 'P2',
      queue: 'OAuth 登录服务 服务支持队列',
      responseSlaMinutes: 30,
      escalationRequired: true,
      recommendedAction: '收集请求 ID 与时间窗口，交由服务负责人按 SLA 排查',
    },
    assertion: {fieldPath: 'priority', expected: 'P2'},
  },
];

const createDocument = (scenario, serverId, snapshotId) => {
  const toolNodeId = 'tool-1';
  const argumentMappings = Object.fromEntries(
    scenario.inputSchema.required.map((name) => [name, {$ref: `input.${name}`}]),
  );
  const outputMappings = Object.fromEntries(
    scenario.outputSchema.required.map((name) => [
      name,
      {$ref: `steps.${toolNodeId}.structuredContent.${name}`},
    ]),
  );
  return {
    schemaVersion: 'simplepoint.io/designer/v1alpha1',
    metadata: {
      name: scenario.code,
      version: '0.1.0',
      title: scenario.name,
      description: scenario.description,
    },
    inputSchema: scenario.inputSchema,
    outputSchema: scenario.outputSchema,
    tools: [{
      alias: toolNodeId,
      serverId,
      snapshotId,
      name: scenario.toolName,
    }],
    prompts: [],
    resources: [],
    nodes: [
      {
        id: '__input',
        type: 'INPUT',
        parentNodeId: null,
        branchId: null,
        order: 0,
        configuration: {},
        position: {x: 80, y: 220},
      },
      {
        id: '__output',
        type: 'OUTPUT',
        parentNodeId: null,
        branchId: null,
        order: 0,
        configuration: {},
        position: {x: 720, y: 220},
      },
      {
        id: toolNodeId,
        type: 'TOOL',
        parentNodeId: null,
        branchId: null,
        order: 0,
        configuration: {tool: toolNodeId, arguments: argumentMappings},
        position: {x: 400, y: 220},
      },
    ],
    ports: [
      {
        nodeId: '__input',
        id: 'out',
        direction: 'OUTPUT',
        kind: 'CONTROL',
        label: null,
        required: true,
        multiple: false,
      },
      {
        nodeId: '__output',
        id: 'in',
        direction: 'INPUT',
        kind: 'CONTROL',
        label: null,
        required: true,
        multiple: false,
      },
      {
        nodeId: toolNodeId,
        id: 'in',
        direction: 'INPUT',
        kind: 'CONTROL',
        label: null,
        required: true,
        multiple: false,
      },
      {
        nodeId: toolNodeId,
        id: 'out',
        direction: 'OUTPUT',
        kind: 'CONTROL',
        label: null,
        required: true,
        multiple: false,
      },
    ],
    edges: [
      {
        id: 'edge-0',
        sourceNodeId: '__input',
        sourceHandle: 'out',
        targetNodeId: toolNodeId,
        targetHandle: 'in',
      },
      {
        id: 'edge-1',
        sourceNodeId: toolNodeId,
        sourceHandle: 'out',
        targetNodeId: '__output',
        targetHandle: 'in',
      },
    ],
    workflowOutput: outputMappings,
    budgets: {
      maximumToolCalls: 4,
      maximumDurationSeconds: 60,
      maximumPayloadBytes: 262144,
    },
    approvals: {},
    tests: [{
      id: 'happy-path',
      name: '标准场景',
      enabled: true,
      input: scenario.liveInput,
      mocks: [{
        nodeId: toolNodeId,
        mode: 'SUCCESS',
        output: scenario.mockOutput,
        errorCode: null,
        errorMessage: null,
      }],
      assertions: [{
        id: `expect-${scenario.assertion.fieldPath}`,
        sourceNodeId: '__output',
        fieldPath: scenario.assertion.fieldPath,
        operator: 'EQUALS',
        expected: scenario.assertion.expected,
      }],
    }],
    viewport: {x: 0, y: 0, zoom: 1},
  };
};

const provisionMcpServer = async () => {
  const health = await fetch('http://127.0.0.1:3099/health');
  if (!health.ok) throw new Error('Local business MCP health check failed');

  const page = await request('GET', '/workbench/mcp/servers?page=0&size=500');
  let server = page.content?.find((item) => item.code === 'local-business-operations');
  if (!server) {
    server = await request('POST', '/workbench/mcp/servers', {
      name: '本地业务运营工具',
      code: 'local-business-operations',
      deploymentType: 'REMOTE',
      transportType: 'STREAMABLE_HTTP',
      endpointUrl: 'http://127.0.0.1:3099/mcp',
      authenticationType: 'NONE',
      allowPrivateNetwork: true,
      enabled: true,
      description: '本地真实业务场景 MCP：库存查询、订单报价和服务故障分诊',
    });
  }
  server = await request('GET', `/workbench/mcp/servers/${server.id}`);
  if (refreshMcp || server.status !== 'READY' || !server.activeSnapshotId) {
    await request('POST', `/workbench/mcp/servers/${server.id}/discover`, {});
    server = await request('GET', `/workbench/mcp/servers/${server.id}`);
  }
  if (server.status !== 'READY' || !server.activeSnapshotId) {
    throw new Error(`MCP Server is not READY: ${JSON.stringify(server)}`);
  }
  return server;
};

const provisionSkill = async (scenario, server, skills) => {
  let skill = skills.find((item) => item.code === scenario.code);
  if (!skill) {
    skill = await request('POST', '/workbench/skills', {
      code: scenario.code,
      name: scenario.name,
      description: scenario.description,
      enabled: true,
    });
    skills.push(skill);
  }

  const document = createDocument(scenario, server.id, server.activeSnapshotId);
  const currentDraft = await request('GET', `/workbench/skills/${skill.id}/draft`, undefined, true);
  let draft = currentDraft;
  if (!currentDraft || !isDeepStrictEqual(currentDraft.document, document)) {
    draft = await request('PUT', `/workbench/skills/${skill.id}/draft`, {
      expectedRevision: currentDraft?.revision ?? 0,
      document,
    });
  }

  const compilation = await request(
    'POST',
    `/workbench/skills/${skill.id}/draft/validate`,
    {},
  );
  if (!compilation.valid) {
    throw new Error(`${scenario.name} validation failed: ${JSON.stringify(compilation.diagnostics)}`);
  }

  const mockRun = await request(
    'POST',
    `/workbench/skills/${skill.id}/draft/mock-test-runs`,
    {revision: draft.revision, idempotencyKey: randomUUID()},
  );
  const completedMockRun = await waitFor(
    () => request(
      'GET',
      `/workbench/skills/${skill.id}/draft/mock-test-runs/${mockRun.id}`,
    ),
    ['PASSED', 'FAILED'],
    `${scenario.name} MOCK test`,
  );
  if (completedMockRun.status !== 'PASSED') {
    throw new Error(`${scenario.name} MOCK test failed: ${JSON.stringify(completedMockRun)}`);
  }

  const execution = await request(
    'POST',
    `/workbench/skills/${skill.id}/draft/debug-executions`,
    {
      revision: draft.revision,
      mode: 'LIVE',
      idempotencyKey: randomUUID(),
      input: scenario.liveInput,
    },
  );
  const completedExecution = await waitFor(
    () => request(
      'GET',
      `/workbench/skills/${skill.id}/draft/debug-executions/${execution.id}`,
    ),
    ['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'WAITING_APPROVAL'],
    `${scenario.name} LIVE execution`,
  );
  if (completedExecution.status !== 'SUCCEEDED') {
    throw new Error(`${scenario.name} LIVE execution failed: ${JSON.stringify(completedExecution)}`);
  }

  let publishedVersion = null;
  let publishTaskId = null;
  let publishedExecution = null;
  if (publishSkills) {
    const versionsPath = `/workbench/skills/${skill.id}/versions`;
    let versions = await request('GET', `${versionsPath}?page=0&size=100`);
    publishedVersion = versions.content?.find((item) => item.version === '0.1.0') || null;
    if (publishedVersion && publishedVersion.status !== 'PUBLISHED') {
      publishedVersion = await request(
        'POST',
        `${versionsPath}/${publishedVersion.id}/publish`,
        {},
      );
    } else if (!publishedVersion) {
      const task = await request(
        'POST',
        `/workbench/skills/${skill.id}/draft/publish-tasks`,
        {
          draftRevision: draft.revision,
          version: '0.1.0',
          activate: true,
          idempotencyKey: randomUUID(),
        },
      );
      const completedTask = await waitFor(
        () => request(
          'GET',
          `/workbench/skills/${skill.id}/draft/publish-tasks/${task.id}`,
        ),
        ['SUCCEEDED', 'FAILED'],
        `${scenario.name} publish task`,
      );
      if (completedTask.status !== 'SUCCEEDED') {
        throw new Error(`${scenario.name} publish failed: ${JSON.stringify(completedTask)}`);
      }
      publishTaskId = completedTask.id;
      versions = await request('GET', `${versionsPath}?page=0&size=100`);
      publishedVersion = versions.content?.find((item) => item.version === '0.1.0') || null;
    }
    if (!publishedVersion || publishedVersion.status !== 'PUBLISHED') {
      throw new Error(`${scenario.name} did not produce an active published version`);
    }
    const submittedExecution = await request(
      'POST',
      `/workbench/skills/${skill.id}/executions`,
      {idempotencyKey: randomUUID(), input: scenario.liveInput},
    );
    publishedExecution = await waitFor(
      () => request(
        'GET',
        `/workbench/skills/${skill.id}/executions/${submittedExecution.id}`,
      ),
      ['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'WAITING_APPROVAL'],
      `${scenario.name} published execution`,
    );
    if (publishedExecution.status !== 'SUCCEEDED') {
      throw new Error(
        `${scenario.name} published execution failed: ${JSON.stringify(publishedExecution)}`,
      );
    }
  }

  return {
    skillId: skill.id,
    code: scenario.code,
    draftRevision: draft.revision,
    validation: 'VALID',
    mockTestRunId: completedMockRun.id,
    liveExecutionId: completedExecution.id,
    liveOutput: completedExecution.output,
    publishedVersionId: publishedVersion?.id ?? null,
    publishedVersion: publishedVersion?.version ?? null,
    publishTaskId,
    publishedExecutionId: publishedExecution?.id ?? null,
    publishedOutput: publishedExecution?.output ?? null,
  };
};

const server = await provisionMcpServer();
const skillsPage = await request('GET', '/workbench/skills?page=0&size=500');
const skills = skillsPage.content || [];
const results = [];
for (const scenario of scenarios) {
  results.push(await provisionSkill(scenario, server, skills));
}

process.stdout.write(`${JSON.stringify({
  mcpServer: {
    id: server.id,
    status: server.status,
    snapshotId: server.activeSnapshotId,
  },
  skills: results,
}, null, 2)}\n`);
