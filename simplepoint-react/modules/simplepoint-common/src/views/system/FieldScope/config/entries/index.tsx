import {Button, Form, message, Select, Space} from 'antd';
import {MinusCircleOutlined, PlusOutlined} from '@ant-design/icons';
import {useState} from 'react';
import {replaceEntries, FieldScopeEntryDto} from '@/api/system/field-scope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const ACCESS_OPTIONS = [
    {value: 'EDITABLE', labelKey: 'field-scopes.title.entry.access.EDITABLE'},
    {value: 'VISIBLE', labelKey: 'field-scopes.title.entry.access.VISIBLE'},
    {value: 'MASKED', labelKey: 'field-scopes.title.entry.access.MASKED'},
    {value: 'HIDDEN', labelKey: 'field-scopes.title.entry.access.HIDDEN'},
];

export interface FieldScopeEntriesConfigProps {
    fieldScopeId: string;
    initialEntries?: FieldScopeEntryDto[];
    onSuccess?: () => void;
}

const App = ({fieldScopeId, initialEntries = [], onSuccess}: FieldScopeEntriesConfigProps) => {
    const {t} = useI18n();
    const [form] = Form.useForm();
    const [saving, setSaving] = useState(false);

    const handleSave = async () => {
        try {
            const values = await form.validateFields();
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
            initialValues={{entries: initialEntries}}
            style={{maxWidth: 720}}
        >
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
                                    <input
                                        placeholder={t('field-scopes.title.entry.resource', '资源')}
                                        style={{
                                            border: '1px solid #d9d9d9',
                                            borderRadius: 6,
                                            padding: '4px 11px',
                                            width: '100%',
                                            outline: 'none',
                                        }}
                                    />
                                </Form.Item>
                                <Form.Item
                                    {...restField}
                                    name={[name, 'field']}
                                    rules={[{required: true, message: t('field-scopes.rule.entry.field', '请输入字段名')}]}
                                    style={{marginBottom: 0, minWidth: 160}}
                                >
                                    <input
                                        placeholder={t('field-scopes.title.entry.field', '字段')}
                                        style={{
                                            border: '1px solid #d9d9d9',
                                            borderRadius: 6,
                                            padding: '4px 11px',
                                            width: '100%',
                                            outline: 'none',
                                        }}
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
