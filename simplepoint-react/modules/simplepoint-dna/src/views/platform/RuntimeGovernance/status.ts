import type {StatusPresentation} from '@simplepoint/components/StatusTag';

export function runtimeStatus(value: string | undefined, t: (key: string, fallback?: string) => string): StatusPresentation {
  const states: Record<string, StatusPresentation> = {
    DRAFT: {label: t('dna.runtime.state.DRAFT', '草稿'), tone: 'neutral'},
    VALIDATING: {label: t('dna.runtime.state.VALIDATING', '校验中'), tone: 'processing'},
    READY: {label: t('dna.runtime.state.READY', '就绪'), tone: 'neutral'},
    MIGRATING: {label: t('dna.runtime.state.MIGRATING', '迁移中'), tone: 'processing'},
    VERIFYING: {label: t('dna.runtime.state.VERIFYING', '验证中'), tone: 'processing'},
    ACTIVE: {label: t('dna.runtime.state.ACTIVE', '已生效'), tone: 'success'},
    FAILED: {label: t('dna.runtime.state.FAILED', '失败'), tone: 'error'},
    RETIRED: {label: t('dna.runtime.state.RETIRED', '已停用'), tone: 'neutral'},
    PENDING: {label: t('dna.runtime.state.PENDING', '待处理'), tone: 'neutral'},
    RUNNING: {label: t('dna.runtime.state.RUNNING', '进行中'), tone: 'processing'},
    SUCCEEDED: {label: t('dna.runtime.state.SUCCEEDED', '成功'), tone: 'success'},
    SKIPPED: {label: t('dna.runtime.state.SKIPPED', '已跳过'), tone: 'neutral'},
    REJECTED: {label: t('dna.runtime.state.REJECTED', '已拒绝'), tone: 'error'},
    SAFE: {label: t('dna.runtime.state.SAFE', '安全'), tone: 'success'},
    CONDITIONAL: {label: t('dna.runtime.state.CONDITIONAL', '需满足条件'), tone: 'warning'},
    DESTRUCTIVE: {label: t('dna.runtime.state.DESTRUCTIVE', '破坏性变更'), tone: 'error'},
  };
  return states[value ?? ''] ?? {label: value || '—', tone: 'neutral'};
}
