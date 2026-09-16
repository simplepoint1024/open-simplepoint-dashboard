#!/usr/bin/env node

import {createServer} from 'node:http';

const host = process.env.SIMPLEPOINT_BUSINESS_MCP_HOST?.trim() || '127.0.0.1';
const port = Number.parseInt(process.env.SIMPLEPOINT_BUSINESS_MCP_PORT || '3099', 10);

const catalog = {
  'SP-LAPTOP-14': {name: '14 英寸商务笔记本', unitPrice: 6299, stock: 36},
  'SP-MONITOR-27': {name: '27 英寸 4K 显示器', unitPrice: 2399, stock: 18},
  'SP-DOCK-12': {name: '十二合一扩展坞', unitPrice: 699, stock: 120},
};

const calls = [];

const readOnlyAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};

const tools = [
  {
    name: 'lookup_inventory',
    title: '查询商品库存',
    description: '按 SKU 和需求数量查询上海仓实时可用库存及补货建议。',
    annotations: readOnlyAnnotations,
    inputSchema: {
      type: 'object',
      properties: {
        sku: {type: 'string', enum: Object.keys(catalog), description: '商品 SKU'},
        quantity: {type: 'integer', minimum: 1, maximum: 500, description: '需求数量'},
      },
      required: ['sku', 'quantity'],
      additionalProperties: false,
    },
    outputSchema: {
      type: 'object',
      properties: {
        sku: {type: 'string'},
        productName: {type: 'string'},
        warehouse: {type: 'string'},
        requestedQuantity: {type: 'integer'},
        availableQuantity: {type: 'integer'},
        fulfillable: {type: 'boolean'},
        replenishmentAdvice: {type: 'string'},
      },
      required: [
        'sku',
        'productName',
        'warehouse',
        'requestedQuantity',
        'availableQuantity',
        'fulfillable',
        'replenishmentAdvice',
      ],
      additionalProperties: false,
    },
  },
  {
    name: 'calculate_order_quote',
    title: '计算订单报价',
    description: '根据 SKU、数量、目的省份和配送时效计算含税订单报价。',
    annotations: readOnlyAnnotations,
    inputSchema: {
      type: 'object',
      properties: {
        sku: {type: 'string', enum: Object.keys(catalog), description: '商品 SKU'},
        quantity: {type: 'integer', minimum: 1, maximum: 100, description: '订购数量'},
        destinationProvince: {type: 'string', minLength: 2, maxLength: 16, description: '收货省份'},
        shippingSpeed: {
          type: 'string',
          enum: ['STANDARD', 'EXPRESS'],
          description: 'STANDARD 标准达或 EXPRESS 次日达',
        },
      },
      required: ['sku', 'quantity', 'destinationProvince', 'shippingSpeed'],
      additionalProperties: false,
    },
    outputSchema: {
      type: 'object',
      properties: {
        quoteNumber: {type: 'string'},
        productName: {type: 'string'},
        subtotal: {type: 'number'},
        freight: {type: 'number'},
        tax: {type: 'number'},
        total: {type: 'number'},
        currency: {type: 'string'},
        estimatedDeliveryDays: {type: 'integer'},
      },
      required: [
        'quoteNumber',
        'productName',
        'subtotal',
        'freight',
        'tax',
        'total',
        'currency',
        'estimatedDeliveryDays',
      ],
      additionalProperties: false,
    },
  },
  {
    name: 'triage_service_incident',
    title: '分诊服务故障',
    description: '根据影响范围、服务等级和故障描述生成处理队列、响应 SLA 与升级建议。',
    annotations: readOnlyAnnotations,
    inputSchema: {
      type: 'object',
      properties: {
        service: {type: 'string', minLength: 2, maxLength: 64, description: '受影响服务'},
        impact: {
          type: 'string',
          enum: ['SINGLE_USER', 'MULTIPLE_USERS', 'TENANT_WIDE', 'PLATFORM_WIDE'],
          description: '故障影响范围',
        },
        customerTier: {
          type: 'string',
          enum: ['STANDARD', 'PREMIUM'],
          description: '客户服务等级',
        },
        summary: {type: 'string', minLength: 6, maxLength: 500, description: '故障摘要'},
      },
      required: ['service', 'impact', 'customerTier', 'summary'],
      additionalProperties: false,
    },
    outputSchema: {
      type: 'object',
      properties: {
        incidentKey: {type: 'string'},
        priority: {type: 'string'},
        queue: {type: 'string'},
        responseSlaMinutes: {type: 'integer'},
        escalationRequired: {type: 'boolean'},
        recommendedAction: {type: 'string'},
      },
      required: [
        'incidentKey',
        'priority',
        'queue',
        'responseSlaMinutes',
        'escalationRequired',
        'recommendedAction',
      ],
      additionalProperties: false,
    },
  },
];

