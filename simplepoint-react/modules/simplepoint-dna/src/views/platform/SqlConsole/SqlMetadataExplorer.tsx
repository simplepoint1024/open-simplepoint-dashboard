import {CodeOutlined, DatabaseOutlined, ReloadOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TableActions from '@simplepoint/components/Table/Actions';
import {Alert, Button, Empty, Input, Spin, Tree, Typography} from 'antd';
import {useMemo} from 'react';
import type {Key, RefObject} from 'react';
import {filterLoadedTree} from './sqlConsoleUtils';
import type {MetadataTreeNode} from '../metadataTree';

type SqlMetadataExplorerProps = {
  error: string | null;
  expandedKeys: Key[];
  loading: boolean;
  onDismissError: () => void;
  onDoubleClick: (event: React.MouseEvent, node: MetadataTreeNode) => void;
  onExpand: (keys: Key[]) => void;
  onInsertPath: () => void;
  onLoadData: (node: object) => Promise<void>;
  onRefresh: () => void;
  onRetry: () => void;
  onSearchChange: (value: string) => void;
  onSelect: (keys: Key[], info: {node: object}) => void;
  renderNodeTitle: (node: MetadataTreeNode) => React.ReactNode;
  retrying: boolean;
  revision: number;
  search: string;
  selectedKeys: Key[];
  selectedPath: string | null;
  treeContainerRef: RefObject<HTMLDivElement | null>;
  treeData: MetadataTreeNode[];
};

export const SqlMetadataExplorer = ({
  error,
  expandedKeys,
  loading,
  onDismissError,
  onDoubleClick,
  onExpand,
  onInsertPath,
  onLoadData,
  onRefresh,
  onRetry,
  onSearchChange,
  onSelect,
  renderNodeTitle,
  retrying,
  revision,
  search,
  selectedKeys,
  selectedPath,
  treeContainerRef,
  treeData,
}: SqlMetadataExplorerProps) => {
  const {t} = useI18n();
  const normalizedSearch = search.trim().toLowerCase();
  const visibleTree = useMemo(() => filterLoadedTree(treeData, search), [treeData, search]);
  const visibleExpandedKeys = useMemo(() => {
    if (!normalizedSearch) return expandedKeys;
    const keys: Key[] = [];
    const visit = (nodes: MetadataTreeNode[]) => nodes.forEach(node => {
      if (node.children?.length) {
        keys.push(node.key);
        visit(node.children);
      }
    });
    visit(visibleTree);
    return keys;
  }, [expandedKeys, normalizedSearch, visibleTree]);

  return (
    <aside className="dna-sql-explorer">
      <div className="dna-sql-toolwindow-title">
        <span><DatabaseOutlined /> {t('dna.federation.sqlConsole.page.card.tree', '数据库')}</span>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
          key: 'refreshTree',
          sort: 0,
          type: 'text',
          size: 'small',
          icon: <ReloadOutlined/>,
          iconOnly: true,
          loading,
          text: t('dna.federation.sqlConsole.page.button.refreshTree', '刷新树'),
          onClick: onRefresh,
        }]}/>
      </div>
      <Input.Search
        allowClear
        value={search}
        onChange={event => onSearchChange(event.target.value)}
        placeholder={t('dna.federation.sqlConsole.page.search', '搜索已加载对象')}
      />
      <Typography.Text type="secondary" className="dna-sql-tree-hint">
        <CodeOutlined /> {t('dna.federation.sqlConsole.page.treeHint', '双击表查看数据，右键生成增删改查 SQL')}
      </Typography.Text>
      {error && (
        <Alert
          type="error"
          showIcon
          closable
          onClose={onDismissError}
          title={error}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
            key: 'retryTree',
            sort: 0,
            size: 'small',
            loading: retrying,
            text: t('dna.federation.sqlConsole.page.retry', '重试'),
            onClick: onRetry,
          }]}/>} />
      )}
      <div className="dna-sql-tree" ref={treeContainerRef}>
        {loading ? <Spin /> : treeData.length ? (
          <Tree
            key={revision}
            blockNode
            showLine={{showLeafIcon: false}}
            treeData={visibleTree}
            loadData={normalizedSearch ? undefined : onLoadData}
            selectedKeys={selectedKeys}
            expandedKeys={visibleExpandedKeys}
            onExpand={keys => onExpand(keys)}
            titleRender={node => renderNodeTitle(node as MetadataTreeNode)}
            filterTreeNode={node => Boolean(normalizedSearch)
              && (node.rawTitle ?? '').toLowerCase().includes(normalizedSearch)}
            onSelect={(keys, info) => onSelect(keys, info)}
            onDoubleClick={(event, node) => onDoubleClick(event, node as MetadataTreeNode)}
          />
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t('dna.federation.sqlConsole.page.empty.noDataSources', '当前没有可用的数据源')}
          />
        )}
      </div>
      {selectedPath && (
        <div className="dna-sql-object-path">
          <Typography.Text ellipsis={{tooltip: selectedPath}}>{selectedPath}</Typography.Text>
          {/* Editor insertion is a local text editing command outside the platform action contract. */}
          <Button size="small" type="text" icon={<CodeOutlined/>} onClick={onInsertPath}>
            {t('dna.federation.sqlConsole.page.button.insertPath', '插入路径')}
          </Button>
        </div>
      )}
    </aside>
  );
};
