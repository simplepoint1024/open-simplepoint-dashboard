import { http, HttpResponse } from "msw";

const base = "/auditing/redis/entries";

type RedisEntryType = "STRING" | "HASH" | "LIST" | "SET" | "ZSET" | "STREAM" | "UNKNOWN";

type RedisEntryRecord = {
  key: string;
  type: RedisEntryType;
  ttlSeconds: number | null;
  persistent: boolean;
  value: string | Record<string, string> | string[] | Array<{ value: string; score: number }>;
};

let redisEntries: RedisEntryRecord[] = [
  {
    key: "simplepoint:gateway:rate-limit:service-rules",
    type: "STRING",
    ttlSeconds: null,
    persistent: true,
    value: JSON.stringify([{ id: "svc-auth", serviceId: "authorization", replenishRate: 10 }], null, 2),
  },
  {
    key: "simplepoint:gateway:rate-limit:endpoint-rules",
    type: "STRING",
    ttlSeconds: null,
    persistent: true,
    value: JSON.stringify([{ id: "ep-token", pathPattern: "/oauth2/token", requestedTokens: 1 }], null, 2),
  },
  {
    key: "spring:session:sessions:demo",
    type: "HASH",
    ttlSeconds: 1800,
    persistent: false,
    value: {
      creationTime: "1712100000000",
      maxInactiveInterval: "1800",
      principalName: "admin",
    },
  },
  {
    key: "audit:recent:errors",
    type: "LIST",
    ttlSeconds: 600,
    persistent: false,
    value: ["gateway timeout", "authorization failed", "amqp publish retried"],
  },
  {
    key: "tenant:enabled",
    type: "SET",
    ttlSeconds: null,
    persistent: true,
    value: ["tenant-demo", "tenant-channel", "tenant-labs"],
  },
  {
    key: "monitoring:latency:top",
    type: "ZSET",
    ttlSeconds: 3600,
    persistent: false,
    value: [
      { value: "authorization", score: 31.5 },
      { value: "common", score: 18.2 },
      { value: "auditing", score: 12.8 },
    ],
  },
];

const defaultPageSize = 10;

const serializeValue = (record: RedisEntryRecord) => {
  if (record.type === "STRING") {
    return typeof record.value === "string" ? record.value : JSON.stringify(record.value, null, 2);
  }
  return JSON.stringify(
    {
      sampled: false,
      size: Array.isArray(record.value)
        ? record.value.length
        : Object.keys(record.value).length,
      values: record.value,
    },
    null,
    2
  );
};

const previewValue = (record: RedisEntryRecord) => {
  const value = serializeValue(record);
  return value.length > 200 ? `${value.slice(0, 200)}...` : value;
};

const resolveSize = (record: RedisEntryRecord) => {
  if (record.type === "STRING") {
    return serializeValue(record).length;
  }
  return Array.isArray(record.value) ? record.value.length : Object.keys(record.value).length;
};

const buildSummary = (record: RedisEntryRecord) => ({
  key: record.key,
  type: record.type,
  ttlSeconds: record.persistent ? null : record.ttlSeconds,
  persistent: record.persistent,
  size: resolveSize(record),
  valuePreview: previewValue(record),
  editable: record.type === "STRING",
});

const buildDetail = (record: RedisEntryRecord) => ({
  key: record.key,
  type: record.type,
  ttlSeconds: record.persistent ? null : record.ttlSeconds,
  persistent: record.persistent,
  size: resolveSize(record),
  editable: record.type === "STRING",
  value: serializeValue(record),
});

const buildPage = <T,>(content: T[], pageNumber: number, pageSize: number, totalElements: number) => ({
  content,
  page: {
    size: pageSize,
    number: pageNumber,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / pageSize),
  },
});

const toPattern = (value: string) => {
  const escaped = value.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*");
  return new RegExp(`^${escaped}$`, "i");
};

