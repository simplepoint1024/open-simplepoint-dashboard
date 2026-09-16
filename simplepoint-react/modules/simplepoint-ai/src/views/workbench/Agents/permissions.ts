export type AgentWorkbenchPermissions = {
  view: boolean;
  create: boolean;
  edit: boolean;
  delete: boolean;
  manageVersions: boolean;
  publish: boolean;
  execute: boolean;
  approve: boolean;
  control: boolean;
  manageMemory: boolean;
  intervene: boolean;
};

export const denyAllAgentWorkbenchPermissions: Readonly<AgentWorkbenchPermissions> =
  Object.freeze({
    view: false,
    create: false,
    edit: false,
    delete: false,
    manageVersions: false,
    publish: false,
    execute: false,
    approve: false,
    control: false,
    manageMemory: false,
    intervene: false,
  });

const permissionFields = Object.keys(
  denyAllAgentWorkbenchPermissions,
) as Array<keyof AgentWorkbenchPermissions>;

/**
 * Normalizes an untrusted API response without JavaScript truthiness. Missing,
 * malformed, or non-boolean values always fail closed.
 */
export const normalizeAgentWorkbenchPermissions = (
  value: unknown,
): AgentWorkbenchPermissions => {
  const source = value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
  return Object.fromEntries(permissionFields.map((field) => [
    field,
    source[field] === true,
  ])) as AgentWorkbenchPermissions;
};

const actionPermission = Object.freeze({
    view: 'view',
    create: 'create',
    edit: 'edit',
    delete: 'delete',
    manageVersions: 'manageVersions',
    createVersion: 'manageVersions',
    publish: 'publish',
    deprecate: 'publish',
    execute: 'execute',
    cancel: 'execute',
    approve: 'approve',
    reject: 'approve',
    control: 'control',
    pause: 'control',
    resume: 'control',
    manageMemory: 'manageMemory',
    deleteMemory: 'manageMemory',
    intervene: 'intervene',
    requestIntervention: 'intervene',
    respondIntervention: 'intervene',
  } as const satisfies Readonly<Record<string, keyof AgentWorkbenchPermissions>>);

export type AgentWorkbenchAction = keyof typeof actionPermission;

/** Unknown actions are deliberately denied so new mutations cannot become
 * available before they are added to the permission contract. */
export const canPerformAgentWorkbenchAction = (
  permissions: Readonly<AgentWorkbenchPermissions> | undefined,
  action: string,
) => {
  const permission = (
    actionPermission as Readonly<Record<string, keyof AgentWorkbenchPermissions>>
  )[action];
  return permission !== undefined && permissions?.[permission] === true;
};
