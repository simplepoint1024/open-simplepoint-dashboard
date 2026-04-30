import '@xyflow/react/dist/style.css';
import type { LineageGraph } from './types';
type Props = {
    graph: LineageGraph;
    onNavigate: (nodeId: string) => void;
    onDeleteEdge: (edgeId: string) => void;
};
export declare function LineageGraphView(props: Props): import("react/jsx-runtime").JSX.Element;
export {};
