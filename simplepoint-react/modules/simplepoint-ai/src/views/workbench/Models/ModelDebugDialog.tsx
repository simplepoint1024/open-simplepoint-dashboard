import TableActions from "@simplepoint/components/Table/Actions";
import api from "@/api";
import {
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from "@simplepoint/shared/types/icon";
import { post } from "@simplepoint/shared/api/methods";
import { useI18n } from "@simplepoint/shared/hooks/useI18n";
import {useOperationScope} from "@simplepoint/shared/hooks/useOperationScope";
import {readSse, finalText, ModelDebugOperationError} from "./stream";
import {
  Avatar,
  Alert,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Typography,
  theme,
} from "antd";
import { useEffect, useRef, useState } from "react";
import {
  localizeWorkbenchError,
  resolveWorkbenchOperationError,
} from "../workbenchErrorCodes";

const { Paragraph, Text } = Typography;
const { TextArea } = Input;

export type DebugModel = {
  id: string;
  modelId?: string;
  displayName?: string;
};

type ChatMessage = {
  id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  status: "completed" | "streaming" | "failed" | "cancelled";
};

type ModelDebugDialogProps = {
  model?: DebugModel;
  open: boolean;
  onClose: () => void;
  authorized: boolean;
};

let localId = 0;

const debugOperationFallback = {
  key: "ai.model-debug.error.generate",
  fallback: "模型调用失败",
};

const messageId = (role: string) => `${role}-${Date.now()}-${++localId}`;

const ModelDebugDialog = ({ model, open, onClose, authorized }: ModelDebugDialogProps) => {
  const config = api["ai-workbench.models"];
  const { t } = useI18n();
  const {token} = theme.useToken();
  const operation = useOperationScope(model?.id);
  const submitting = operation.busy;
  const [errorText, setErrorText] = useState('');
  const openRef = useRef(open); openRef.current = open;
  const [conversation, setConversation] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const conversationRef = useRef<HTMLDivElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const target = conversationRef.current;
    if (target) target.scrollTop = target.scrollHeight;
  }, [conversation]);

  useEffect(() => () => requestRef.current?.abort(), []);

  const updateAssistant = (id: string, update: Partial<ChatMessage>) => {
    setConversation((current) =>
      current.map((item) => (item.id === id ? { ...item, ...update } : item)),
    );
  };

  const close = () => {
    if (!operation.current() || !operation.isSelected()) return;
    const request = requestRef.current; requestRef.current = null;
    request?.abort(); if (request) operation.finish(request);
    setConversation([]);
    setInput("");
    setErrorText("");
    onClose();
  };

  const send = async () => {
    const prompt = input.trim();
    if (!model?.id || !prompt || !openRef.current || !authorized || requestRef.current) return;
    const controller = operation.begin(); if (!controller) return;
    requestRef.current = controller;
    const current = () => operation.current() && requestRef.current === controller;

    const userMessage: ChatMessage = {
      id: messageId("user"),
      role: "USER",
      content: prompt,
      status: "completed",
    };
    const assistantId = messageId("assistant");
    const assistantMessage: ChatMessage = {
      id: assistantId,
      role: "ASSISTANT",
      content: "",
      status: "streaming",
    };
    const history = [
      ...conversation.filter((item) => item.status === "completed"),
      userMessage,
    ];
    setConversation((current) => [...current, userMessage, assistantMessage]);
    setInput("");
    setErrorText("");
    let text = "";
    let completed = false;

    try {
      const response = await post<Response>(
        config.debugUrl(model.id),
        {
          messages: history.map((item) => ({
            role: item.role,
            content: [{ type: "TEXT", text: item.content }],
          })),
        },
        {
          responseType: "response",
          timeoutMs: 310_000,
          signal: controller.signal,
          notifyOnError: false,
        },
      );
      await readSse(response, (event) => {
        if (!current() || controller.signal.aborted) return;
        if (event.type === "TEXT_DELTA" || event.type === "REFUSAL_DELTA") {
          text += event.textDelta || "";
          updateAssistant(assistantId, { content: text });
        } else if (event.type === "COMPLETED") {
          completed = true;
          text ||= finalText(event.result);
          updateAssistant(assistantId, { content: text, status: "completed" });
        } else if (event.type === "ERROR") {
          throw new ModelDebugOperationError(
            event.errorCode || "AI_MODEL_DEBUG_GENERATION_FAILED",
          );
        }
      }, controller.signal);
      if (!current()) return;
      if (!completed) {
        throw new ModelDebugOperationError("AI_MODEL_DEBUG_INCOMPLETE");
      }
    } catch (error) {
      if (!current()) return;
      if (controller.signal.aborted) {
        updateAssistant(assistantId, {
          content: text || t("ai.model-debug.receivingStopped", "已停止接收响应"),
          status: "cancelled",
        });
      } else {
        const errorText = localizeWorkbenchError(
          t,
          resolveWorkbenchOperationError(error, debugOperationFallback),
        );
        updateAssistant(assistantId, {
          content: text || errorText,
          status: "failed",
        });
        setErrorText(errorText);
      }
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      operation.finish(controller);
    }
  };

  return (
    <Modal
      rootClassName="ai-model-debug"
      open={open && operation.selected}
      width={760}
      footer={null}
      destroyOnHidden
      title={
        model?.displayName ||
        model?.modelId ||
        t("ai.model-debug.title", "模型调试")
      }
      onCancel={close}
    >
      <Alert type="info" showIcon style={{marginBottom: 12}}
        title={t("ai.model-debug.billingNotice", "调试会实际调用模型，可能产生费用；停止接收响应不代表服务端已取消调用或计费。")}/>
      {errorText && <Alert className="ai-model-debug-error" type="error" showIcon title={errorText} style={{marginBottom: 12}}/>}
      <div
        ref={conversationRef}
        style={{
          height: 480,
          overflowY: "auto",
          padding: "16px 4px",
          borderTop: `1px solid ${token.colorBorderSecondary}`,
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        {!conversation.length && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t("ai.model-debug.empty", "发送一条消息开始对话")}
            style={{ marginTop: 135 }}
          />
        )}
        <Space direction="vertical" size={18} style={{ display: "flex" }}>
          {conversation.map((item) => {
            const user = item.role === "USER";
            return (
              <div
                key={item.id}
                style={{
                  display: "flex",
                  justifyContent: user ? "flex-end" : "flex-start",
                  gap: 10,
                }}
              >
                {!user && (
                  <Avatar
                    icon={<RobotOutlined />}
                    style={{ background: token.colorPrimary }}
                  />
                )}
                <div style={{ maxWidth: "82%" }}>
                  <div
                    style={{
                      borderRadius: token.borderRadiusLG,
                      padding: "10px 14px",
                      background: user ? token.colorPrimary : token.colorFillAlter,
                      color: user ? token.colorTextLightSolid : "inherit",
                    }}
                  >
                    {item.status === "streaming" && !item.content ? (
                      <Space size={8}>
                        <Spin size="small" />
                        <Text type="secondary">
                          {t("ai.model-debug.thinking", "正在生成…")}
                        </Text>
                      </Space>
                    ) : (
                      <Paragraph
                        style={{
                          margin: 0,
                          whiteSpace: "pre-wrap",
                          color: "inherit",
                        }}
                      >
                        {item.content}
                      </Paragraph>
                    )}
                  </div>
                  {item.status === "failed" && (
                    <Text type="danger">
                      {t("ai.model-debug.failed", "本轮调用失败")}
                    </Text>
                  )}
                </div>
                {user && <Avatar icon={<UserOutlined />} />}
              </div>
            );
          })}
        </Space>
      </div>
      <div style={{ paddingTop: 16 }}>
        <TextArea
          value={input}
          autoSize={{ minRows: 3, maxRows: 7 }}
          disabled={submitting}
          placeholder={t(
            "ai.model-debug.placeholder",
            "输入消息，Enter 发送，Shift + Enter 换行",
          )}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              void send();
            }
          }}
        />
        <div
          style={{ display: "flex", justifyContent: "flex-end", marginTop: 10 }}
        >
          {submitting ? (
            <TableActions
              selectedKeys={[]}
              selectedRows={[]}
              buttons={[
                {
                  key: "stopReceiving",
                  sort: 10,
                  text: t("ai.model-debug.stopReceiving", "停止接收"),
                  danger: true,
                  icon: <StopOutlined />,
                  onClick: () => requestRef.current?.abort(),
                },
              ]}
            />
          ) : (
            <TableActions
              selectedKeys={[]}
              selectedRows={[]}
              buttons={[
                {
                  key: "send",
                  sort: 20,
                  text: t("ai.model-debug.send", "发送"),
                  type: "primary",
                  icon: <SendOutlined />,
                  disabled: !authorized || !input.trim(),
                  onClick: () => void send(),
                },
              ]}
            />
          )}
        </div>
      </div>
    </Modal>
  );
};

export default ModelDebugDialog;
