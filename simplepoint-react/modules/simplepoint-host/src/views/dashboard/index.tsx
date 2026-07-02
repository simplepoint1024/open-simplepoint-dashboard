import React, {useEffect, useMemo} from 'react';
import {Badge, Card, Col, Row, Tag, Timeline, Typography} from 'antd';
import {
  AppstoreOutlined,
  BulbOutlined,
  CloudServerOutlined,
  CodeOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  GlobalOutlined,
  LayoutOutlined,
  LockOutlined,
  PartitionOutlined,
  PieChartOutlined,
  SettingOutlined,
  TableOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import {useI18n} from '@/layouts/i18n/useI18n';

const {Title, Text} = Typography;

interface FeatureItem {
  label: string;
  new?: boolean;
}

interface FeatureCategory {
  icon: React.ReactNode;
  title: string;
  color: string;
  features: FeatureItem[];
}

export const Dashboard: React.FC = () => {
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['host-dashboard']);
  }, [ensure, locale]);

  const greeting = (() => {
    const h = new Date().getHours();
    if (h < 6)  return t('host.dashboard.greeting.early',    '夜深了，注意休息');
    if (h < 12) return t('host.dashboard.greeting.morning',  '早上好');
    if (h < 18) return t('host.dashboard.greeting.afternoon', '下午好');
    return             t('host.dashboard.greeting.evening',  '晚上好');
  })();

  const CATEGORIES = useMemo<FeatureCategory[]>(() => [
    {
      icon: <LockOutlined />,
      title: t('host.dashboard.category.security', '权限与安全'),
      color: '#1677ff',
      features: [
        {label: t('host.dashboard.feature.security.rbac', 'RBAC 角色权限体系')},
        {label: t('host.dashboard.feature.security.management', '用户 / 角色 / 权限 / 菜单管理')},
        {label: t('host.dashboard.feature.security.oidc', 'OAuth2 / OIDC 授权服务器')},
        {label: t('host.dashboard.feature.security.jwt', 'JWT 资源服务器')},
        {label: t('host.dashboard.feature.security.buttonLevel', '操作权限按钮级控制')},
      ],
    },
    {
      icon: <TeamOutlined />,
      title: t('host.dashboard.category.multiTenant', '多租户'),
      color: '#52c41a',
      features: [
        {label: t('host.dashboard.feature.multiTenant.isolation', '租户隔离数据层')},
        {label: t('host.dashboard.feature.multiTenant.context', '租户上下文自动注入')},
        {label: t('host.dashboard.feature.multiTenant.api', '租户管理 REST API')},
        {label: t('host.dashboard.feature.multiTenant.switch', '前端租户切换')},
      ],
    },
    {
      icon: <PartitionOutlined />,
      title: t('host.dashboard.category.microFrontend', '微前端'),
      color: '#722ed1',
      features: [
        {label: t('host.dashboard.feature.microFrontend.federation', 'Module Federation (Rsbuild)')},
        {label: t('host.dashboard.feature.microFrontend.dynamicRoutes', '动态路由从后端菜单加载')},
        {label: t('host.dashboard.feature.microFrontend.apps', 'Host + Common + Audit + DNA 四端')},
        {label: t('host.dashboard.feature.microFrontend.skeleton', '骨架屏 + 懒加载边界')},
        {label: t('host.dashboard.feature.microFrontend.tabs', '可拖拽排序 Tab 页签')},
      ],
    },
    {
      icon: <TableOutlined />,
      title: t('host.dashboard.category.smartTable', '智能表格'),
      color: '#fa8c16',
      features: [
        {label: t('host.dashboard.feature.smartTable.visibility', '列显示 / 隐藏')},
        {label: t('host.dashboard.feature.smartTable.freeze', '列冻结 (左 / 右)')},
        {label: t('host.dashboard.feature.smartTable.drag', '列拖拽排序')},
        {label: t('host.dashboard.feature.smartTable.resize', '列宽拖拽调整')},
        {label: t('host.dashboard.feature.smartTable.boolean', '布尔值 Tag 渲染')},
        {label: t('host.dashboard.feature.smartTable.dict', '字典选项自动映射')},
        {label: t('host.dashboard.feature.smartTable.empty', '空状态自定义')},
        {label: t('host.dashboard.feature.smartTable.persist', '表格设置后端持久化'), new: true},
      ],
    },
    {
      icon: <SettingOutlined />,
      title: t('host.dashboard.category.userPreference', '用户偏好设置'),
      color: '#eb2f96',
      features: [
        {label: t('host.dashboard.feature.userPreference.storage', '每用户每页面独立存储'), new: true},
        {label: t('host.dashboard.feature.userPreference.dual', 'localStorage + 后端双写'), new: true},
        {label: t('host.dashboard.feature.userPreference.debounce', '800ms 防抖批量保存'), new: true},
        {label: t('host.dashboard.feature.userPreference.sync', '跨设备偏好同步'), new: true},
        {label: t('host.dashboard.feature.userPreference.api', 'GET / PUT / DELETE REST API'), new: true},
      ],
    },
    {
      icon: <LayoutOutlined />,
      title: t('host.dashboard.category.layoutNav', '布局与导航'),
      color: '#13c2c2',
      features: [
        {label: t('host.dashboard.feature.layoutNav.sidebarDrag', '侧边菜单宽度可拖动调整'), new: true},
        {label: t('host.dashboard.feature.layoutNav.collapse', '菜单折叠 / 展开')},
        {label: t('host.dashboard.feature.layoutNav.breadcrumb', '面包屑导航')},
        {label: t('host.dashboard.feature.layoutNav.github', '顶栏 GitHub 快速跳转')},
        {label: t('host.dashboard.feature.layoutNav.notification', '通知中心')},
        {label: t('host.dashboard.feature.layoutNav.shortcuts', '快捷键面板 (?)')},
        {label: t('host.dashboard.feature.layoutNav.mobile', '移动端响应式适配')},
      ],
    },
    {
      icon: <DatabaseOutlined />,
      title: t('host.dashboard.category.dna', 'DNA 数据平台'),
      color: '#f5222d',
      features: [
        {label: t('host.dashboard.feature.dna.multiSource', '多数据源联邦管理')},
        {label: t('host.dashboard.feature.dna.calcite', 'Apache Calcite SQL 联邦查询')},
        {label: t('host.dashboard.feature.dna.jdbc', 'JDBC 驱动代理')},
        {label: t('host.dashboard.feature.dna.catalog', '数据目录 / 数据集')},
        {label: t('host.dashboard.feature.dna.quality', '数据质量规则')},
        {label: t('host.dashboard.feature.dna.console', 'SQL 控制台')},
      ],
    },
    {
      icon: <DeploymentUnitOutlined />,
      title: t('host.dashboard.category.plugin', '插件体系'),
      color: '#faad14',
      features: [
        {label: t('host.dashboard.feature.plugin.classloader', 'PluginClassloader 隔离加载')},
        {label: t('host.dashboard.feature.plugin.config', 'plugin.yaml Manifest 声明')},
        {label: t('host.dashboard.feature.plugin.spring', 'Spring Bean 自动注册')},
        {label: t('host.dashboard.feature.plugin.servlet', 'Servlet 端点动态映射')},
        {label: t('host.dashboard.feature.plugin.hotload', '/plugins 端点热加载')},
      ],
    },
    {
      icon: <GlobalOutlined />,
      title: t('host.dashboard.category.i18n', '国际化'),
      color: '#1890ff',
      features: [
        {label: t('host.dashboard.feature.i18n.plugin', 'i18n 插件多语言')},
        {label: t('host.dashboard.feature.i18n.backend', '后端消息 JSON 自动注册')},
        {label: t('host.dashboard.feature.i18n.frontend', '前端 useI18n / spI18n')},
        {label: t('host.dashboard.feature.i18n.persist', '语言切换持久化')},
      ],
    },
    {
      icon: <FileTextOutlined />,
      title: t('host.dashboard.category.form', '表单引擎'),
      color: '#2f54eb',
      features: [
        {label: t('host.dashboard.feature.form.schema', 'JSON Schema 自动生成')},
        {label: t('host.dashboard.feature.form.rjsf', 'RJSF + AntD 渲染')},
        {label: t('host.dashboard.feature.form.dict', '字典下拉 x-ui.dictCode')},
        {label: t('host.dashboard.feature.form.button', '按钮声明 @ButtonDeclarations')},
        {label: t('host.dashboard.feature.form.permission', '权限按钮过滤')},
      ],
    },
    {
      icon: <CloudServerOutlined />,
      title: t('host.dashboard.category.infra', '基础设施'),
      color: '#389e0d',
      features: [
        {label: t('host.dashboard.feature.infra.boot', 'Spring Boot 4 / Spring Cloud')},
        {label: t('host.dashboard.feature.infra.consul', 'Consul 配置中心')},
        {label: t('host.dashboard.feature.infra.redis', 'Redis WebSession / 缓存')},
        {label: t('host.dashboard.feature.infra.amqp', 'Service Router 服务间调用')},
        {label: t('host.dashboard.feature.infra.docker', 'Docker Swarm 编排')},
      ],
    },
    {
      icon: <PieChartOutlined />,
      title: t('host.dashboard.category.observability', '可观测性'),
      color: '#d46b08',
      features: [
        {label: t('host.dashboard.feature.observability.actuator', 'Spring Actuator')},
        {label: t('host.dashboard.feature.observability.audit', '操作审计日志')},
        {label: t('host.dashboard.feature.observability.coverage', 'JaCoCo 单元测试覆盖率')},
        {label: t('host.dashboard.feature.observability.checkstyle', 'Checkstyle 代码规范')},
      ],
    },
  ], [t]);

  const CHANGELOG = useMemo(() => [
    {
      version: '1.0.0',
      date: '2025',
      items: [
        t('host.dashboard.changelog.v100.persist', '表格列设置后端持久化（每用户每页面独立）'),
        t('host.dashboard.changelog.v100.sidebarDrag', '侧边菜单宽度可拖拽调整（用户隔离存储）'),
        t('host.dashboard.changelog.v100.preferenceApi', '用户偏好 REST API（GET / PUT / DELETE）'),
        t('host.dashboard.changelog.v100.dnaMock', 'DNA 微前端菜单 Mock 补全（18 条目）'),
        t('host.dashboard.changelog.v100.tableSettings', '表格设置：拖拽排序 / 冻结 / 显示隐藏'),
        t('host.dashboard.changelog.v100.layout', '面包屑 / Tab 拖拽排序 / 动画优化'),
        t('host.dashboard.changelog.v100.notification', '通知中心 + 快捷键面板'),
        t('host.dashboard.changelog.v100.form', 'RJSF 表单完整 AntD 样式'),
        t('host.dashboard.changelog.v100.errorPages', '404 / 403 错误页面'),
        t('host.dashboard.changelog.v100.mobile', '移动端响应式布局'),
        t('host.dashboard.changelog.v100.version', '全局版本统一 1.0.0'),
      ],
    },
  ], [t]);

  return (
    <div style={{padding: 24, height: '100%', overflowY: 'auto', boxSizing: 'border-box'}}>

      {/* 欢迎横幅 */}
      <Card
        style={{marginBottom: 24, background: 'linear-gradient(135deg, #1677ff 0%, #4096ff 100%)', border: 'none'}}
        styles={{body: {padding: '24px 32px'}}}
      >
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12}}>
          <div>
            <Title level={4} style={{color: '#fff', margin: 0}}>
              {t('host.dashboard.banner.welcome', '{greeting}，欢迎使用 Simple·Point 🎉', {greeting})}
            </Title>
            <Text style={{color: 'rgba(255,255,255,0.85)', fontSize: 13, marginTop: 4, display: 'block'}}>
              {t('host.dashboard.banner.subtitle', '企业级全栈框架 · 开箱即用的权限、多租户、插件体系')}
            </Text>
          </div>
          <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
            <Tag color="white" style={{color: '#1677ff', fontWeight: 600, fontSize: 13, padding: '2px 12px'}}>
              v1.0.0
            </Tag>
            <Tag color="rgba(255,255,255,0.2)" style={{color: '#fff', fontSize: 12, padding: '2px 10px', border: '1px solid rgba(255,255,255,0.4)'}}>
              Spring Boot 4
            </Tag>
            <Tag color="rgba(255,255,255,0.2)" style={{color: '#fff', fontSize: 12, padding: '2px 10px', border: '1px solid rgba(255,255,255,0.4)'}}>
              React 19
            </Tag>
            <Tag color="rgba(255,255,255,0.2)" style={{color: '#fff', fontSize: 12, padding: '2px 10px', border: '1px solid rgba(255,255,255,0.4)'}}>
              Module Federation
            </Tag>
          </div>
        </div>
      </Card>

      {/* 功能矩阵 */}
      <Card
        title={
          <span>
            <AppstoreOutlined style={{marginRight: 8, color: '#1677ff'}} />
            {t('host.dashboard.card.features', 'v1.0.0 功能全景')}
          </span>
        }
        style={{marginBottom: 24}}
      >
        <Row gutter={[16, 16]}>
          {CATEGORIES.map(cat => (
            <Col xs={24} sm={12} xl={8} key={cat.title}>
              <Card
                size="small"
                title={
                  <span style={{color: cat.color, fontWeight: 600}}>
                    <span style={{marginRight: 6}}>{cat.icon}</span>
                    {cat.title}
                  </span>
                }
                styles={{body: {padding: '8px 12px'}, header: {minHeight: 38, padding: '0 12px'}}}
                style={{height: '100%', borderTop: `3px solid ${cat.color}`}}
              >
                <ul style={{margin: 0, paddingLeft: 16, listStyle: 'none'}}>
                  {cat.features.map(f => (
                    <li key={f.label} style={{padding: '3px 0', fontSize: 13, display: 'flex', alignItems: 'center', gap: 6}}>
                      <span style={{color: cat.color, fontSize: 8, flexShrink: 0}}>●</span>
                      <span>{f.label}</span>
                      {f.new && (
                        <Badge
                          count="NEW"
                          style={{backgroundColor: '#f5222d', fontSize: 10, height: 16, lineHeight: '16px', padding: '0 4px', minWidth: 0}}
                        />
                      )}
                    </li>
                  ))}
                </ul>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      {/* 技术栈 */}
      <Card
        title={
          <span>
            <CodeOutlined style={{marginRight: 8, color: '#722ed1'}} />
            {t('host.dashboard.card.techStack', '核心技术栈')}
          </span>
        }
        style={{marginBottom: 24}}
      >
        <Row gutter={[24, 0]}>
          {/* 后端 */}
          <Col xs={24} md={12} style={{borderRight: '1px solid #f0f0f0', paddingRight: 24}}>
            <Text strong style={{display: 'block', marginBottom: 10, fontSize: 13, color: '#555'}}>
              {t('host.dashboard.techStack.backend', '🖥️ 后端')}
            </Text>
            <Row gutter={[6, 6]}>
              {[
                ['Spring Boot 4',              '#1677ff'],
                ['Spring Security 7',          '#1677ff'],
                ['Spring Cloud 2025',          '#1677ff'],
                ['Spring Authorization Server','#1677ff'],
                ['Spring Data JPA',            '#52c41a'],
                ['Hibernate 6',                '#52c41a'],
                ['Apache Calcite',             '#fa8c16'],
                ['Service Router',             '#fa8c16'],
                ['Redis',                      '#f5222d'],
                ['Consul',                     '#13c2c2'],
                ['Lombok',                     '#389e0d'],
                ['Hutool',                     '#389e0d'],
                ['Kotlin DSL',                 '#7b68ee'],
                ['Docker Swarm',               '#2496ed'],
              ].map(([name, color]) => (
                <Col key={name}>
                  <Tag color={color as string} style={{fontSize: 12, padding: '2px 10px', marginRight: 0}}>
                    {name}
                  </Tag>
                </Col>
              ))}
            </Row>
          </Col>

          {/* 前端 */}
          <Col xs={24} md={12} style={{paddingLeft: 24, marginTop: 0}}>
            <Text strong style={{display: 'block', marginBottom: 10, fontSize: 13, color: '#555'}}>
              {t('host.dashboard.techStack.frontend', '🌐 前端')}
            </Text>
            <Row gutter={[6, 6]}>
              {[
                ['React 19',          '#1677ff'],
                ['TypeScript 5',      '#1677ff'],
                ['Ant Design 5',      '#1677ff'],
                ['Module Federation', '#722ed1'],
                ['Rsbuild',           '#722ed1'],
                ['Nx Monorepo',       '#722ed1'],
                ['RJSF',              '#eb2f96'],
                ['@dnd-kit',          '#eb2f96'],
                ['Tanstack Query',    '#faad14'],
                ['react-resizable',   '#fa8c16'],
                ['MSW (Mock)',        '#389e0d'],
                ['pnpm Workspace',    '#f59e0b'],
              ].map(([name, color]) => (
                <Col key={name}>
                  <Tag color={color as string} style={{fontSize: 12, padding: '2px 10px', marginRight: 0}}>
                    {name}
                  </Tag>
                </Col>
              ))}
            </Row>
          </Col>
        </Row>
      </Card>

      {/* 更新日志 */}
      <Card
        title={
          <span>
            <BulbOutlined style={{marginRight: 8, color: '#fa8c16'}} />
            {t('host.dashboard.card.changelog', '更新日志')}
          </span>
        }
      >
        {CHANGELOG.map(entry => (
          <div key={entry.version}>
            <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12}}>
              <Tag color="#1677ff" style={{fontWeight: 700, fontSize: 14, padding: '2px 14px'}}>
                v{entry.version}
              </Tag>
              <Text type="secondary" style={{fontSize: 12}}>{entry.date}</Text>
            </div>
            <Timeline
              items={entry.items.map(item => ({
                children: <Text style={{fontSize: 13}}>{item}</Text>,
                color: '#1677ff',
              }))}
            />
          </div>
        ))}
      </Card>

    </div>
  );
};
