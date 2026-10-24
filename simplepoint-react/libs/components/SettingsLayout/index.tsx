import type {ReactNode} from 'react';
import {RightOutlined} from '@simplepoint/shared/types/icon';
import './style.css';

export type SettingsSection<K extends string> = {key: K; label: string; icon: ReactNode; disabled?: boolean};

/** Shared account/workspace settings frame. Navigation is separate from permission-derived business actions. */
export default function SettingsLayout<K extends string>({label, identity, sections, activeKey, onChange, children, className = ''}: {
  label: string; identity: ReactNode; sections: readonly SettingsSection<K>[]; activeKey: K;
  onChange: (key: K) => void; children: ReactNode; className?: string;
}) {
  return <div className={`sp-settings-layout ${className}`}>
    <aside className="sp-settings-navigation" aria-label={label}>
      {identity}
      <div className="sp-settings-nav-label">{label}</div>
      <nav className="sp-settings-nav-links" aria-label={label}>
        {sections.map(section => <button key={section.key} type="button" disabled={section.disabled}
          aria-current={activeKey === section.key ? 'page' : undefined}
          onClick={() => onChange(section.key)}>
          <span aria-hidden="true">{section.icon}</span><span>{section.label}</span><RightOutlined aria-hidden="true"/>
        </button>)}
      </nav>
    </aside>
    <main className="sp-settings-main" aria-label={sections.find(section => section.key === activeKey)?.label ?? label}>
      {children}
    </main>
  </div>;
}
