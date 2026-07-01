import { http, HttpResponse } from 'msw';

const base = '/common/platform/object-storage';

type ObjectStorageObject = {
  id: string;
  providerCode: string;
  providerType: string;
  bucket: string;
  objectKey: string;
  originalFileName: string;
  contentType: string;
  contentLength: number;
  eTag: string;
  accessUrl: string | null;
  sourceServiceName: string;
  createdAt: string;
};

type ObjectStorageQuota = {
  id: string;
  tenantId: string;
  tenantName: string;
  quotaBytes: number | null;
  usedBytes: number;
  remainingBytes: number | null;
  enabled: boolean;
  description: string | null;
};

const providers = [
  {
    code: 'minio-primary',
    name: 'MinIO Primary',
    type: 'MINIO',
    endpoint: 'http://127.0.0.1:9000',
    bucket: 'common-assets',
    defaultProvider: true,
  },
  {
    code: 'ceph-archive',
    name: 'Ceph Archive',
    type: 'CEPH',
    endpoint: 'https://ceph.example.com',
    bucket: 'archive-assets',
    defaultProvider: false,
  },
];

let objects: ObjectStorageObject[] = [
  {
    id: 'obj-1',
    providerCode: 'minio-primary',
    providerType: 'MINIO',
    bucket: 'common-assets',
    objectKey: 'tenant-demo/2026/04/03/8c6d-avatar-demo.png',
    originalFileName: 'avatar-demo.png',
    contentType: 'image/png',
    contentLength: 345678,
    eTag: 'etag-demo-1',
    accessUrl: 'http://127.0.0.1:9000/common-assets/tenant-demo/2026/04/03/8c6d-avatar-demo.png',
    sourceServiceName: 'common',
    createdAt: new Date().toISOString(),
  },
  {
    id: 'obj-2',
    providerCode: 'ceph-archive',
    providerType: 'CEPH',
    bucket: 'archive-assets',
    objectKey: 'tenant-labs/2026/04/01/01-contract.pdf',
    originalFileName: 'contract.pdf',
    contentType: 'application/pdf',
    contentLength: 897654,
    eTag: 'etag-demo-2',
    accessUrl: null,
    sourceServiceName: 'auditing',
    createdAt: new Date(Date.now() - 86400000).toISOString(),
  },
];

let quotas: ObjectStorageQuota[] = [
  {
    id: 'quota-1',
    tenantId: 'tenant-demo',
    tenantName: 'Demo Tenant',
    quotaBytes: 1024 * 1024 * 1024,
    usedBytes: 345678,
    remainingBytes: 1024 * 1024 * 1024 - 345678,
    enabled: true,
    description: '默认演示租户配额',
  },
  {
    id: 'quota-2',
    tenantId: 'tenant-labs',
    tenantName: 'Labs Tenant',
    quotaBytes: null,
    usedBytes: 897654,
    remainingBytes: null,
    enabled: true,
    description: '实验租户不限额',
  },
];

const buildPage = <T,>(content: T[], pageNumber: number, pageSize: number, totalElements: number) => ({
  content,
  page: {
    size: pageSize,
    number: pageNumber,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / pageSize),
  },
});

const paginate = <T,>(items: T[], pageNumber: number, pageSize: number) => {
  const from = Math.max(pageNumber, 0) * Math.max(pageSize, 1);
  const to = from + Math.max(pageSize, 1);
  return buildPage(items.slice(from, to), pageNumber, pageSize, items.length);
};

