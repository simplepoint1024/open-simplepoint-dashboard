import type {ReactNode} from 'react';
import type {ExpressionVariable} from '../ExpressionEditor/model';

export type WorkflowVariable = {
  name: string;
  label: string;
  type: 'string' | 'number' | 'boolean' | 'date' | 'date-time' | 'user' | 'users' | 'role' | 'roles';
  scope: 'start' | 'task' | 'both';
  required: boolean;
  defaultValue?: unknown;
};

export type WorkflowFormContract = {
  schemaVersion: 1;
  variables: WorkflowVariable[];
  startBinding?: WorkflowFormBinding;
  taskBindings: Record<string, WorkflowFormBinding>;
};

export const emptyFormContract = (): WorkflowFormContract => ({schemaVersion: 1, variables: [], taskBindings: {}});

const reservedVariableNames = new Set(['initiator', 'workflowDefinitionId', 'workflowVersion', 'workflowTitle',
  'execution', 'task', 'processEngine', 'processEngineConfiguration', 'delegateTask', 'class', 'Runtime',
  'ProcessBuilder', 'new', 'true', 'false', 'null', 'and', 'or', 'not', 'eq', 'ne', 'gt', 'lt', 'ge', 'le',
  'empty', 'div', 'mod', 'instanceof', '__proto__', 'constructor', 'prototype'].map(value => value.toLowerCase()));

export function reservedVariable(name: string): boolean {return reservedVariableNames.has(name.toLowerCase());}

export type WorkflowRuntimeForm = {
  definitionVersion: number;
  formContract: WorkflowFormContract;
  binding?: WorkflowFormBinding;
  values: Record<string, unknown>;
};

export function expressionVariables(contract: WorkflowFormContract): ExpressionVariable[] {
  return contract.variables.map(variable => ({name: variable.name, label: variable.label, kind: variable.type,
    type: variable.type === 'number' || variable.type === 'boolean' ? variable.type
      : variable.type === 'users' || variable.type === 'roles' ? 'array' : 'string'}));
}

/** Versioned integration boundary shared with future form-engine providers. */
export type WorkflowFormBinding = {
  provider: string;
  formId: string;
  version: number;
  variableMappings: Record<string, string>;
};

export type WorkflowFormAdapter = {
  provider: string;
  /** Adapters are registered explicitly by the application; bindings never trigger arbitrary module loading. */
  renderBindingEditor?: (context: {
    elementId: string;
    binding?: WorkflowFormBinding;
    readOnly: boolean;
    onChange: (binding?: WorkflowFormBinding) => void;
  }) => ReactNode;
  renderStartForm?: (context: {
    binding: WorkflowFormBinding;
    value: Record<string, unknown>;
    onChange: (value: Record<string, unknown>) => void;
    disabled: boolean;
    onValidityChange: (valid: boolean) => void;
  }) => ReactNode;
  renderTaskForm?: (context: {
    binding: WorkflowFormBinding;
    taskId: string;
    value: Record<string, unknown>;
    onChange: (value: Record<string, unknown>) => void;
    disabled: boolean;
    onValidityChange: (valid: boolean) => void;
  }) => ReactNode;
};
