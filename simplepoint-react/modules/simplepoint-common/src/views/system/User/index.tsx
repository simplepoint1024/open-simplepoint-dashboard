import SimpleTable from "@simplepoint/components/SimpleTable";
import api from '@/api/index';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import React, {useCallback, useEffect, useMemo, useState} from "react";
import {Avatar, Drawer} from "antd";
import {UserOutlined} from '@ant-design/icons';
import RoleConfig from "./config/role";

const baseConfig = api['rbac-users'];

const App = () => {
    const {t, ensure, locale} = useI18n();

    const [userId, setUserId] = useState<string | null>(null);
    const [open, setOpen] = useState(false);

    /** Drawer 高度（可拖拽） */
    const [drawerHeight, setDrawerHeight] = useState(480);

    /** Drawer 关闭时重置高度 */
    useEffect(() => {
        if (!open) setDrawerHeight(480);
    }, [open]);

    /** 拖拽调整 Drawer 高度 */
    const startResize = useCallback(
        (e: React.MouseEvent) => {
            e.preventDefault();
            const startY = e.clientY;
            const startHeight = drawerHeight;

            const minHeight = 240;
            const maxHeight = Math.max(320, window.innerHeight - 80);

            const onMove = (me: MouseEvent) => {
                const delta = startY - me.clientY;
                let next = startHeight + delta;
                if (next < minHeight) next = minHeight;
                if (next > maxHeight) next = maxHeight;
                setDrawerHeight(next);
            };

            const onUp = () => {
                window.removeEventListener("mousemove", onMove);
                window.removeEventListener("mouseup", onUp);
            };

            window.addEventListener("mousemove", onMove);
            window.addEventListener("mouseup", onUp);
        },
        [drawerHeight]
    );

    /** 加载 i18n 命名空间（只执行一次） */
    useEffect(() => {
        ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    /** SimpleTable 自定义按钮事件 */
    const customButtonEvents = {
        "config.role": (_keys: React.Key[], rows: any[]) => {
            const id = rows?.[0]?.id;
            if (!id) return;
            setUserId(id);
            setOpen(true);
        },
    } as const;

    const columnOverrides = useMemo(() => ({
        picture: {
            order: -100,
            width: 72,
            align: 'center' as const,
            sorter: false,
            render: (value: unknown, record: any) => (
                <Avatar
                    size={34}
                    src={typeof value === 'string' && value ? value : undefined}
                    icon={!value ? <UserOutlined/> : undefined}
                    alt={record?.nickname || record?.name || t('user.defaultName', '用户')}
                />
            ),
        },
    }), [t]);

    const formUiSchema = useMemo(() => ({
        picture: {
            'ui:widget': 'OssImage',
            'ui:options': {
                directory: 'avatars/users',
                sourceServiceName: 'rbac-avatar',
                shape: 'circle',
                maxSizeMb: 5,
            },
        },
    }), []);

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                customButtonEvents={customButtonEvents}
                columnOverrides={columnOverrides}
                formUiSchema={formUiSchema}
            />

            <Drawer
                title={t("users.button.config.role")}
                open={open}
                onClose={() => {
                    setOpen(false);
                    setUserId(null);
                }}
                placement="bottom"
                maskClosable={false}
                height={drawerHeight}
                destroyOnHidden
                styles={{
                    body: {position: "relative", paddingTop: 12},
                }}
            >
                {/* 拖拽句柄 */}
                <div
                    style={{
                        position: "absolute",
                        top: 0,
                        left: 0,
                        right: 0,
                        height: 8,
                        cursor: "ns-resize",
                        zIndex: 10,
                    }}
                    onMouseDown={startResize}
                />

                {/* ⭐ 不再使用 key 强制重建，避免闪退 */}
                {userId && <RoleConfig userId={userId}/>}
            </Drawer>
        </div>
    );
};

export default App;