export default [
  http.get(`${base}/providers`, () => HttpResponse.json(providers)),
  http.get(`${base}/objects`, ({ request }) => {
    const url = new URL(request.url);
    const providerCode = url.searchParams.get('providerCode')?.trim() ?? '';
    const originalFileName = url.searchParams.get('originalFileName')?.trim().toLowerCase() ?? '';
    const pageNumber = Number(url.searchParams.get('page') ?? '0');
    const pageSize = Number(url.searchParams.get('size') ?? '10');
    const filtered = objects
      .filter((item) => (!providerCode || item.providerCode === providerCode)
        && (!originalFileName || item.originalFileName.toLowerCase().includes(originalFileName)))
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
    return HttpResponse.json(paginate(filtered, pageNumber, pageSize));
  }),
  http.get(`${base}/objects/:id`, ({ params }) => {
    const object = objects.find((item) => item.id === params.id);
    if (!object) {
      return new HttpResponse(`Object does not exist: ${params.id}`, { status: 404 });
    }
    return HttpResponse.json(object);
  }),
  http.get(`${base}/objects/:id/content`, ({ params }) => {
    const object = objects.find((item) => item.id === params.id);
    if (!object) {
      return new HttpResponse(`Object does not exist: ${params.id}`, { status: 404 });
    }
    return new HttpResponse(`mock content for ${object.originalFileName}`, {
      headers: {
        'Content-Type': object.contentType || 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${object.originalFileName}"`,
      },
    });
  }),
  http.post(`${base}/objects/upload`, async ({ request }) => {
    const formData = await request.formData();
    const file = formData.get('file');
    if (!(file instanceof File)) {
      return new HttpResponse('file is required', { status: 400 });
    }
    const providerCode = String(formData.get('providerCode') || providers[0]?.code || '').trim();
    const provider = providers.find((item) => item.code === providerCode);
    if (!provider) {
      return new HttpResponse(`Provider does not exist: ${providerCode}`, { status: 400 });
    }
    const directory = String(formData.get('directory') || '').trim();
    const object = {
      id: `obj-${Date.now()}`,
      providerCode: provider.code,
      providerType: provider.type,
      bucket: provider.bucket,
      objectKey: `${directory ? `${directory}/` : ''}tenant-demo/${Date.now()}-${file.name}`,
      originalFileName: file.name,
      contentType: file.type || 'application/octet-stream',
      contentLength: file.size,
      eTag: `etag-${Date.now()}`,
      accessUrl: provider.endpoint ? `${provider.endpoint.replace(/\/$/, '')}/${provider.bucket}/${file.name}` : null,
      sourceServiceName: 'common',
      createdAt: new Date().toISOString(),
    };
    objects = [object, ...objects];
    quotas = quotas.map((quota) => {
      if (quota.tenantId !== 'tenant-demo') {
        return quota;
      }
      const usedBytes = (quota.usedBytes ?? 0) + file.size;
      return {
        ...quota,
        usedBytes,
        remainingBytes: quota.quotaBytes == null ? null : Math.max(quota.quotaBytes - usedBytes, 0),
      };
    });
    return HttpResponse.json(object);
  }),
  http.delete(`${base}/objects`, ({ request }) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '')
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean);
    if (ids.length === 0) {
      return new HttpResponse('ids is required', { status: 400 });
    }
    const deleted = objects.filter((item) => ids.includes(item.id));
    const deletedBytes = deleted.reduce((sum, item) => sum + (item.contentLength ?? 0), 0);
    objects = objects.filter((item) => !ids.includes(item.id));
    quotas = quotas.map((quota) => {
      if (quota.tenantId !== 'tenant-demo') {
        return quota;
      }
      const usedBytes = Math.max((quota.usedBytes ?? 0) - deletedBytes, 0);
      return {
        ...quota,
        usedBytes,
        remainingBytes: quota.quotaBytes == null ? null : Math.max(quota.quotaBytes - usedBytes, 0),
      };
    });
    return HttpResponse.json(ids);
  }),
  http.get(`${base}/quotas`, ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId')?.trim().toLowerCase() ?? '';
    const pageNumber = Number(url.searchParams.get('page') ?? '0');
    const pageSize = Number(url.searchParams.get('size') ?? '10');
    const filtered = quotas.filter((item) => !tenantId || item.tenantId.toLowerCase().includes(tenantId));
    return HttpResponse.json(paginate(filtered, pageNumber, pageSize));
  }),
  http.post(`${base}/quotas`, async ({ request }) => {
    const payload = await request.json() as Record<string, unknown>;
    const tenantId = String(payload.tenantId || '').trim();
    if (!tenantId) {
      return new HttpResponse('tenantId is required', { status: 400 });
    }
    if (quotas.some((item) => item.tenantId === tenantId)) {
      return new HttpResponse(`Quota already exists for tenant: ${tenantId}`, { status: 400 });
    }
    const quota = {
      id: `quota-${Date.now()}`,
      tenantId,
      tenantName: tenantId,
      quotaBytes: payload.quotaBytes == null ? null : Number(payload.quotaBytes),
      usedBytes: 0,
      remainingBytes: payload.quotaBytes == null ? null : Number(payload.quotaBytes),
      enabled: payload.enabled !== false,
      description: payload.description == null ? null : String(payload.description),
    };
    quotas = [quota, ...quotas];
    return HttpResponse.json(quota);
  }),
  http.put(`${base}/quotas`, async ({ request }) => {
    const payload = await request.json() as Record<string, unknown>;
    const id = String(payload.id || '').trim();
    const current = quotas.find((item) => item.id === id);
    if (!current) {
      return new HttpResponse(`Quota does not exist: ${id}`, { status: 404 });
    }
    const next = {
      ...current,
      tenantId: String(payload.tenantId || current.tenantId),
      quotaBytes: payload.quotaBytes == null ? null : Number(payload.quotaBytes),
      enabled: payload.enabled !== false,
      description: payload.description == null ? null : String(payload.description),
    };
    next.remainingBytes = next.quotaBytes == null ? null : Math.max(next.quotaBytes - (next.usedBytes ?? 0), 0);
    quotas = quotas.map((item) => (item.id === id ? next : item));
    return HttpResponse.json(next);
  }),
  http.delete(`${base}/quotas`, ({ request }) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '')
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean);
    if (ids.length === 0) {
      return new HttpResponse('ids is required', { status: 400 });
    }
    quotas = quotas.filter((item) => !ids.includes(item.id));
    return HttpResponse.json(ids);
  }),
];
