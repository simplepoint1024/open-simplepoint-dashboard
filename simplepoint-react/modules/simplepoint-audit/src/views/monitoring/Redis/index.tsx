import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { ColumnsType, TablePaginationConfig } from "antd/es/table";
import { get, post, put } from "@simplepoint/shared/api/methods";
import { request } from "@simplepoint/shared/api/client";
import type { Page } from "@simplepoint/shared/types/request";
import { useI18n } from "@simplepoint/shared/hooks/useI18n";
import api from "@/api";

const { Paragraph, Text } = Typography;
const baseConfig = api["monitoring-redis"];

type RedisEntryType = "STRING" | "HASH" | "LIST" | "SET" | "ZSET" | "STREAM" | "UNKNOWN";

type RedisEntrySummary = {
  key: string;
  type: RedisEntryType;
  ttlSeconds?: number | null;
  persistent?: boolean | null;
  size?: number | null;
  valuePreview?: string;
  editable?: boolean | null;
};

type RedisEntryDetail = RedisEntrySummary & {
  value: string;
};

type RedisEditorValues = {
  key: string;
  value: string;
  persistent: boolean;
  ttlSeconds?: number | null;
};

const emptyPage = (): Page<RedisEntrySummary> => ({
  content: [],
  page: {
    size: 10,
    totalElements: 0,
    totalPages: 0,
    number: 0,
  },
});

const typeOptions: Array<{ label: string; value: RedisEntryType }> = [
  { label: "STRING", value: "STRING" },
  { label: "HASH", value: "HASH" },
  { label: "LIST", value: "LIST" },
  { label: "SET", value: "SET" },
  { label: "ZSET", value: "ZSET" },
  { label: "STREAM", value: "STREAM" },
  { label: "UNKNOWN", value: "UNKNOWN" },
];

const typeColorMap: Record<RedisEntryType, string> = {
  STRING: "green",
  HASH: "blue",
  LIST: "purple",
  SET: "orange",
  ZSET: "gold",
  STREAM: "cyan",
  UNKNOWN: "default",
};

