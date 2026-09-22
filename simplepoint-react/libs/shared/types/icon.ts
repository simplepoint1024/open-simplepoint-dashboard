import * as AntIcons from '@ant-design/icons';
import React from 'react';
import type {ElementType} from 'react';

// UI code imports named icons from this shared module as well. Keeping both
// named icons and the data-driven menu registry behind the same MF singleton
// prevents each remote from bundling another copy of Ant Design icon data.
export {
  AimOutlined, ApartmentOutlined, ApiOutlined, AppstoreOutlined, ArrowDownOutlined,
  ArrowLeftOutlined, ArrowUpOutlined, BellOutlined, BranchesOutlined, BugOutlined,
  BulbOutlined, CaretRightOutlined, CheckCircleOutlined, CheckOutlined, ClearOutlined,
  CloseCircleOutlined, CloseOutlined, CloudServerOutlined, CloudUploadOutlined,
  CodeOutlined, CopyOutlined, CreditCardOutlined, DashboardOutlined, DatabaseOutlined,
  DeleteOutlined, DeploymentUnitOutlined, DesktopOutlined, DoubleLeftOutlined,
  DoubleRightOutlined, DownOutlined, DownloadOutlined, EditOutlined, ExperimentOutlined,
  EyeOutlined, FileTextOutlined, FilterFilled, FilterOutlined, FontSizeOutlined,
  ForkOutlined, FormOutlined, FullscreenExitOutlined, FullscreenOutlined, GithubOutlined,
  GlobalOutlined, HistoryOutlined, HolderOutlined, HomeOutlined, IdcardOutlined,
  InboxOutlined, KeyOutlined, LayoutOutlined, LinkOutlined, LoadingOutlined, LockOutlined,
  LogoutOutlined, MailOutlined, MenuFoldOutlined, MenuUnfoldOutlined, MessageOutlined,
  MinusCircleOutlined, MoonOutlined, MoreOutlined, PartitionOutlined, PauseCircleOutlined,
  PhoneOutlined, PieChartOutlined, PlayCircleOutlined, PlusOutlined,
  QuestionCircleOutlined, RedoOutlined, ReloadOutlined, RightOutlined, RobotOutlined,
  RocketOutlined, SafetyCertificateOutlined, SaveOutlined, SearchOutlined, SendOutlined,
  SettingOutlined, SkinOutlined, StopOutlined, SunOutlined, TableOutlined, TeamOutlined,
  ThunderboltOutlined, ToolOutlined, UndoOutlined, UploadOutlined, UserOutlined,
  VerticalLeftOutlined, VerticalRightOutlined, WarningOutlined,
} from '@ant-design/icons';

// Keep the complete icon registry in one shared provider bundle. Menu rendering
// is synchronous and never starts an icon-specific network request.
const iconComponents = AntIcons as unknown as Record<string, ElementType>;

export const outlinedIconNames = Object.keys(iconComponents)
  .filter(name => name.endsWith('Outlined'))
  .sort();

export const createIcon = (name: string) => {
  const Icon = iconComponents[name];
  return Icon ? React.createElement(Icon) : null;
};
