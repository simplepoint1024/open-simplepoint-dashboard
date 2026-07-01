import React, { useState } from 'react';
import { Spin } from 'antd';

export const IframeView: React.FC<{ src: string }> = ({ src }) => {
  const [loading, setLoading] = useState(true);
  return (
    <div style={{height: '100%', width: '100%', position:'relative'}}>
      {loading && (
        <div style={{position:'absolute',inset:0,display:'flex',alignItems:'center',justifyContent:'center',background:'transparent'}}>
          <Spin/>
        </div>
      )}
      <iframe
        src={src}
        title={src}
        style={{border: 0, width: '100%', height: '100%'}}
        allow="clipboard-read; clipboard-write; fullscreen; geolocation"
        referrerPolicy="no-referrer"
        loading="lazy"
        onLoad={() => setLoading(false)}
        onError={() => setLoading(false)}
      />
    </div>
  );
};

