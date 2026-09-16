type Translate = (key: string, fallback?: string) => string;

export const workflowDependencyTypeLabel = (
  t: Translate,
  dependencyType?: string,
) => {
  switch (dependencyType) {
    case 'AGENT':
      return t('ai.workflows.dependency.agent', 'Agent');
    case 'SKILL':
      return t('ai.workflows.dependency.skill', 'Skill');
    case 'COMPENSATION_SKILL':
      return t('ai.workflows.dependency.compensationSkill', '补偿 Skill');
    default:
      return t('ai.workflows.dependency.unknown', '未知依赖类型');
  }
};
