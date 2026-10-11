type Schema = {properties?: Record<string, any>; required?: string[]; [key: string]: any};
type Translate = (key: string, fallback?: string) => string;
export const MB = 1024 * 1024;
export const quotaMb = (bytes: unknown) => typeof bytes === 'number' ? Number((bytes / MB).toFixed(2)) : undefined;
export const writable = (schema: Schema | undefined, field: string) => !!schema?.properties?.[field]
  && schema.properties[field].readOnly !== true;
const providerFields = ['name', 'code', 'type', 'endpoint', 'region', 'accessKey', 'secretKey', 'bucket',
  'basePath', 'pathStyleAccess', 'publicBaseUrl', 'enabled', 'defaultProvider', 'description'];
const providerRequired = ['name', 'code', 'type', 'accessKey', 'bucket'];
export const providerCanCreate = (schema?: Schema) => [...providerRequired, 'secretKey']
  .every(field => writable(schema, field));
const select = (schema: Schema, fields: string[]): Schema => {
  const result = structuredClone(schema);
  result.properties = Object.fromEntries(fields.filter(field => writable(schema, field))
    .map(field => [field, result.properties![field]]));
  result.required = (result.required ?? []).filter(field => field in result.properties!);
  return result;
};

/** Only server-declared writable fields become editable; ciphertext is never a client field. */
export function providerFormSchema(schema: Schema, editing: Record<string, any> | null, t: Translate): Schema {
  const result = select(schema, providerFields);
  result.required = providerRequired.filter(field => writable(schema, field));
  if (!editing && writable(schema, 'secretKey')) result.required.push('secretKey');
  const maxLengths: Record<string, number> = {name: 128, code: 64, endpoint: 2048, region: 128,
    accessKey: 512, bucket: 255, basePath: 512, publicBaseUrl: 2048, description: 512};
  Object.entries(result.properties!).forEach(([field, property], index) => {
    property['x-order'] = index;
    if (maxLengths[field]) property.maxLength = maxLengths[field];
    if (result.required!.includes(field) && field !== 'type') {
      property.type = 'string'; property.minLength = 1; property.pattern = '\\S';
    }
  });
  const p = result.properties!;
  if (p.code) {p.code.pattern = '^[a-z0-9][a-z0-9._-]{1,63}$'; if (editing) p.code.readOnly = true;}
  if (p.type && Array.isArray(p.type.enum)) {
    const labels: Record<string, string> = {
      MINIO: t('storage.provider.type.MINIO', 'MinIO'), S3: t('storage.provider.type.S3', 'AWS S3'),
      ALIYUN_OSS: t('storage.provider.type.ALIYUN_OSS', 'Aliyun OSS'),
      TENCENT_COS: t('storage.provider.type.TENCENT_COS', 'Tencent COS'),
      QINIU_KODO: t('storage.provider.type.QINIU_KODO', 'Qiniu Kodo'), CEPH: t('storage.provider.type.CEPH', 'Ceph'),
    };
    p.type.oneOf = p.type.enum.map((value: string) => ({const: value, title: labels[value] ?? value}));
    delete p.type.enum;
  }
  if (p.accessKey) p.accessKey.title = t('storage.provider.accessKey', 'Access Key');
  if (p.secretKey) {
    p.secretKey.title = t('storage.provider.secretKey', 'Secret Key');
    p.secretKey['x-ui'] = {widget: 'password'};
    if (editing) p.secretKey.description = t('storage.provider.secretKeep', '留空表示保持现有凭证不变');
  }
  if (p.description) p.description['x-ui'] = {widget: 'textarea'};
  return result;
}

export function providerPayload(schema: Schema, data: Record<string, any>, editing: Record<string, any> | null) {
  const payload: Record<string, any> = {};
  for (const field of providerFields) if (writable(schema, field) && field in data) {
    payload[field] = typeof data[field] === 'string' ? data[field].trim() : data[field];
  }
  if (editing) payload.id = editing.id;
  // Blank optional values clear their configuration; a blank secret explicitly preserves credentials.
  for (const field of ['endpoint', 'basePath', 'publicBaseUrl', 'description', 'secretKey']) {
    if (writable(schema, field)) payload[field] = payload[field] || null;
  }
  if (writable(schema, 'region')) payload.region = payload.region || 'us-east-1';
  return payload;
}

/** MB is a presentation field inheriting quotaBytes permissions, not a second quota contract. */
export function quotaFormSchema(schema: Schema, editing: Record<string, any> | null, t: Translate): Schema {
  const result = select(schema, ['tenantId', 'quotaBytes', 'enabled', 'description']);
  const p = result.properties!;
  if (p.tenantId) {p.tenantId.type = 'string'; p.tenantId.minLength = 1; p.tenantId['x-order'] = 0;}
  result.required = writable(schema, 'tenantId') ? ['tenantId'] : [];
  if (p.quotaBytes) {
    const original = p.quotaBytes; delete p.quotaBytes;
    p.quotaMb = {...original, type: 'number', minimum: 0, maximum: Math.floor(Number.MAX_SAFE_INTEGER / MB),
      default: quotaMb(editing?.quotaBytes), title: t('storage.quota.limitMb', '配额（MB，留空表示不限额）'), 'x-order': 1};
    delete p.quotaMb.format;
  }
  if (p.enabled) p.enabled['x-order'] = 2;
  if (p.description) {p.description.maxLength = 512; p.description['x-ui'] = {widget: 'textarea'}; p.description['x-order'] = 3;}
  return result;
}

export function quotaPayload(schema: Schema, data: Record<string, any>, editing: Record<string, any> | null) {
  const payload: Record<string, any> = {};
  for (const field of ['tenantId', 'enabled', 'description']) if (writable(schema, field) && field in data) {
    payload[field] = typeof data[field] === 'string' ? data[field].trim() : data[field];
  }
  if (editing) payload.id = editing.id;
  if (writable(schema, 'quotaBytes')) {
    // Merely editing a description must not round an existing byte-exact limit to two decimal MB.
    payload.quotaBytes = editing && data.quotaMb === quotaMb(editing.quotaBytes)
      ? editing.quotaBytes : data.quotaMb == null ? null : Math.round(data.quotaMb * MB);
    if (payload.quotaBytes != null && (!Number.isSafeInteger(payload.quotaBytes) || payload.quotaBytes < 0)) {
      throw new RangeError('QUOTA_RANGE_EXCEEDED');
    }
  }
  if (writable(schema, 'description')) payload.description = payload.description || null;
  return payload;
}
