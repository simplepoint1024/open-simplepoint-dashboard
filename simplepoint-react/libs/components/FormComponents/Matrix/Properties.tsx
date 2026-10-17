import {useState} from 'react';
import {Alert} from 'antd';
import SForm from '../../SForm/Lazy';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {DesignerPropertyEditorProps} from '../../FormDesigner/extensions';
import {MATRIX_LIMIT, matrixSize, resizeMatrix} from './model';

export default function MatrixProperties({node, disabled, onChange, document}: DesignerPropertyEditorProps) {
  const {t} = useI18n(), [error, setError] = useState(false);
  return <>
    {error && <Alert type="warning" showIcon title={t('app.designer.matrixOccupied', '请先移出待删除格子中的组件，再缩小网格。')}/>}
    <SForm hideSubmit disabled={disabled} idPrefix="matrix_property" formData={matrixSize(node)}
      formContext={{labelCol: {flex: '100px'}, wrapperCol: {flex: '1 1 0'}, descriptionLocation: 'tooltip', rowGutter: 0}}
      schema={{type: 'object', required: ['rows', 'columns'], properties: {
        rows: {type: 'integer', title: t('app.designer.matrixRows', '行数'), minimum: 1, maximum: MATRIX_LIMIT},
        columns: {type: 'integer', title: t('app.designer.matrixColumns', '列数'), minimum: 1, maximum: MATRIX_LIMIT},
      }}} onChange={({formData}, changed) => {
        if (!changed || disabled || !formData || !Number.isInteger(formData.rows) || !Number.isInteger(formData.columns)) return;
        try {
          const next = resizeMatrix(node, formData.rows, formData.columns);
          const remaining = new Set(next.children.map(cell => cell.componentId));
          if (node.children.some(cell => !remaining.has(cell.componentId) && document.rules.some(rule => rule.targetComponentId === cell.componentId))) throw new Error('referenced');
          onChange(next); setError(false);
        } catch {setError(true);}
      }}/>
  </>;
}
