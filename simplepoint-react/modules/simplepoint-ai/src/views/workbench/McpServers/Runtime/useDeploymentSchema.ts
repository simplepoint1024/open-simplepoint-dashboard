import {useMemo} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {RJSFSchema} from '@rjsf/utils';
import {managedServerFilters, enabledSecretFilters} from './runtimeQueries';
import type {RuntimeServer} from './types';

/** Pool and one-off workloads share the same validated resource and authorized selector fields. */
export function useDeploymentSchema({serversUrl, secretsUrl, fixedServer, pool, networkMode}: {
  serversUrl: string; secretsUrl: string; fixedServer?: RuntimeServer; pool: boolean; networkMode?: string;
}) {
  const {t} = useI18n();
  return useMemo<RJSFSchema>(() => {
    const properties: Record<string, RJSFSchema> = {
      serverId: {type: 'string', minLength: 1, title: t('ai.runtime.field.server', 'MCP Server'),
        ...(fixedServer ? {oneOf: [{const: fixedServer.id, title: fixedServer.name || fixedServer.code || fixedServer.id}]}
          : {'x-ui': {widget: 'EntitySelect', options: {endpoint: serversUrl, filters: managedServerFilters}}})},
      imageReference: {type: 'string', minLength: 1, maxLength: 440, title: t('ai.runtime.field.imageReference', 'OCI 镜像')},
      imageDigest: {type: 'string', pattern: '^(?:sha256:[a-fA-F0-9]{64})?$', title: t('ai.runtime.field.imageDigest', '镜像 Digest'),
        description: t('ai.runtime.imageDigest.help', '可选；留空时服务端会拉取镜像 Tag，并自动保存解析出的 Digest。')},
      memoryMiB: {type: 'integer', minimum: 32, maximum: 16384, title: t('ai.runtime.field.memory', '内存（MiB）')},
      cpuCores: {type: 'number', minimum: 0.01, maximum: 16, title: t('ai.runtime.field.cpu', 'CPU（核）')},
      pidsLimit: {type: 'integer', minimum: 1, maximum: 4096, title: t('ai.runtime.field.pids', 'PID 上限')},
      networkMode: {type: 'string', title: t('ai.runtime.field.networkMode', '网络模式'), oneOf: [
        {const: 'none', title: t('ai.runtime.network.none', '禁止网络')},
        {const: 'bridge', title: t('ai.runtime.network.bridge', 'Bridge 网络')},
        {const: 'egress', title: t('ai.runtime.network.egress', '受控出口')},
      ]},
      secretIds: {type: 'array', maxItems: 16, uniqueItems: true, items: {type: 'string'},
        title: t('ai.runtime.field.secrets', 'Runtime Secrets'),
        'x-ui': {widget: 'EntitySelect', options: {endpoint: secretsUrl, filters: enabledSecretFilters, multiple: true}}},
    };
    if (networkMode === 'egress') properties.egressAllowlist = {type: 'string', minLength: 1,
      title: t('ai.runtime.field.egressAllowlist', '出口域名白名单'),
      description: t('ai.runtime.egress.help', '使用换行、空格或逗号分隔域名；支持 *.example.com。'),
      'x-ui': {widget: 'textarea'}};
    const required = ['serverId', 'imageReference', 'memoryMiB', 'cpuCores', 'pidsLimit', 'networkMode'];
    if (networkMode === 'egress') required.push('egressAllowlist');
    if (pool) {
      properties.code = {type: 'string', pattern: '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$', title: t('ai.runtime.field.code', '编码')};
      properties.name = {type: 'string', minLength: 1, maxLength: 128, title: t('ai.runtime.field.name', '名称')};
      properties.minReplicas = {type: 'integer', minimum: 0, maximum: 256, title: t('ai.runtime.field.minReplicas', '最小副本')};
      properties.maxReplicas = {type: 'integer', minimum: 1, maximum: 256, title: t('ai.runtime.field.maxReplicas', '最大副本')};
      properties.desiredReplicas = {type: 'integer', minimum: 0, maximum: 256, title: t('ai.runtime.field.desiredReplicas', '目标副本')};
      properties.activationReplicas = {type: 'integer', minimum: 1, maximum: 256, title: t('ai.runtime.field.activationReplicas', '激活副本')};
      properties.prewarmNodes = {type: 'integer', minimum: 0, maximum: 256, title: t('ai.runtime.field.prewarmNodes', '预热节点')};
      properties.idleTimeoutSeconds = {type: 'integer', minimum: 0, maximum: 86400, title: t('ai.runtime.field.idleTimeout', '空闲回收（秒）'),
        description: t('ai.runtime.idleTimeout.help', '0 表示不按空闲时间回收。')};
      properties.replicaLifetimeSeconds = {type: 'integer', minimum: 60, maximum: 86400, title: t('ai.runtime.field.replicaLifetime', '副本寿命（秒）')};
      required.push('code', 'name', 'minReplicas', 'maxReplicas', 'desiredReplicas', 'activationReplicas', 'prewarmNodes',
        'idleTimeoutSeconds', 'replicaLifetimeSeconds');
    } else {
      properties.executionId = {type: 'string', maxLength: 64, title: t('ai.runtime.field.executionId', 'Execution ID'),
        description: t('ai.runtime.executionId.help', '留空时由平台生成。')};
      properties.timeoutSeconds = {type: 'integer', minimum: 1, maximum: 86400, title: t('ai.runtime.field.timeout', '超时（秒）')};
      required.push('timeoutSeconds');
    }
    return {type: 'object', required, properties};
  }, [serversUrl, secretsUrl, fixedServer, pool, networkMode, t]);
}
