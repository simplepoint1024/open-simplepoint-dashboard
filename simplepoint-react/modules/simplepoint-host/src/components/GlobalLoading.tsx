import React from 'react';
import {Spin} from 'antd';
import {useThemeMode} from '@/hooks/useThemeMode';

interface Props {
    visible: boolean;
    text?: string;
}

export const GlobalLoading: React.FC<Props> = ({visible, text}) => {
    const {resolvedTheme} = useThemeMode();

    if (!visible) return null;

    return (
        <div
            style={{
                position: 'fixed',
                inset: 0,
                // Keep Ant Design Modal/Message above the bootstrap overlay so
                // authentication and recovery prompts can always be operated.
                zIndex: 900,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background:
                    resolvedTheme === 'dark'
                        ? 'rgba(0,0,0,0.75)'
                        : 'rgba(255,255,255,0.9)'
            }}
        >
            <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12}}>
                <Spin size="large" />
                {text && (
                    <div
                        style={{
                            color: resolvedTheme === 'dark' ? '#EEE' : '#333',
                            fontSize: 14
                        }}
                    >
                        {text}
                    </div>
                )}
            </div>
        </div>
    );
};
