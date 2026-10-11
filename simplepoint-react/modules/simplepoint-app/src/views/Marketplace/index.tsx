import EmptyPage from '../../components/EmptyPage';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

export default function Marketplace() {
  const {t} = useI18n();
  return <EmptyPage title={t('menu.app.marketplace', '应用市场')}/>;
}
