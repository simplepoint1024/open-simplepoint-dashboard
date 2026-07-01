import React, {useEffect, useMemo, useState} from "react";
import {
  Avatar,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  message,
  Row,
  Skeleton,
  Space,
  Tag,
  Typography
} from "antd";
import {EditOutlined, LockOutlined, ReloadOutlined, SaveOutlined, UserOutlined} from "@ant-design/icons";
import {useI18n} from "@/layouts/i18n/useI18n.ts";
import {useUserInfo} from '@/fetches/user.ts';
import {post} from '@simplepoint/shared/api/methods';
import './index.css'

export const Profile: React.FC = () => {
  const {t, ensure, locale} = useI18n();
  // 增量加载 profile 命名空间
  useEffect(() => {
    void ensure(['profile']);
  }, [ensure, locale]);

  const {data, isLoading, refetch} = useUserInfo();
  const [saving, setSaving] = useState(false);
  const [editMode, setEditMode] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    const handleFocus = () => {
      void refetch();
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        void refetch();
      }
    };

    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [refetch]);

  // 数据变化时同步到表单
  useEffect(() => {
    if (!data) return;
    form.setFieldsValue({
      nickname: (data as any)?.nickname || (data as any)?.name || '',
      email: (data as any)?.email || '',
      phone: (data as any)?.phone || '',
    });
  }, [data, form]);

  const roles: string[] = useMemo(() => {
    const r = (data as any)?.roles;
    if (Array.isArray(r)) return r as string[];
    if (typeof r === 'string') return r.split(',').map((s: string) => s.trim()).filter(Boolean);
    return [];
  }, [data]);

  const onSave = async () => {
    try {
      await form.validateFields();
      setSaving(true);
      await new Promise(r => setTimeout(r, 600));
      message.success(t('profile.saveSuccess', '保存成功'));
      setEditMode(false);
    } catch (_) {
      // ignore
    } finally {
      setSaving(false);
    }
  };

  const [pwdSaving, setPwdSaving] = useState(false);
  const [pwdForm] = Form.useForm();

  const onChangePassword = async () => {
    try {
      const values = await pwdForm.validateFields();
      if (values.newPassword !== values.confirmPassword) {
        pwdForm.setFields([{ name: 'confirmPassword', errors: [t('rule.passwordMismatch', '两次输入的密码不一致')] }]);
        return;
      }
      setPwdSaving(true);
      await post('/common/users/change-password', {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
        confirmPassword: values.confirmPassword,
      });
      message.success(t('profile.passwordChanged', '密码修改成功'));
      pwdForm.resetFields();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? t('profile.passwordChangeFailed', '密码修改失败'));
    } finally {
      setPwdSaving(false);
    }
  };

  const openTwoFactorSettings = () => {
    try {
      const opened = window.open('/authorization/account/2fa', '_blank', 'noopener,noreferrer');
      if (!opened) {
        window.location.assign('/authorization/account/2fa');
      }
    } catch {
      window.location.assign('/authorization/account/2fa');
    }
  };

  return (
    <div className="profile-page">
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card className="profile-card">
            {isLoading ? (
              <Skeleton avatar active paragraph={{rows: 2}}/>
            ) : data ? (
              <div className="avatar-card">
                <Avatar size={72} icon={!(data as any)?.picture ? <UserOutlined/> : undefined}
                        src={(data as any)?.picture}/>
                <Typography.Title className="profile-name" level={5}>
                  {(data as any)?.nickname || (data as any)?.name || t('user.defaultName', '用户')}
                </Typography.Title>
                <Typography.Paragraph className="profile-sub">
                  {(data as any)?.email || t('profile.noEmail', '未绑定邮箱')}
                </Typography.Paragraph>
                <Space>
                  <Button icon={<ReloadOutlined/>} onClick={() => refetch()} size="small">
                    {t('action.refresh', '刷新')}
                  </Button>
                  {!editMode ? (
                    <Button type="primary" icon={<EditOutlined/>} onClick={() => setEditMode(true)} size="small">
                      {t('action.edit', '编辑')}
                    </Button>
                  ) : (
                    <Button type="primary" icon={<SaveOutlined/>} loading={saving} onClick={onSave} size="small">
                      {t('action.save', '保存')}
                    </Button>
                  )}
                </Space>
              </div>
            ) : (
              <Empty/>
            )}
          </Card>
        </Col>
        <Col xs={24} md={16}>
          <Card className="profile-card" title={t('profile.basic', '基本信息')} extra={!isLoading && data ? (
            !editMode ? <Button type="link" icon={<EditOutlined/>}
                                onClick={() => setEditMode(true)}>{t('action.edit', '编辑')}</Button> :
              <Space>
                <Button onClick={() => {
                  form.resetFields();
                  setEditMode(false);
                }}>
                  {t('action.cancel', '取消')}
                </Button>
                <Button type="primary" icon={<SaveOutlined/>} loading={saving} onClick={onSave}>
                  {t('action.save', '保存')}
                </Button>
              </Space>
          ) : undefined}>
            {isLoading ? (
              <Skeleton active paragraph={{rows: 6}}/>
            ) : !data ? (
              <Empty/>
            ) : !editMode ? (
              <>
                <Descriptions column={1} size="middle" styles={{ label: { width: 120 } }}>
                  <Descriptions.Item label={t('field.username', '用户名')}>
                    {(data as any)?.username || (data as any)?.name || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.nickname', '昵称')}>
                    {(data as any)?.nickname || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.email', '邮箱')}>
                    {(data as any)?.email || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.phone', '手机号')}>
                    {(data as any)?.phone || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.roles', '角色')}>
                    {roles.length > 0 ? roles.map(r => (<Tag key={r} color="blue">{r}</Tag>)) : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.twoFactorEnabled', '两步认证')}>
                    <Space size={8} wrap>
                      <Tag color={data?.twoFactorEnabled === true ? 'success' : 'default'}>
                        {data?.twoFactorEnabled === true
                          ? t('profile.twoFactor.enabled', '已开启')
                          : t('profile.twoFactor.disabled', '未开启')}
                      </Tag>
                      <Button type="link" style={{paddingInline: 0}} onClick={openTwoFactorSettings}>
                        {data?.twoFactorEnabled === true
                          ? t('action.manageTwoFactor', '管理两步认证')
                          : t('action.enableTwoFactor', '开启两步认证')}
                      </Button>
                    </Space>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.joinedAt', '加入时间')}>
                    {(data as any)?.joinedAt || (data as any)?.createTime || '-'}
                  </Descriptions.Item>
                </Descriptions>
                <Form form={form} style={{ display: 'none' }} />
              </>
            ) : (
              <Form layout="vertical" form={form} className="profile-form">
                <Form.Item name="nickname" label={t('field.nickname', '昵称')}
                           rules={[{required: true, message: t('rule.nickname', '请输入昵称')}]}>
                  <Input maxLength={32} placeholder={t('ph.nickname', '请输入昵称')}/>
                </Form.Item>
                <Form.Item name="email" label={t('field.email', '邮箱')}
                           rules={[{type: 'email', message: t('rule.email', '邮箱格式不正确')}]}>
                  <Input maxLength={64} placeholder={t('ph.email', '请输入邮箱')}/>
                </Form.Item>
                <Form.Item name="phone" label={t('field.phone', '手机号')}>
                  <Input maxLength={20} placeholder={t('ph.phone', '请输入手机号')}/>
                </Form.Item>
              </Form>
            )}
          </Card>
        </Col>
      </Row>
      <Row gutter={[16, 16]} style={{marginTop: 0}}>
        <Col xs={24} md={{span: 16, offset: 8}}>
          <Card
            className="profile-card"
            title={<span><LockOutlined style={{marginRight: 6}}/>{t('profile.changePassword', '修改密码')}</span>}
          >
            <Form layout="vertical" form={pwdForm} style={{maxWidth: 400}}>
              <Form.Item
                name="currentPassword"
                label={t('field.currentPassword', '当前密码')}
                rules={[{required: true, message: t('rule.currentPassword', '请输入当前密码')}]}
              >
                <Input.Password maxLength={64} placeholder={t('ph.currentPassword', '请输入当前密码')}/>
              </Form.Item>
              <Form.Item
                name="newPassword"
                label={t('field.newPassword', '新密码')}
                rules={[
                  {required: true, message: t('rule.newPassword', '请输入新密码')},
                  {min: 6, message: t('rule.passwordLength', '密码长度至少 6 位')},
                ]}
              >
                <Input.Password maxLength={64} placeholder={t('ph.newPassword', '请输入新密码')}/>
              </Form.Item>
              <Form.Item
                name="confirmPassword"
                label={t('field.confirmPassword', '确认新密码')}
                rules={[{required: true, message: t('rule.confirmPassword', '请再次输入新密码')}]}
              >
                <Input.Password maxLength={64} placeholder={t('ph.confirmPassword', '请再次输入新密码')}/>
              </Form.Item>
              <Form.Item>
                <Button type="primary" icon={<LockOutlined/>} loading={pwdSaving} onClick={onChangePassword}>
                  {t('action.changePassword', '修改密码')}
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
