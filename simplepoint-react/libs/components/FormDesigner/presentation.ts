/** Presentation metadata is independent of the server's versioned component capability contract. */
const icons: Record<string, string> = {
  'display.divider': 'MinusOutlined', 'display.image': 'PictureOutlined', 'display.alert': 'InfoCircleOutlined',
  'display.progress': 'DashboardOutlined', 'display.icon': 'StarOutlined', 'display.markdown': 'FileMarkdownOutlined',
  'field.rating': 'StarOutlined', 'field.cascader': 'BranchesOutlined', 'field.transfer': 'SwapOutlined',
  'data.detail': 'ProfileOutlined',
  'layout.matrix': 'AppstoreOutlined', 'layout.cell': 'BorderOutlined',
  'layout.form': 'FormOutlined', 'layout.grid': 'TableOutlined', 'layout.group': 'GroupOutlined', 'layout.tabs': 'FolderOutlined',
  'display.text': 'FontSizeOutlined', 'field.text': 'FontSizeOutlined', 'field.textarea': 'AlignLeftOutlined',
  'field.json': 'CodeOutlined', 'field.switch': 'CheckSquareOutlined', 'field.number': 'NumberOutlined',
  'field.select': 'UnorderedListOutlined', 'field.date': 'CalendarOutlined', 'field.time': 'ClockCircleOutlined',
  'field.datetime': 'FieldTimeOutlined', 'field.user': 'UserOutlined', 'field.role': 'SafetyCertificateOutlined',
  'field.organization': 'ApartmentOutlined', 'field.tenant': 'TeamOutlined', 'field.attachment': 'PaperClipOutlined',
  'action.save': 'SaveOutlined', 'action.submit': 'SendOutlined', 'action.reset': 'ReloadOutlined',
  'action.open-dialog': 'ExpandOutlined', 'action.open-drawer': 'LayoutOutlined', 'action.close': 'CloseOutlined',
  'action.apply': 'CheckOutlined',
};
export const componentIcon = (type: string) => icons[type] ?? 'AppstoreOutlined';
export function componentGroup(type: string) {
  if (type.startsWith('layout.') || type.startsWith('display.')) return 'layout';
  if (type.startsWith('action.')) return 'actions';
  if (['data.detail', 'field.user', 'field.role', 'field.organization', 'field.tenant', 'field.attachment'].includes(type)) return 'business';
  return 'fields';
}
export const designerFormContext = {
  labelCol: {flex: '100px'}, wrapperCol: {flex: '1 1 0'}, labelAlign: 'left',
  rowGutter: 0, descriptionLocation: 'tooltip', colon: false,
};
