import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {safeDisplayUrl} from './model';
/** No raw HTML, script plugins, embedded frames or executable URL protocols. */
export default function Markdown({text}: {text: string}) {
  return <div className="sp-form-markdown"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm]}
    urlTransform={value => value.startsWith('#') ? value : safeDisplayUrl(value)}
    components={{a: ({node: _, ...props}) => <a {...props} target="_blank" rel="noopener noreferrer"/>,
      img: ({node: _, ...props}) => <img {...props} loading="lazy" style={{maxWidth: '100%'}}/>}}>{text}</ReactMarkdown></div>;
}
