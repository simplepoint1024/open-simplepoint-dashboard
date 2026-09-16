import api from '@/api';
import {
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Avatar,
  App,
  Button,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Typography,
} from 'antd';
import {useEffect, useRef, useState} from 'react';
import {
  localizeWorkbenchError,
  resolveWorkbenchOperationError,
} from '../workbenchErrorCodes';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

export type DebugModel = {
  id: string;
  modelId?: string;
  displayName?: string;
};

type GenerationResult = {
  output?: Array<{type: string; text?: string}>;
};

type GenerationEvent = {
  type: string;
  textDelta?: string;
  result?: GenerationResult;
  errorCode?: string;
};

type ChatMessage = {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  status: 'completed' | 'streaming' | 'failed' | 'cancelled';
};

type ModelDebugDialogProps = {
  model?: DebugModel;
  open: boolean;
  onClose: () => void;
};

let localId = 0;

const debugOperationFallback = {
  key: 'ai.model-debug.error.generate',
  fallback: '模型调用失败',
};

class ModelDebugOperationError extends Error {
  readonly code: string;

  constructor(code: string) {
    super('Model debug operation failed');
    this.name = 'ModelDebugOperationError';
    this.code = code;
  }
}

const messageId = (role: string) => `${role}-${Date.now()}-${++localId}`;

const finalText = (result?: GenerationResult) => (
  result?.output
    ?.filter((block) => block.type === 'TEXT' || block.type === 'REFUSAL')
    .map((block) => block.text)
    .filter(Boolean)
    .join('\n\n') ?? ''
);

const readSse = async (
  response: Response,
  onEvent: (event: GenerationEvent) => void,
) => {
  if (!response.body) {
    throw new ModelDebugOperationError('AI_MODEL_DEBUG_STREAM_UNAVAILABLE');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const consume = (frame: string) => {
    const data = frame.split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
      .trim();
    if (data && data !== '[DONE]') onEvent(JSON.parse(data) as GenerationEvent);
  };

  while (true) {
    const {done, value} = await reader.read();
    buffer += decoder.decode(value, {stream: !done}).replace(/\r\n/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      consume(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
    if (done) break;
  }
  if (buffer.trim()) consume(buffer);
};

const ModelDebugDialog = ({model, open, onClose}: ModelDebugDialogProps) => {
  const config = api['ai-workbench.models'];
  const {t} = useI18n();
  const {message} = App.useApp();
  const [conversation, setConversation] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const conversationRef = useRef<HTMLDivElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const target = conversationRef.current;
    if (target) target.scrollTop = target.scrollHeight;
  }, [conversation]);

  useEffect(() => () => requestRef.current?.abort(), []);

  const updateAssistant = (id: string, update: Partial<ChatMessage>) => {
    setConversation((current) => current.map((item) => (
      item.id === id ? {...item, ...update} : item
    )));
  };

  const close = () => {
    requestRef.current?.abort();
    requestRef.current = null;
    setConversation([]);
    setInput('');
    setSubmitting(false);
    onClose();
  };

  const send = async () => {
    const prompt = input.trim();
    if (!model?.id || !prompt || submitting) return;

    const userMessage: ChatMessage = {
      id: messageId('user'),
      role: 'USER',
      content: prompt,
      status: 'completed',
    };
    const assistantId = messageId('assistant');
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

    const controller = new AbortController();
    requestRef.current = controller;
    let text = '';
    let completed = false;

    try {
      const response = await post<Response>(config.debugUrl(model.id), {
        messages: history.map((item) => ({
          role: item.role,
          content: [{type: 'TEXT', text: item.content}],
        })),
      }, {
        responseType: 'response',
        timeoutMs: 310_000,
        signal: controller.signal,
      });
      await readSse(response, (event) => {
        if (event.type === 'TEXT_DELTA' || event.type === 'REFUSAL_DELTA') {
          text += event.textDelta || '';
          updateAssistant(assistantId, {content: text});
        } else if (event.type === 'COMPLETED') {
          completed = true;
          text ||= finalText(event.result);
          updateAssistant(assistantId, {content: text, status: 'completed'});
        } else if (event.type === 'ERROR') {
          throw new ModelDebugOperationError(
            event.errorCode || 'AI_MODEL_DEBUG_GENERATION_FAILED',
          );
        }
      });
      if (!completed) {
        throw new ModelDebugOperationError('AI_MODEL_DEBUG_INCOMPLETE');
      }
    } catch (error) {
      if (controller.signal.aborted) {
        updateAssistant(assistantId, {
          content: text || t('ai.model-debug.cancelled', '生成已停止'),
          status: 'cancelled',
        });
      } else {
        const errorText = localizeWorkbenchError(
          t,
          resolveWorkbenchOperationError(error, debugOperationFallback),
        );
        updateAssistant(assistantId, {content: text || errorText, status: 'failed'});
        message.error(errorText);
      }
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      width={760}
      footer={null}
      destroyOnHidden
      title={model?.displayName || model?.modelId || t('ai.model-debug.title', '模型调试')}
      onCancel={close}
    >
      <div
        ref={conversationRef}
        style={{
          height: 480,
          overflowY: 'auto',
          padding: '16px 4px',
          borderTop: '1px solid #f0f0f0',
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        {!conversation.length && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t('ai.model-debug.empty', '发送一条消息开始对话')}
            style={{marginTop: 135}}
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
                      background: user ? '#1677ff' : '#f5f5f5',
                      color: user ? '#fff' : 'inherit',
                    }}
                  >
                    {item.status === 'streaming' && !item.content ? (
                      <Space size={8}>
                        <Spin size="small" />
                        <Text type="secondary">{t('ai.model-debug.thinking', '正在生成…')}</Text>
                      </Space>
                    ) : (
                      <Paragraph style={{margin: 0, whiteSpace: 'pre-wrap', color: 'inherit'}}>
                        {item.content}
                      </Paragraph>
                    )}
                  </div>
                  {item.status === 'failed' && (
                    <Text type="danger">{t('ai.model-debug.failed', '本轮调用失败')}</Text>
                  )}
                </div>
                {user && <Avatar icon={<UserOutlined />} />}
              </div>
            );
          })}
        </Space>
      </div>
      <div style={{paddingTop: 16}}>
        <TextArea
          value={input}
          autoSize={{minRows: 3, maxRows: 7}}
          disabled={submitting}
          placeholder={t('ai.model-debug.placeholder', '输入消息，Enter 发送，Shift + Enter 换行')}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              void send();
            }
          }}
        />
        <div style={{display: 'flex', justifyContent: 'flex-end', marginTop: 10}}>
          {submitting ? (
            <Button danger icon={<StopOutlined />} onClick={() => requestRef.current?.abort()}>
              {t('ai.model-debug.stop', '停止')}
            </Button>
          ) : (
            <Button
              type="primary"
              icon={<SendOutlined />}
              disabled={!input.trim()}
              onClick={() => void send()}
            >
              {t('ai.model-debug.send', '发送')}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default ModelDebugDialog;