export default [
  http.get(base, ({ request }) => {
    const url = new URL(request.url);
    const pageNumber = Number(url.searchParams.get("page") ?? "0");
    const pageSize = Number(url.searchParams.get("size") ?? `${defaultPageSize}`);
    const pattern = url.searchParams.get("pattern")?.trim() ?? "";
    const type = url.searchParams.get("type")?.trim().toUpperCase() ?? "";
    const matcher = pattern ? toPattern(pattern) : null;

    const filtered = redisEntries
      .filter((record) => (!matcher || matcher.test(record.key)) && (!type || record.type === type))
      .sort((left, right) => left.key.localeCompare(right.key))
      .map(buildSummary);

    const fromIndex = Math.max(pageNumber, 0) * Math.max(pageSize, 1);
    const toIndex = fromIndex + Math.max(pageSize, 1);
    return HttpResponse.json(buildPage(filtered.slice(fromIndex, toIndex), pageNumber, pageSize, filtered.length));
  }),
  http.get(`${base}/detail`, ({ request }) => {
    const key = new URL(request.url).searchParams.get("key") ?? "";
    const record = redisEntries.find((entry) => entry.key === key);
    if (!record) {
      return new HttpResponse(`Redis key does not exist: ${key}`, { status: 404 });
    }
    return HttpResponse.json(buildDetail(record));
  }),
  http.post(base, async ({ request }) => {
    const payload = await request.json() as Record<string, unknown>;
    const key = String(payload.key ?? "").trim();
    if (!key) {
      return new HttpResponse("Redis key must not be blank", { status: 400 });
    }
    if (redisEntries.some((entry) => entry.key === key)) {
      return new HttpResponse(`Redis key already exists: ${key}`, { status: 400 });
    }
    if (payload.value === undefined || payload.value === null) {
      return new HttpResponse("Redis value must not be null", { status: 400 });
    }
    const persistent = payload.persistent === true;
    const ttlSeconds = persistent ? null : Number(payload.ttlSeconds ?? 0);
    if (ttlSeconds !== null && (!Number.isFinite(ttlSeconds) || ttlSeconds <= 0)) {
      return new HttpResponse("ttlSeconds must be greater than 0 when persistent is false", { status: 400 });
    }
    const record: RedisEntryRecord = {
      key,
      type: "STRING",
      ttlSeconds,
      persistent,
      value: String(payload.value),
    };
    redisEntries = [record, ...redisEntries];
    return HttpResponse.json(buildDetail(record));
  }),
  http.put(base, async ({ request }) => {
    const payload = await request.json() as Record<string, unknown>;
    const key = String(payload.key ?? "").trim();
    const current = redisEntries.find((entry) => entry.key === key);
    if (!current) {
      return new HttpResponse(`Redis key does not exist: ${key}`, { status: 404 });
    }
    if (current.type !== "STRING") {
      return new HttpResponse(
        `Only string Redis keys can be edited through key-value operations: ${key}`,
        { status: 400 }
      );
    }
    if (payload.value === undefined || payload.value === null) {
      return new HttpResponse("Redis value must not be null", { status: 400 });
    }
    const persistent = payload.persistent === true;
    const ttlSeconds = persistent ? null : Number(payload.ttlSeconds ?? 0);
    if (ttlSeconds !== null && (!Number.isFinite(ttlSeconds) || ttlSeconds <= 0)) {
      return new HttpResponse("ttlSeconds must be greater than 0 when persistent is false", { status: 400 });
    }
    const nextRecord: RedisEntryRecord = {
      ...current,
      persistent,
      ttlSeconds,
      value: String(payload.value),
    };
    redisEntries = redisEntries.map((entry) => (entry.key === key ? nextRecord : entry));
    return HttpResponse.json(buildDetail(nextRecord));
  }),
  http.delete(base, ({ request }) => {
    const keys = new URL(request.url).searchParams.getAll("keys").map((key) => key.trim()).filter(Boolean);
    if (keys.length === 0) {
      return new HttpResponse("At least one redis key is required", { status: 400 });
    }
    const keySet = new Set(keys);
    redisEntries = redisEntries.filter((entry) => !keySet.has(entry.key));
    return HttpResponse.json(keys);
  }),
];
