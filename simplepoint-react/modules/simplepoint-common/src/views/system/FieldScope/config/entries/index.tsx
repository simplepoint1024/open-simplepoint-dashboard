import {App as AntdApp, Alert, Button, Form, Select, Space} from 'antd';
import {MinusCircleOutlined, PlusOutlined} from '@ant-design/icons';
import {useEffect, useState} from 'react';
import {fetchFieldCatalog, replaceEntries, FieldScopeEntryDto} from '@/api/system/field-scope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

import {useData} from '@simplepoint/shared/api/methods';

const ACCESS_OPTIONS = [
    {value: 'EDITABLE', labelKey: 'field-scopes.title.entry.access.EDITABLE'},
    {value: 'VISIBLE', labelKey: 'field-scopes.title.entry.access.VISIBLE'},
    {value: 'MASKED', labelKey: 'field-scopes.title.entry.access.MASKED'},
    {value: 'HIDDEN', labelKey: 'field-scopes.title.entry.access.HIDDEN'},
];

const EMPTY_ENTRIES: FieldScopeEntryDto[] = [];

export interface FieldScopeEntriesConfigProps {
    fieldScopeId: string;
    initialEntries?: FieldScopeEntryDto[];
    onSuccess?: () => void;
}

const App = ({fieldScopeId, initialEntries = EMPTY_ENTRIES, onSuccess}: FieldScopeEntriesConfigProps) => {
    const {message} = AntdApp.useApp();
    const {t} = useI18n();
    const [form] = Form.useForm();
    const [saving, setSaving] = useState(false);
    const {data: catalog, isFetching, error} = useData(['fieldScopeCatalog'], fetchFieldCatalog);
    const entries = Form.useWatch('entries', form) as FieldScopeEntryDto[] | undefined;
    useEffect(() => {
        form.setFieldsValue({entries: initialEntries});
    }, [fieldScopeId, initialEntries, form]);
    const fieldsFor = (resource?: string) => {
        if (!catalog || !resource) return [];
        if (catalog[resource]) return catalog[resource];
        const matches = Object.keys(catalog).filter((key) => key.split('.').pop() === resource);
        return matches.length === 1 ? catalog[matches[0]] : [];
    };

    const handleSave = async () => {
        try {
            const values = await form.validateFields();
            const keys = (values.entries ?? []).map((entry: FieldScopeEntryDto) => entry.resource + '#' + entry.field);
            if (new Set(keys).size !== keys.length) {
                message.error(t('field-scopes.rule.duplicate', '同一资源字段不能重复配置'));
                return;
            }
            setSaving(true);
            await replaceEntries(fieldScopeId, values.entries ?? []);
            message.success(t('field-scopes.message.entriesSaveSuccess', '保存成功'));
            onSuccess?.();
        } catch (e: any) {
            if (e?.errorFields) return; // validation error, already shown
            message.error(t('field-scopes.message.entriesSaveFailed', '保存失败'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <Form
            form={form}
            disabled={saving || isFetching || !!error}
            initialValues={{entries: initialEntries}}
            style={{maxWidth: 720}}
        >
            {error && <Alert type="error" showIcon message={t('field-scopes.message.catalogFailed', '字段目录加载失败，请刷新后重试')} />}
            <Form.List name="entries">
                {(fields, {add, remove}) => (
                    <>
                        {fields.map(({key, name, ...restField}) => (
                            <Space
                                key={key}
                                align="baseline"
                                style={{display: 'flex', marginBottom: 8, flexWrap: 'wrap'}}
                            >
                                <Form.Item
                                    {...restField}
                                    name={[name, 'resource']}
                                    rules={[{required: true, message: t('field-scopes.rule.entry.resource', '请输入资源名')}]}
                                    style={{marginBottom: 0, minWidth: 160}}
                                >
                                    <Select
                                        showSearch optionFilterProp="label"
                                        placeholder={t('field-scopes.title.entry.resource', '资源')}
                                        options={Object.keys(catalog ?? {}).map((resource) => ({value: resource, label: resource}))}
                                        onChange={() => form.setFieldValue(['entries', name, 'field'], undefined)}
                                        loading={isFetching}
                                    />
                                </Form.Item>
                                <Form.Item
                                    {...restField}
                                    name={[name, 'field']}
                                    rules={[{required: true, message: t('field-scopes.rule.entry.field', '请输入字段名')}]}
                                    style={{marginBottom: 0, minWidth: 160}}
                                >
                                    <Select
                                        showSearch
                                        placeholder={t('field-scopes.title.entry.field', '字段')}
                                        options={fieldsFor(entries?.[name]?.resource).map((field) => ({value: field, label: field}))}
                                    />
                                </Form.Item>
                                <Form.Item
                                    {...restField}
                                    name={[name, 'access']}
                                    rules={[{required: true, message: t('field-scopes.rule.entry.access', '请选择访问级别')}]}
                                    style={{marginBottom: 0, minWidth: 140}}
                                >
                                    <Select
                                        placeholder={t('field-scopes.title.entry.access', '访问级别')}
                                        style={{width: 140}}
                                        options={ACCESS_OPTIONS.map(o => ({
                                            value: o.value,
                                            label: t(o.labelKey, o.value),
                                        }))}
                                    />
                                </Form.Item>
                                <MinusCircleOutlined onClick={() => remove(name)} style={{color: '#ff4d4f'}}/>
                            </Space>
                        ))}
                        <Form.Item>
                            <Button type="dashed" onClick={() => add({resource: '', field: '', access: 'VISIBLE'})}
                                    icon={<PlusOutlined/>}>
                                {t('field-scopes.action.addEntry', '添加规则')}
                            </Button>
                        </Form.Item>
                    </>
                )}
            </Form.List>
            <Form.Item>
                <Button type="primary" onClick={handleSave} loading={saving}>
                    {t('field-scopes.action.saveEntries', '保存')}
                </Button>
            </Form.Item>
        </Form>
    );
};

export default App;
