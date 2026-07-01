import React, {useEffect, useMemo, useState} from "react";
import {
    App as AntApp,
    Avatar,
    Button,
    Card,
    Col,
    Descriptions,
    Divider,
    Form,
    List,
    Radio,
    Row,
    Segmented,
    Select,
    Space,
    Tag
} from "antd";
import {
    DesktopOutlined,
    FontSizeOutlined,
    GlobalOutlined,
    MoonOutlined,
    ReloadOutlined,
    SkinOutlined,
    SunOutlined,
    UserOutlined
} from "@ant-design/icons";
import {useI18n} from "@/layouts/i18n/useI18n";
import "./index.css";

const THEME_KEY = "sp.theme";
const SIZE_KEY = "sp.globalSize";

const readLS = <T, >(key: string, fallback: T): T => {
    try {
        return (localStorage.getItem(key) as T) || fallback;
    } catch {
        return fallback;
    }
};

const writeLS = (key: string, val: any) => {
    try {
        localStorage.setItem(key, val);
    } catch {
    }
};

const dispatchEvent = (name: string, detail: any) => {
    try {
        window.dispatchEvent(new CustomEvent(name, {detail}));
    } catch {
    }
};

export const Settings: React.FC = () => {
    const {t, languages = [], locale, setLocale, ensure} = useI18n();
    const {message} = AntApp.useApp();

    // 加载 settings 命名空间
    useEffect(() => {
        ensure(["settings"]).catch();
    }, [ensure, locale]);

    // 主题 & 尺寸
    const [themeMode, setThemeMode] = useState<"light" | "dark" | "system">(
        () => readLS(THEME_KEY, "light")
    );
    const [sizeMode, setSizeMode] = useState<"small" | "middle" | "large">(
        () => readLS(SIZE_KEY, "middle")
    );

    // 跟随系统主题
    const getSystemTheme = () =>
        window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";

    const resolvedTheme =
        themeMode === "system" ? getSystemTheme() : (themeMode as "light" | "dark");

    // 监听外部主题事件
    useEffect(() => {
        const handler = (e: any) => setThemeMode(e?.detail || "light");
        window.addEventListener("sp-set-theme", handler);
        return () => window.removeEventListener("sp-set-theme", handler);
    }, []);

    // 语言选项
    const languageOptions = useMemo(
        () => languages.map(l => ({label: l.name, value: l.code})),
        [languages]
    );

    // --- handlers ---
    const updateTheme = (val: any) => {
        writeLS(THEME_KEY, val);
        setThemeMode(val);
        dispatchEvent("sp-set-theme", val);
        message.success(t("settings.themeUpdated"));
    };

    const updateSize = (val: any) => {
        writeLS(SIZE_KEY, val);
        setSizeMode(val);
        dispatchEvent("sp-set-size", val);
        message.success(t("settings.sizeUpdated"));
    };

    const updateLang = (val: string) => {
        setLocale(val);
        message.success(t("settings.langUpdated"));
    };

    const resetAppearance = () => {
        writeLS(THEME_KEY, "light");
        writeLS(SIZE_KEY, "middle");
        setThemeMode("light");
        setSizeMode("middle");
        dispatchEvent("sp-set-theme", "light");
        dispatchEvent("sp-set-size", "middle");
        message.success(t("settings.resetApplied"));
    };

    const resetLang = () => {
        setLocale("zh-CN");
        message.success(t("settings.resetLangApplied"));
    };

    // 示例数据
    const sampleData = [
        {title: t("settings.sampleItem"), desc: t("settings.sampleDesc")},
        {title: t("settings.sampleItem"), desc: t("settings.sampleDesc")}
    ];

    return (
        <div className="settings-page">
            <Row gutter={[16, 16]}>
                {/* 外观设置 */}
                <Col xs={24} lg={12}>
                    <Card
                        className="settings-card"
                        title={
                            <Space>
                                <SkinOutlined/>
                                {t("settings.appearance")}
                            </Space>
                        }
                    >
                        <Form layout="vertical">
                            <Form.Item label={t("settings.theme")}>
                                <Segmented
                                    value={themeMode}
                                    onChange={v => updateTheme(v)}
                                    options={[
                                        {label: t("settings.light"), value: "light", icon: <SunOutlined/>},
                                        {label: t("settings.dark"), value: "dark", icon: <MoonOutlined/>},
                                        {label: t("settings.system"), value: "system", icon: <DesktopOutlined/>}
                                    ]}
                                />
                                <Space style={{marginTop: 8}} wrap>
                                    <Tag color="blue">
                                        {t("settings.theme")}:{" "}
                                        {themeMode === "system"
                                            ? t("settings.system")
                                            : t(`settings.${themeMode}`)}
                                    </Tag>
                                    <Tag>
                                        {t("settings.preview")}: {t(`settings.${resolvedTheme}`)}
                                    </Tag>
                                </Space>
                            </Form.Item>

                            <Form.Item label={t("settings.size")}>
                                <Radio.Group value={sizeMode} onChange={e => updateSize(e.target.value)}>
                                    <Radio.Button value="small">{t("size.small")}</Radio.Button>
                                    <Radio.Button value="middle">{t("size.middle")}</Radio.Button>
                                    <Radio.Button value="large">{t("size.large")}</Radio.Button>
                                </Radio.Group>
                            </Form.Item>

                            <Divider/>
                            <Button icon={<ReloadOutlined/>} onClick={resetAppearance}>
                                {t("action.reset")}
                            </Button>
                        </Form>
                    </Card>
                </Col>

                {/* 国际化 */}
                <Col xs={24} lg={12}>
                    <Card
                        className="settings-card"
                        title={
                            <Space>
                                <GlobalOutlined/>
                                {t("settings.i18n")}
                            </Space>
                        }
                    >
                        <Form layout="vertical">
                            <Form.Item label={t("settings.language")}>
                                <Select
                                    value={locale}
                                    options={languageOptions}
                                    onChange={updateLang}
                                    showSearch
                                    optionFilterProp="label"
                                />
                            </Form.Item>

                            <Form.Item label={t("settings.preview")}>
                                <List
                                    size="small"
                                    dataSource={sampleData}
                                    renderItem={(it, idx) => (
                                        <List.Item key={idx}>
                                            <List.Item.Meta
                                                avatar={<Avatar size={24} icon={<UserOutlined/>}/>}
                                                title={it.title}
                                                description={it.desc}
                                            />
                                        </List.Item>
                                    )}
                                />
                            </Form.Item>

                            <Divider/>
                            <Button onClick={resetLang}>{t("action.resetLang")}</Button>
                        </Form>
                    </Card>
                </Col>

                {/* 关于 */}
                <Col span={24}>
                    <Card
                        className="settings-card"
                        title={
                            <Space>
                                <FontSizeOutlined/>
                                {t("settings.about")}
                            </Space>
                        }
                    >
                        <Descriptions size="small" column={2}>
                            <Descriptions.Item label={t("about.version")}>v1.0.0</Descriptions.Item>
                            <Descriptions.Item label={t("about.license")}>
                                {t("about.licenseValue")}
                            </Descriptions.Item>
                            <Descriptions.Item label={t("about.repo")}>
                                <a
                                    href="https://github.com/simplepoint1024/open-simplepoint-dashboard-react"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                >
                                    github.com/simplepoint1024/open-simplepoint-dashboard-react
                                </a>
                            </Descriptions.Item>
                            <Descriptions.Item label={t("about.ui")}>Ant Design v5</Descriptions.Item>
                        </Descriptions>
                    </Card>
                </Col>
            </Row>
        </div>
    );
};
