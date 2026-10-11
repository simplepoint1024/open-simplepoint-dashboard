import EmptyPage from '../../components/EmptyPage';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

export default function Templates() {
  const {t} = useI18n();
  return <EmptyPage title={t('menu.app.templates', '应用模板')}/>;
}