const App = () => {
  const { ensure, locale, t } = useI18n();
  const [form] = Form.useForm<RedisEditorValues>();
  const persistent = Form.useWatch("persistent", form) ?? true;

  const [pattern, setPattern] = useState("");
  const [typeFilter, setTypeFilter] = useState<RedisEntryType | undefined>();
  const [pageData, setPageData] = useState<Page<RedisEntrySummary>>(emptyPage());
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [editorMode, setEditorMode] = useState<"create" | "edit">("create");
  const [detail, setDetail] = useState<RedisEntryDetail | null>(null);
  const pageRef = useRef<HTMLDivElement>(null);
  const controlsRef = useRef<HTMLDivElement>(null);
  const [tableScrollY, setTableScrollY] = useState(420);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  useLayoutEffect(() => {
    const updateTableHeight = () => {
      const pageHeight = pageRef.current?.getBoundingClientRect().height ?? 0;
      const controlsHeight = controlsRef.current?.getBoundingClientRect().height ?? 0;
      if (!pageHeight) {
        return;
      }
      const tableHeaderHeight = 55;
      const paginationHeight = 64;
      const verticalGaps = 24;
      setTableScrollY(Math.max(240, pageHeight - controlsHeight - tableHeaderHeight - paginationHeight - verticalGaps));
    };
    const observer = new ResizeObserver(updateTableHeight);
    if (pageRef.current) {
      observer.observe(pageRef.current);
    }
    if (controlsRef.current) {
      observer.observe(controlsRef.current);
    }
    updateTableHeight();
    return () => observer.disconnect();
  }, []);

  const buildListParams = useCallback(
    (pageNumber: number, pageSize: number, nextPattern = pattern, nextType = typeFilter) => {
      const params: Record<string, string | number> = {
        page: Math.max(pageNumber - 1, 0),
        size: pageSize,
      };
      const normalizedPattern = nextPattern.trim();
      if (normalizedPattern) {
        params.pattern = normalizedPattern;
      }
      if (nextType) {
        params.type = nextType;
      }
      return params;
    },
    [pattern, typeFilter]
  );

  const loadEntries = useCallback(
    async (pageNumber: number, pageSize: number) => {
      setLoading(true);
      try {
        const data = await get<Page<RedisEntrySummary>>(
          baseConfig.baseUrl,
          buildListParams(pageNumber, pageSize)
        );
        setPageData(data);
      } finally {
        setLoading(false);
      }
    },
    [buildListParams]
  );

  useEffect(() => {
    void loadEntries(1, 10);
  }, [loadEntries]);

  const loadDetail = useCallback(async (key: string) => {
    setDetailLoading(true);
    try {
      return await get<RedisEntryDetail>(`${baseConfig.baseUrl}/detail`, { key });
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const refreshCurrentPage = useCallback(async () => {
    await loadEntries(pageData.page.number + 1, pageData.page.size || 10);
  }, [loadEntries, pageData.page.number, pageData.page.size]);

  const handleSearch = useCallback(async () => {
    await loadEntries(1, pageData.page.size || 10);
  }, [loadEntries, pageData.page.size]);

  const openCreateEditor = useCallback(() => {
    setEditorMode("create");
    form.setFieldsValue({
      key: "",
      value: "",
      persistent: true,
      ttlSeconds: null,
    });
    setEditorOpen(true);
  }, [form]);

  const openEditEditor = useCallback(
    async (record: RedisEntrySummary) => {
      if (!record.editable) {
        message.warning(
          t(
            "monitoring.redis.editableOnlyString",
            "当前只支持编辑 STRING 类型的 Redis 键值对。"
          )
        );
        return;
      }
      const loadedDetail = await loadDetail(record.key);
      setEditorMode("edit");
      form.setFieldsValue({
        key: loadedDetail.key,
        value: loadedDetail.value ?? "",
        persistent: Boolean(loadedDetail.persistent),
        ttlSeconds: loadedDetail.persistent ? null : loadedDetail.ttlSeconds ?? null,
      });
      setEditorOpen(true);
    },
    [form, loadDetail, t]
  );

  const openDetailModal = useCallback(
    async (record: RedisEntrySummary) => {
      setDetail(null);
      const loadedDetail = await loadDetail(record.key);
      setDetail(loadedDetail);
      setDetailOpen(true);
    },
    [loadDetail]
  );

  const closeEditor = useCallback(() => {
    setEditorOpen(false);
    form.resetFields();
  }, [form]);

  const handleSubmit = useCallback(async () => {
    const values = await form.validateFields();
    const payload = {
      key: values.key.trim(),
      value: values.value ?? "",
      persistent: Boolean(values.persistent),
      ttlSeconds: values.persistent ? null : values.ttlSeconds ?? null,
    };
    setSaving(true);
    try {
      if (editorMode === "create") {
        await post<RedisEntryDetail>(baseConfig.baseUrl, payload);
        message.success(t("table.addSuccess", "新增成功"));
      } else {
        await put<RedisEntryDetail>(baseConfig.baseUrl, payload);
        message.success(t("table.editSuccess", "修改成功"));
      }
      closeEditor();
      await refreshCurrentPage();
    } finally {
      setSaving(false);
    }
  }, [closeEditor, editorMode, form, refreshCurrentPage, t]);

  const handleDelete = useCallback(
    async (keys: string[]) => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("keys", key));
      await request<string[]>(`${baseConfig.baseUrl}?${params.toString()}`, {
        method: "DELETE",
      });
      message.success(t("table.deleteSuccess", "删除成功"));
      const shouldMoveToPreviousPage =
        pageData.content.length <= keys.length && pageData.page.number > 0;
      const nextPageNumber = shouldMoveToPreviousPage
        ? pageData.page.number
        : pageData.page.number + 1;
      await loadEntries(nextPageNumber, pageData.page.size || 10);
    },
    [loadEntries, pageData.content.length, pageData.page.number, pageData.page.size, t]
  );

  const columns = useMemo<ColumnsType<RedisEntrySummary>>(
    () => [
      {
        title: t("monitoring.redis.key", "Key"),
        dataIndex: "key",
        key: "key",
        width: 260,
        render: (value: string) => (
          <Text code copyable={{ text: value }}>
            {value}
          </Text>
        ),
      },
      {
        title: t("monitoring.redis.type", "Type"),
        dataIndex: "type",
        key: "type",
        width: 110,
        render: (value: RedisEntryType) => <Tag color={typeColorMap[value]}>{value}</Tag>,
      },
      {
        title: t("monitoring.redis.ttl", "TTL"),
        dataIndex: "ttlSeconds",
        key: "ttlSeconds",
        width: 140,
        render: (_value, record) => {
          if (record.persistent) {
            return <Tag color="success">{t("monitoring.redis.persistent", "Permanent")}</Tag>;
          }
          if (record.ttlSeconds == null) {
            return "--";
          }
          return `${record.ttlSeconds}s`;
        },
      },
      {
        title: t("monitoring.redis.size", "Size"),
        dataIndex: "size",
        key: "size",
        width: 100,
        render: (value?: number | null) => value ?? "--",
      },
      {
        title: t("monitoring.redis.valuePreview", "Value Preview"),
        dataIndex: "valuePreview",
        key: "valuePreview",
        render: (value?: string) => (
          <Paragraph
            style={{ marginBottom: 0, whiteSpace: "pre-wrap" }}
            ellipsis={{ rows: 2, tooltip: value || "--" }}
          >
            {value || "--"}
          </Paragraph>
        ),
      },
      {
        title: t("monitoring.redis.actions", "Actions"),
        key: "actions",
        width: 180,
        render: (_value, record) => (
          <Space size="small" wrap>
            <Button type="link" onClick={() => void openDetailModal(record)}>
              {t("monitoring.redis.view", "查看")}
            </Button>
            <Button
              type="link"
              disabled={!record.editable}
              onClick={() => void openEditEditor(record)}
            >
              {t("table.button.edit", "编辑")}
            </Button>
            <Popconfirm
              title={t("table.confirmDeleteTitle", "确认删除")}
              description={t("monitoring.redis.deleteConfirm", "确认删除当前 Redis 键？")}
              onConfirm={() => void handleDelete([record.key])}
            >
              <Button type="link" danger>
                {t("table.button.delete", "删除")}
              </Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [handleDelete, openDetailModal, openEditEditor, t]
  );

  const pagination = useMemo<TablePaginationConfig>(
    () => ({
      current: pageData.page.number + 1,
      pageSize: pageData.page.size || 10,
      total: pageData.page.totalElements || 0,
      showSizeChanger: true,
      showQuickJumper: true,
      pageSizeOptions: ["10", "20", "50", "100"],
      showTotal: (total) => t("table.total", "共 {total} 条", { total }),
    }),
    [pageData.page.number, pageData.page.size, pageData.page.totalElements, t]
  );

  return (
    <div ref={pageRef} style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0, gap: 12 }}>
      <div ref={controlsRef} style={{ display: "flex", flexDirection: "column", gap: 12, flexShrink: 0 }}>
        <Alert
          showIcon
          type="info"
          message={t("monitoring.redis.noticeTitle", "Redis 管理")}
          description={t(
            "monitoring.redis.noticeDesc",
            "列表使用 SCAN 查询当前 Redis 键；支持查看常见 Redis 类型，新增/编辑当前先支持 STRING 类型键值对。"
          )}
        />

        <Card>
          <Space wrap>
            <Input
              allowClear
              value={pattern}
              onChange={(event) => setPattern(event.target.value)}
              onPressEnter={() => void handleSearch()}
              placeholder={t(
                "monitoring.redis.patternPlaceholder",
                "输入 Redis 模式，如 simplepoint:*"
              )}
              style={{ width: 280 }}
            />
            <Select<RedisEntryType>
              allowClear
              value={typeFilter}
              onChange={(value) => setTypeFilter(value)}
              placeholder={t("monitoring.redis.typePlaceholder", "选择数据类型")}
              options={typeOptions}
              style={{ width: 180 }}
            />
            <Button type="primary" onClick={() => void handleSearch()}>
              {t("action.search", "查询")}
            </Button>
            <Button onClick={() => void refreshCurrentPage()}>
              {t("action.refresh", "刷新")}
            </Button>
            <Button type="primary" ghost onClick={openCreateEditor}>
              {t("table.button.add", "新增")}
            </Button>
          </Space>
        </Card>
      </div>

      <Card
        style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}
        bodyStyle={{ padding: 0, flex: 1, minHeight: 0 }}
      >
        <Table<RedisEntrySummary>
          rowKey="key"
          loading={loading}
          columns={columns}
          dataSource={pageData.content}
          pagination={pagination}
          scroll={{ x: 980, y: tableScrollY }}
          onChange={(nextPagination) =>
            void loadEntries(nextPagination.current ?? 1, nextPagination.pageSize ?? 10)
          }
        />
      </Card>

      <Modal
        destroyOnClose
        open={editorOpen}
        title={editorMode === "create"
          ? t("monitoring.redis.createTitle", "新增 Redis 键值")
          : t("monitoring.redis.editTitle", "编辑 Redis 键值")}
        onCancel={closeEditor}
        onOk={() => void handleSubmit()}
        confirmLoading={saving}
        width={720}
      >
        <Form form={form} layout="vertical" initialValues={{ persistent: true }}>
          <Form.Item
            label={t("monitoring.redis.key", "Key")}
            name="key"
            rules={[
              { required: true, message: t("form.required", "这是必填项") },
              {
                validator: async (_rule, value: string | undefined) => {
                  if (typeof value === "string" && value.trim()) {
                    return;
                  }
                  throw new Error(t("form.required", "这是必填项"));
                },
              },
            ]}
          >
            <Input disabled={editorMode === "edit"} placeholder="simplepoint:demo:key" />
          </Form.Item>

          <Form.Item
            label={t("monitoring.redis.value", "Value")}
            name="value"
            rules={[
              {
                validator: async (_rule, value: string | undefined) => {
                  if (value !== undefined && value !== null) {
                    return;
                  }
                  throw new Error(t("form.required", "这是必填项"));
                },
              },
            ]}
          >
            <Input.TextArea autoSize={{ minRows: 8, maxRows: 16 }} />
          </Form.Item>

          <Space wrap size={16} align="start" style={{ display: "flex" }}>
            <Form.Item
              label={t("monitoring.redis.persistent", "Permanent")}
              name="persistent"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            {!persistent && (
              <Form.Item
                label={t("monitoring.redis.ttlSeconds", "TTL Seconds")}
                name="ttlSeconds"
                rules={[
                  { required: true, message: t("form.required", "这是必填项") },
                  {
                    validator: async (_rule, value: number | null | undefined) => {
                      if (typeof value === "number" && value > 0) {
                        return;
                      }
                      throw new Error(
                        t("monitoring.redis.ttlRule", "TTL 必须是大于 0 的秒数")
                      );
                    },
                  },
                ]}
              >
                <InputNumber min={1} precision={0} style={{ width: 220 }} />
              </Form.Item>
            )}
          </Space>
        </Form>
      </Modal>

      <Modal
        footer={null}
        open={detailOpen}
        title={t("monitoring.redis.detailTitle", "Redis 键详情")}
        onCancel={() => setDetailOpen(false)}
        width={820}
      >
        {detailLoading ? null : detail ? (
          <Space direction="vertical" size={16} style={{ display: "flex" }}>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label={t("monitoring.redis.key", "Key")} span={2}>
                <Text code copyable={{ text: detail.key }}>
                  {detail.key}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label={t("monitoring.redis.type", "Type")}>
                <Tag color={typeColorMap[detail.type]}>{detail.type}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t("monitoring.redis.editable", "Editable")}>
                {detail.editable ? t("common.yes", "是") : t("common.no", "否")}
              </Descriptions.Item>
              <Descriptions.Item label={t("monitoring.redis.ttl", "TTL")}>
                {detail.persistent
                  ? t("monitoring.redis.persistent", "Permanent")
                  : detail.ttlSeconds == null
                    ? "--"
                    : `${detail.ttlSeconds}s`}
              </Descriptions.Item>
              <Descriptions.Item label={t("monitoring.redis.size", "Size")}>
                {detail.size ?? "--"}
              </Descriptions.Item>
            </Descriptions>

            <Input.TextArea
              readOnly
              autoSize={{ minRows: 10, maxRows: 20 }}
              value={detail.value || ""}
            />
          </Space>
        ) : null}
      </Modal>
    </div>
  );
};

export default App;
