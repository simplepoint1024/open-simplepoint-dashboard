import {useEffect, useState} from 'react';

/** Compact navigation follows viewport changes without replacing the desktop user's choice. */
export function useResponsiveSider() {
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 767px)').matches);
  const [desktopCollapsed, setDesktopCollapsed] = useState(false);
  const [mobileCollapsed, setMobileCollapsed] = useState(true);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 767px)');
    const change = () => {
      setNarrow(media.matches);
      if (media.matches) setMobileCollapsed(true);
    };
    change(); media.addEventListener('change', change);
    return () => media.removeEventListener('change', change);
  }, []);
  return [narrow ? mobileCollapsed : desktopCollapsed,
    narrow ? setMobileCollapsed : setDesktopCollapsed] as const;
}
