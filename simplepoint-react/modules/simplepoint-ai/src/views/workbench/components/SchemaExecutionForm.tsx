import SForm from '@simplepoint/components/SForm';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Spin} from 'antd';
import {useEffect, useMemo, useState} from 'react';

export type ExecutionInput = Record<string, unknown>;

type SchemaExecutionFormProps = {
  schema?: unknown;
  loading?: boolean;
  submitting?: boolean;
  submitText: string;
  initialData?: ExecutionInput;
  onSubmit: (input: ExecutionInput) => void | Promise<void>;
};

const EMPTY_INPUT: ExecutionInput = {};

const isObject = (value: unknown): value is Record<string, unknown> => (
  Boolean(value) && typeof value === 'object' && !Array.isArray(value)
);

const normalizeObjectSchema = (schema: unknown): Record<string, unknown> => {
  if (!isObject(schema)) {
    return {type: 'object', properties: {}, additionalProperties: true};
  }
  const normalized = {...schema};
  if (!normalized.type) normalized.type = 'object';
  return normalized;
};

export const inputSchemaFromManifest = (
  manifest?: Record<string, unknown>,
): Record<string, unknown> => {
  if (!isObject(manifest?.spec)) return normalizeObjectSchema(undefined);
  return normalizeObjectSchema(manifest.spec.inputSchema);
};

const SchemaExecutionForm = ({
  schema,
  loading = false,
  submitting = false,
  submitText,
  initialData = EMPTY_INPUT,
  onSubmit,
}: SchemaExecutionFormProps) => {
  const {t} = useI18n();
  const normalizedSchema = useMemo(() => normalizeObjectSchema(schema), [schema]);
  const [formData, setFormData] = useState<ExecutionInput>(initialData);

  useEffect(() => {
    setFormData(initialData);
  }, [initialData, normalizedSchema]);

  if (loading) {
    return <div style={{display: 'flex', justifyContent: 'center', padding: 32}}><Spin /></div>;
  }

  const properties = isObject(normalizedSchema.properties)
    ? normalizedSchema.properties : {};
  const hasDeclaredFields = Object.keys(properties).length > 0;

  return (
    <>
      {!hasDeclaredFields && normalizedSchema.additionalProperties === false && (
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.execution.schema.noInput',
            '该版本无需执行输入，可直接提交。',
          )}
        />
      )}
      <SForm
        schema={normalizedSchema as never}
        formData={formData}
        submitLoading={submitting}
        uiSchema={{
          'ui:submitButtonOptions': {submitText},
        }}
        onChange={(event) => {
          setFormData(isObject(event.formData) ? event.formData : {});
        }}
        onSubmit={(event) => {
          void onSubmit(isObject(event.formData) ? event.formData : {});
        }}
      />
    </>
  );
};

export default SchemaExecutionForm;