const json = (response, status, body) => {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  });
  response.end(payload);
};

const textResult = (structuredContent) => ({
  content: [{type: 'text', text: JSON.stringify(structuredContent, null, 2)}],
  isError: false,
  structuredContent,
});

const requireCatalogItem = (sku) => {
  const item = catalog[sku];
  if (!item) throw new Error(`未知 SKU：${sku}`);
  return item;
};

const requireInteger = (value, name, minimum, maximum) => {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} 必须是 ${minimum} 到 ${maximum} 之间的整数`);
  }
  return value;
};

const executeTool = (name, args) => {
  if (name === 'lookup_inventory') {
    const item = requireCatalogItem(args.sku);
    const quantity = requireInteger(args.quantity, 'quantity', 1, 500);
    return {
      sku: args.sku,
      productName: item.name,
      warehouse: '上海中心仓',
      requestedQuantity: quantity,
      availableQuantity: item.stock,
      fulfillable: item.stock >= quantity,
      replenishmentAdvice: item.stock >= quantity
        ? '库存充足，可直接锁定库存'
        : `当前缺口 ${quantity - item.stock} 件，建议拆单或发起补货`,
    };
  }

  if (name === 'calculate_order_quote') {
    const item = requireCatalogItem(args.sku);
    const quantity = requireInteger(args.quantity, 'quantity', 1, 100);
    if (!['STANDARD', 'EXPRESS'].includes(args.shippingSpeed)) {
      throw new Error('shippingSpeed 仅支持 STANDARD 或 EXPRESS');
    }
    if (typeof args.destinationProvince !== 'string' || args.destinationProvince.trim().length < 2) {
      throw new Error('destinationProvince 至少需要 2 个字符');
    }
    const subtotal = item.unitPrice * quantity;
    const remoteProvince = ['新疆', '西藏', '青海', '宁夏', '内蒙古'].includes(
      args.destinationProvince.trim(),
    );
    const freightBase = args.shippingSpeed === 'EXPRESS' ? 36 : 18;
    const freight = freightBase + (remoteProvince ? 28 : 0) + Math.max(0, quantity - 1) * 4;
    const tax = Number((subtotal * 0.13).toFixed(2));
    const total = Number((subtotal + freight + tax).toFixed(2));
    return {
      quoteNumber: `Q-${args.sku.replaceAll('-', '')}-${quantity}-${args.shippingSpeed === 'EXPRESS' ? 'E' : 'S'}`,
      productName: item.name,
      subtotal,
      freight,
      tax,
      total,
      currency: 'CNY',
      estimatedDeliveryDays: args.shippingSpeed === 'EXPRESS'
        ? (remoteProvince ? 3 : 1)
        : (remoteProvince ? 7 : 3),
    };
  }

  if (name === 'triage_service_incident') {
    const impacts = ['SINGLE_USER', 'MULTIPLE_USERS', 'TENANT_WIDE', 'PLATFORM_WIDE'];
    if (!impacts.includes(args.impact)) throw new Error('impact 不在支持范围内');
    if (!['STANDARD', 'PREMIUM'].includes(args.customerTier)) {
      throw new Error('customerTier 仅支持 STANDARD 或 PREMIUM');
    }
    if (typeof args.service !== 'string' || args.service.trim().length < 2) {
      throw new Error('service 至少需要 2 个字符');
    }
    if (typeof args.summary !== 'string' || args.summary.trim().length < 6) {
      throw new Error('summary 至少需要 6 个字符');
    }
    const impactScore = impacts.indexOf(args.impact);
    const premium = args.customerTier === 'PREMIUM';
    const priority = impactScore === 3
      ? 'P1'
      : impactScore === 2 || (impactScore === 1 && premium)
        ? 'P2'
        : impactScore === 1 || premium
          ? 'P3'
          : 'P4';
    const responseSlaMinutes = {P1: 15, P2: 30, P3: 120, P4: 480}[priority];
    const incidentKey = `INC-${String(calls.length + 1).padStart(4, '0')}`;
    return {
      incidentKey,
      priority,
      queue: priority === 'P1' ? '平台 SRE 应急队列' : `${args.service.trim()} 服务支持队列`,
      responseSlaMinutes,
      escalationRequired: priority === 'P1' || priority === 'P2',
      recommendedAction: priority === 'P1'
        ? '立即建立事故群、冻结相关发布并通知值班负责人'
        : '收集请求 ID 与时间窗口，交由服务负责人按 SLA 排查',
    };
  }

  throw new Error(`未知工具：${name}`);
};

const handleMcpRequest = (request) => {
  const {id, method, params = {}} = request;
  if (method === 'initialize') {
    return {
      jsonrpc: '2.0',
      id,
      result: {
        protocolVersion: '2025-11-25',
        capabilities: {tools: {listChanged: false}},
        serverInfo: {
          name: 'simplepoint-local-business-operations',
          title: 'SimplePoint 本地业务运营工具',
          version: '1.0.0',
        },
      },
    };
  }
  if (method === 'tools/list') {
    return {jsonrpc: '2.0', id, result: {tools}};
  }
  if (method === 'tools/call') {
    const startedAt = new Date().toISOString();
    try {
      const output = executeTool(params.name, params.arguments || {});
      calls.push({startedAt, tool: params.name, arguments: params.arguments || {}, output, success: true});
      return {jsonrpc: '2.0', id, result: textResult(output)};
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      calls.push({startedAt, tool: params.name, arguments: params.arguments || {}, error: message, success: false});
      return {
        jsonrpc: '2.0',
        id,
        result: {content: [{type: 'text', text: message}], isError: true},
      };
    }
  }
  return {
    jsonrpc: '2.0',
    id,
    error: {code: -32601, message: `Method not found: ${method}`},
  };
};

const server = createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    json(response, 200, {status: 'UP', server: 'simplepoint-local-business-operations'});
    return;
  }
  if (request.method === 'GET' && request.url === '/calls') {
    json(response, 200, {count: calls.length, calls});
    return;
  }
  if (request.method !== 'POST' || request.url !== '/mcp') {
    response.writeHead(404).end();
    return;
  }

  let body = '';
  request.setEncoding('utf8');
  request.on('data', (chunk) => {
    body += chunk;
    if (body.length > 1024 * 1024) request.destroy();
  });
  request.on('end', () => {
    try {
      const payload = JSON.parse(body);
      if (payload.method === 'notifications/initialized') {
        response.writeHead(202).end();
        return;
      }
      json(response, 200, handleMcpRequest(payload));
    } catch (error) {
      json(response, 400, {
        jsonrpc: '2.0',
        id: null,
        error: {code: -32700, message: error instanceof Error ? error.message : 'Invalid JSON'},
      });
    }
  });
});

server.listen(port, host, () => {
  process.stdout.write(`SimplePoint business MCP listening on http://${host}:${port}/mcp\n`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
