import { http, HttpResponse } from "msw";
import {createRedisSnapshotFixture, redisSchema} from './queryFixture';

const base = "/auditing/redis/entries";
const maxValueBytes = 262144;
const exceedsValueLimit = (value: unknown) => new TextEncoder().encode(String(value)).length > maxValueBytes;

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

const resolveSize = (record: RedisEntryRecord) => {
  if (record.type === "STRING") {
    return new TextEncoder().encode(serializeValue(record)).length;
  }
  return Array.isArray(record.value) ? record.value.length : Object.keys(record.value).length;
};

const buildSummary = (record: RedisEntryRecord) => ({
  key: record.key,
  type: record.type,
  ttlSeconds: record.persistent ? null : record.ttlSeconds,
  persistent: record.persistent,
  size: resolveSize(record),
  editable: record.type === "STRING",
  observedAt: new Date().toISOString(),
});

const buildDetail = (record: RedisEntryRecord) => ({
  key: record.key,
  type: record.type,
  ttlSeconds: record.persistent ? null : record.ttlSeconds,
  persistent: record.persistent,
  size: resolveSize(record),
  editable: record.type === "STRING",
  value: serializeValue(record),
  valueEncoding: record.type === 'STRING' ? 'UTF-8' : 'json',
  sampled: false,
  truncated: false,
});

const snapshots = createRedisSnapshotFixture(() => redisEntries.map(buildSummary));

export default [
  http.get(`${base}/query/schema`, () => HttpResponse.json(redisSchema)),
  http.get(`${base}/query`, ({request}) => {
    try {
      return HttpResponse.json(snapshots.query(new URL(request.url).searchParams));
    } catch {
      return HttpResponse.json({code: 'INVALID_QUERY', message: '筛选条件无效'}, {status: 400});
    }
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
    const key = String(payload.key ?? "");
    if (!key) {
      return new HttpResponse("Redis key must not be blank", { status: 400 });
    }
    if (redisEntries.some((entry) => entry.key === key)) {
      return new HttpResponse(`Redis key already exists: ${key}`, { status: 400 });
    }
    if (payload.value === undefined || payload.value === null) {
      return new HttpResponse("Redis value must not be null", { status: 400 });
    }
    if (exceedsValueLimit(payload.value)) {
      return new HttpResponse("Redis value exceeds the editing limit of 262144 UTF-8 bytes", {status: 400});
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
    snapshots.invalidate();
    return HttpResponse.json(buildDetail(record));
  }),
  http.put(base, async ({ request }) => {
    const payload = await request.json() as Record<string, unknown>;
    const key = String(payload.key ?? "");
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
    if (exceedsValueLimit(payload.value)) {
      return new HttpResponse("Redis value exceeds the editing limit of 262144 UTF-8 bytes", {status: 400});
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
    snapshots.invalidate();
    return HttpResponse.json(buildDetail(nextRecord));
  }),
  http.delete(base, ({ request }) => {
    const keys = new URL(request.url).searchParams.getAll("keys");
    if (keys.length === 0 || keys.some(key => key.length === 0)) {
      return new HttpResponse("At least one redis key is required", { status: 400 });
    }
    const keySet = new Set(keys);
    redisEntries = redisEntries.filter((entry) => !keySet.has(entry.key));
    snapshots.invalidate();
    return HttpResponse.json(keys);
  }),
];
