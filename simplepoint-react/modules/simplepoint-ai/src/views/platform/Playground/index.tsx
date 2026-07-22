import api from '@/api';
import {
  ClearOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {get, post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  Divider,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Typography,
  message,
} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

type ModelOption = {
  id: string;
  modelId: string;
  displayName?: string;
  modelType: string;
  providerName?: string;
};

type ContentBlock = {
  type: string;
  text?: string;
  toolName?: string;
  argumentsJson?: string;
};

type GenerationResult = {
  invocationId: string;
  modelId: string;
  output?: ContentBlock[];
  stopReason?: string;
  durationMillis: number;
  usage?: {inputTokens?: number; outputTokens?: number; totalTokens?: number};
};

type GenerationEvent = {
  type: string;
  textDelta?: string;
  result?: GenerationResult;
  errorMessage?: string;
};

type ChatMessage = {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  status: 'completed' | 'streaming' | 'failed' | 'cancelled';
  result?: GenerationResult;
};

type SettingsValues = {
  modelDefinitionId: string;
  instructions?: string;
  temperature?: number;
  maxOutputTokens?: number;
  jsonSchema?: string;
};

type PlaygroundConfigKey = 'platform.ai-inference' | 'tenant.ai-inference';

type PlaygroundProps = {
  configKey?: PlaygroundConfigKey;
};

let localId = 0;

const createMessageId = (role: string) => `${role}-${Date.now()}-${++localId}`;

const formatOutput = (blocks: ContentBlock[] | undefined, toolCallLabel: string) => (
  (blocks ?? []).map((block) => (
    block.type === 'TEXT' || block.type === 'REFUSAL'
      ? block.text
      : `${block.toolName || toolCallLabel}: ${block.argumentsJson || '{}'}`
  )).filter(Boolean).join('\n\n')
);

const readSse = async (
  response: Response,
  onEvent: (event: GenerationEvent) => void,
) => {
  if (!response.body) {
    throw new Error('Streaming response body is unavailable');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const consumeFrame = (frame: string) => {
    const data = frame.split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
      .trim();
    if (!data || data === '[DONE]') return;
    onEvent(JSON.parse(data) as GenerationEvent);
  };

  while (true) {
    const {done, value} = await reader.read();
    buffer += decoder.decode(value, {stream: !done}).replace(/\r\n/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      consumeFrame(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
    if (done) break;
  }
  if (buffer.trim()) consumeFrame(buffer);
};

export const PlaygroundView = ({configKey = 'platform.ai-inference'}: PlaygroundProps) => {
  const config = api[configKey];
  const {ensure, locale, t} = useI18n();
  const [form] = Form.useForm<SettingsValues>();
  const [models, setModels] = useState<ModelOption[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [input, setInput] = useState('');
  const [conversation, setConversation] = useState<ChatMessage[]>([]);
  const conversationRef = useRef<HTMLDivElement>(null);
  const requestControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [ensure, locale]);

  useEffect(() => {
    setLoadingModels(true);
    void get<ModelOption[]>(config.modelsUrl)
      .then((items) => setModels((items ?? []).filter((item) => (
        item.modelType === 'LLM' || item.modelType === 'MULTIMODAL'
      ))))
      .catch((error) => message.error(resolveApiErrorMessage(
        error,
        t('ai.inference.error.models', '可用生成模型加载失败'),
      )))
      .finally(() => setLoadingModels(false));
  }, [config.modelsUrl, locale]);

  useEffect(() => {
    const target = conversationRef.current;
    if (target) target.scrollTop = target.scrollHeight;
  }, [conversation]);

  useEffect(() => () => requestControllerRef.current?.abort(), []);

  const options = useMemo(() => models.map((model) => ({
    value: model.id,
    label: `${model.displayName || model.modelId}${model.providerName ? ` · ${model.providerName}` : ''}`,
  })), [models]);

  const updateAssistant = (id: string, update: Partial<ChatMessage>) => {
    setConversation((current) => current.map((item) => (
      item.id === id ? {...item, ...update} : item
    )));
  };

  const send = async () => {
    const prompt = input.trim();
    if (!prompt || submitting) return;

    let values: SettingsValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    const userMessage: ChatMessage = {
      id: createMessageId('user'),
      role: 'USER',
      content: prompt,
      status: 'completed',
    };
    const assistantId = createMessageId('assistant');
    const assistantMessage: ChatMessage = {
      id: assistantId,
      role: 'ASSISTANT',
      content: '',
      status: 'streaming',
    };
    const history = [...conversation.filter((item) => item.status === 'completed'), userMessage];
    setConversation((current) => [...current, userMessage, assistantMessage]);
    setInput('');
    setSubmitting(true);

    const responseFormat = values.jsonSchema?.trim()
      ? {
          type: 'JSON_SCHEMA',
          name: 'response',
          jsonSchema: values.jsonSchema.trim(),
          strict: true,
        }
      : undefined;
    let assistantText = '';
    let completed = false;
    const requestController = new AbortController();
    requestControllerRef.current = requestController;

    try {
      const response = await post<Response>(`${config.baseUrl}/stream`, {
        modelDefinitionId: values.modelDefinitionId,
        instructions: values.instructions?.trim() || undefined,
        messages: history.map((item) => ({
          role: item.role,
          content: [{type: 'TEXT', text: item.content}],
        })),
        maxOutputTokens: values.maxOutputTokens,
        temperature: values.temperature,
        responseFormat,
      }, {
        responseType: 'response',
        timeoutMs: 310_000,
        signal: requestController.signal,
      });

      await readSse(response, (event) => {
        if (event.type === 'TEXT_DELTA' || event.type === 'REFUSAL_DELTA') {
          assistantText += event.textDelta || '';
          updateAssistant(assistantId, {content: assistantText});
        } else if (event.type === 'COMPLETED' && event.result) {
          completed = true;
          assistantText ||= formatOutput(
            event.result.output,
            t('ai.inference.toolCall', '工具调用'),
          );
          updateAssistant(assistantId, {
            content: assistantText,
            status: 'completed',
            result: event.result,
          });
        } else if (event.type === 'ERROR') {
          throw new Error(event.errorMessage || t('ai.inference.error.generate', '模型调用失败'));
        }
      });
      if (!completed) {
        throw new Error(t('ai.inference.error.incomplete', '模型流式响应未正常完成'));
      }
    } catch (error) {
      if (requestController.signal.aborted) {
        updateAssistant(assistantId, {
          content: assistantText || t('ai.inference.chat.cancelled', '生成已停止'),
          status: 'cancelled',
        });
        return;
      }
      const text = resolveApiErrorMessage(
        error,
        t('ai.inference.error.generate', '模型调用失败'),
      );
      updateAssistant(assistantId, {
        content: assistantText || text,
        status: 'failed',
      });
      message.error(text);
    } finally {
      if (requestControllerRef.current === requestController) {
        requestControllerRef.current = null;
      }
      setSubmitting(false);
    }
  };

  const stop = () => {
    requestControllerRef.current?.abort();
  };

  return (
    <Space direction="vertical" size={16} style={{display: 'flex'}}>
      <Alert
        showIcon
        type="info"
        message={t('ai.inference.notice.title', '统一模型对话调试')}
        description={t('ai.inference.notice.description', '对话会携带当前页面内的多轮上下文；调用台账不会保存提示词或模型输出正文。')}
      />
      <Row gutter={[16, 16]} align="stretch">
        <Col xs={24} lg={7}>
          <Card title={t('ai.inference.settings.title', '对话设置')} style={{height: '100%'}}>
            <Form
              form={form}
              layout="vertical"
              initialValues={{temperature: 0.7, maxOutputTokens: 1024}}
            >
              <Form.Item
                name="modelDefinitionId"
                label={t('ai.inference.form.model', '模型')}
                rules={[{required: true}]}
              >
                <Select
                  showSearch
                  loading={loadingModels}
                  options={options}
                  optionFilterProp="label"
                  placeholder={t('ai.inference.form.model.placeholder', '选择可用的 LLM 或多模态模型')}
                />
              </Form.Item>
              <Form.Item name="instructions" label={t('ai.inference.form.instructions', '系统指令')}>
                <TextArea autoSize={{minRows: 3, maxRows: 8}} />
              </Form.Item>
              <Divider titlePlacement="start" plain>
                {t('ai.inference.settings.advanced', '高级参数')}
              </Divider>
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item name="temperature" label="Temperature">
                    <InputNumber min={0} max={2} step={0.1} style={{width: '100%'}} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="maxOutputTokens" label={t('ai.inference.form.maxTokens', '最大输出 Token')}>
                    <InputNumber min={1} max={32768} step={128} style={{width: '100%'}} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="jsonSchema"
                label={t('ai.inference.form.jsonSchema', '响应 JSON Schema（可选）')}
                tooltip={t('ai.inference.form.jsonSchema.tooltip', '填写后启用严格结构化输出。')}
              >
                <TextArea autoSize={{minRows: 4, maxRows: 12}} placeholder='{"type":"object","properties":{}}' />
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={17}>
          <Card
            title={t('ai.inference.chat.title', '模型对话')}
            extra={(
              <Button
                icon={<ClearOutlined />}
                disabled={!conversation.length || submitting}
                onClick={() => setConversation([])}
              >
                {t('ai.inference.action.clear', '清空对话')}
              </Button>
            )}
            styles={{body: {padding: 0}}}
          >
            <div
              ref={conversationRef}
              style={{height: 520, overflowY: 'auto', padding: 20, background: '#f7f8fa'}}
            >
              {!conversation.length && (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t('ai.inference.chat.empty', '发送一条消息开始对话')}
                  style={{marginTop: 150}}
                />
              )}
              <Space direction="vertical" size={18} style={{display: 'flex'}}>
                {conversation.map((item) => {
                  const user = item.role === 'USER';
                  return (
                    <div
                      key={item.id}
                      style={{display: 'flex', justifyContent: user ? 'flex-end' : 'flex-start', gap: 10}}
                    >
                      {!user && <Avatar icon={<RobotOutlined />} style={{background: '#1677ff'}} />}
                      <div style={{maxWidth: '82%'}}>
                        <div
                          style={{
                            borderRadius: 12,
                            padding: '10px 14px',
                            background: user ? '#1677ff' : '#fff',
                            color: user ? '#fff' : 'inherit',
                            boxShadow: user ? undefined : '0 1px 4px rgba(0,0,0,0.08)',
                          }}
                        >
                          {item.status === 'streaming' && !item.content ? (
                            <Space size={8}>
                              <Spin size="small" />
                              <Text type="secondary">{t('ai.inference.chat.thinking', '模型正在思考...')}</Text>
                            </Space>
                          ) : (
                            <Paragraph style={{margin: 0, whiteSpace: 'pre-wrap', color: 'inherit'}}>
                              {item.content}
                            </Paragraph>
                          )}
                        </div>
                        {item.status === 'failed' && (
                          <Text type="danger">{t('ai.inference.chat.failed', '本轮调用失败')}</Text>
                        )}
                        {item.status === 'cancelled' && (
                          <Text type="secondary">
                            {t('ai.inference.chat.cancelled', '生成已停止')}
                          </Text>
                        )}
                        {item.result && (
                          <Text type="secondary" style={{fontSize: 12}}>
                            {t('ai.inference.result.metadata', '耗时 {duration} ms，Token {tokens}', {
                              duration: item.result.durationMillis,
                              tokens: item.result.usage?.totalTokens ?? '-',
                            })}
                          </Text>
                        )}
                      </div>
                      {user && <Avatar icon={<UserOutlined />} />}
                    </div>
                  );
                })}
              </Space>
            </div>
            <div style={{padding: 16, borderTop: '1px solid #f0f0f0'}}>
              <TextArea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onPressEnter={(event) => {
                  if (!event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    void send();
                  }
                }}
                autoSize={{minRows: 3, maxRows: 8}}
                placeholder={t('ai.inference.chat.placeholder', '输入消息，Enter 发送，Shift + Enter 换行')}
                disabled={submitting}
              />
              <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 10}}>
                <Text type="secondary">
                  {t('ai.inference.chat.contextHint', '后续消息会自动携带本页对话上下文')}
                </Text>
                {submitting ? (
                  <Button danger icon={<StopOutlined />} onClick={stop}>
                    {t('ai.inference.action.stop', '停止生成')}
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    disabled={!input.trim()}
                    onClick={() => void send()}
                  >
                    {t('ai.inference.action.send', '发送')}
                  </Button>
                )}
              </div>
            </div>
          </Card>
        </Col>
      </Row>
    </Space>
  );
};

export default PlaygroundView;
