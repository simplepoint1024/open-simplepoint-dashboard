import {
  ApiOutlined,
  DatabaseOutlined,
  MessageOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Card, Col, Row, Space, Tag, Typography} from 'antd';
import {useEffect} from 'react';
import api from '@/api';

const workspaceConfig = api['platform.ai-workspace'];
const {Paragraph, Text, Title} = Typography;

const Workspace = () => {
  const {ensure, locale, t} = useI18n();

  useEffect(() => {
    void ensure(workspaceConfig.i18nNamespaces);
  }, [ensure, locale]);

  const capabilities = [
    {
      key: 'assistant',
      icon: <MessageOutlined />,
      title: t('ai.workspace.capability.assistant.title', '智能助手'),
      description: t('ai.workspace.capability.assistant.description', '接入对话、提示词和会话管理能力。'),
    },
    {
      key: 'knowledge',
      icon: <DatabaseOutlined />,
      title: t('ai.workspace.capability.knowledge.title', '知识库'),
      description: t('ai.workspace.capability.knowledge.description', '管理文档、向量索引和检索增强配置。'),
      ready: true,
    },
    {
      key: 'model',
      icon: <ApiOutlined />,
      title: t('ai.workspace.capability.model.title', '模型接入'),
      description: t('ai.workspace.capability.model.description', '统一维护模型供应商和调用参数。'),
      ready: true,
    },
    {
      key: 'settings',
      icon: <SettingOutlined />,
      title: t('ai.workspace.capability.settings.title', 'AI 配置'),
      description: t('ai.workspace.capability.settings.description', '扩展安全策略、配额和运行参数。'),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{display: 'flex'}}>
      <Card>
        <Space align="start" size={16}>
          <RobotOutlined style={{fontSize: 36, color: '#1677ff'}} />
          <div>
            <Title level={3} style={{margin: 0}}>
              {t('ai.workspace.title', 'SimplePoint AI 工作台')}
            </Title>
            <Paragraph type="secondary" style={{margin: '8px 0 0'}}>
              {t('ai.workspace.description', '用于承载智能助手、知识库和模型服务等 AI 能力。')}
            </Paragraph>
          </div>
        </Space>
      </Card>

      <Alert
        type="success"
        showIcon
        message={t('ai.workspace.ready.title', '模型接入与知识库已就绪')}
        description={t('ai.workspace.ready.description', '支持模型目录、常见文档解析、pgvector 向量索引和混合检索。')}
      />

      <Row gutter={[16, 16]}>
        {capabilities.map((capability) => (
          <Col key={capability.key} xs={24} sm={12} xl={6}>
            <Card style={{height: '100%'}}>
              <Space direction="vertical" size={12}>
                <Space>
                  <span style={{fontSize: 22, color: '#1677ff'}}>{capability.icon}</span>
                  <Text strong>{capability.title}</Text>
                </Space>
                <Text type="secondary">{capability.description}</Text>
                <Tag color={capability.ready ? 'green' : undefined}>
                  {capability.ready
                    ? t('ai.workspace.status.ready', '已接入')
                    : t('ai.workspace.status.planned', '待接入')}
                </Tag>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>
    </Space>
  );
};

export default Workspace;
