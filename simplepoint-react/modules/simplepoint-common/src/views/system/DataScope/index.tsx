import SimpleTable from '@simplepoint/components/SimpleTable';
import api from '@/api/index';
import {useCallback, useEffect} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const baseConfig = api['rbac-data-scopes'];

// customDeptIds schema injected via formSchemaTransform when type=CUSTOM
const CUSTOM_DEPT_IDS_SCHEMA = {
    type: 'array',
    items: {type: 'string'},
    uniqueItems: true,
};

/**
 * Transform the backend schema to conditionally show customDeptIds
 * only when the DataScope type is CUSTOM.
 * Uses RJSF dependencies so the field appears/disappears as the user
 * changes the type dropdown inside the live form.
 */
function injectCustomDepsSchema(schema: any): any {
    const next = structuredClone(schema);
    const props = next?.properties;
    if (!props || !props.type || !props.customDeptIds) {
        return next;
    }

    // Determine enum values from the existing type field
    const typeEnumValues: string[] = props.type?.enum ?? ['ALL', 'CUSTOM', 'DEPT', 'DEPT_AND_BELOW', 'SELF'];
    const nonCustomEnums = typeEnumValues.filter((v: string) => v !== 'CUSTOM');

    // Move customDeptIds out of top-level properties into dependencies
    const customDeptIdsDef = {...CUSTOM_DEPT_IDS_SCHEMA, title: props.customDeptIds?.title};
    delete props.customDeptIds;

    next.dependencies = {
        ...(next.dependencies ?? {}),
        type: {
            oneOf: [
                {
                    properties: {
                        type: {enum: ['CUSTOM']},
                        customDeptIds: customDeptIdsDef,
                    },
                },
                {
                    properties: {
                        type: {enum: nonCustomEnums},
                    },
                },
            ],
        },
    };
    return next;
}

const App = () => {
    const {ensure, locale} = useI18n();

    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    const formSchemaTransform = useCallback((schema: any) => injectCustomDepsSchema(schema), []);

    const beforeSubmit = useCallback(({formData}: any) => {
        // Clear customDeptIds when type is not CUSTOM to avoid stale data
        if (formData?.type !== 'CUSTOM') {
            return {...formData, customDeptIds: []};
        }
        return formData;
    }, []);

    return (
        <SimpleTable
            {...baseConfig}
            submitRefreshTargets={{page: true, schema: false}}
            deleteRefreshTargets={{page: true, schema: false}}
            formSchemaTransform={formSchemaTransform}
            beforeSubmit={beforeSubmit}
            formUiSchema={{customDeptIds: {'ui:widget': 'OrgTreeMultiSelect'}}}
        />
    );
};

export default App;
