export type WorkflowWorkbenchPermissions = {
  view: boolean;
  create: boolean;
  edit: boolean;
  delete: boolean;
  manageVersions: boolean;
  publish: boolean;
  execute: boolean;
  intervene: boolean;
};

export const denyAllWorkflowWorkbenchPermissions:
Readonly<WorkflowWorkbenchPermissions> = Object.freeze({
  view: false,
  create: false,
  edit: false,
  delete: false,
  manageVersions: false,
  publish: false,
  execute: false,
  intervene: false,
});

const permissionFields = Object.keys(
  denyAllWorkflowWorkbenchPermissions,
) as Array<keyof WorkflowWorkbenchPermissions>;

/**
 * Normalizes an untrusted API response without JavaScript truthiness. Missing,
 * malformed, or non-boolean values always fail closed.
 */
export const normalizeWorkflowWorkbenchPermissions = (
  value: unknown,
): WorkflowWorkbenchPermissions => {
  const source = value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
  return Object.fromEntries(permissionFields.map((field) => [
    field,
    source[field] === true,
  ])) as WorkflowWorkbenchPermissions;
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
  intervene: 'intervene',
  pause: 'intervene',
  resume: 'intervene',
  cancel: 'intervene',
  respondHumanTask: 'intervene',
} as const satisfies Readonly<Record<string, keyof WorkflowWorkbenchPermissions>>);

export type WorkflowWorkbenchAction = keyof typeof actionPermission;

/** Unknown actions remain denied until they are explicitly added to the
 * Workflow permission contract. */
export const canPerformWorkflowWorkbenchAction = (
  permissions: Readonly<WorkflowWorkbenchPermissions> | undefined,
  action: string,
) => {
  const permission = (
    actionPermission as Readonly<Record<string, keyof WorkflowWorkbenchPermissions>>
  )[action];
  return permission !== undefined && permissions?.[permission] === true;
};
