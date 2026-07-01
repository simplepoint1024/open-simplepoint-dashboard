import { setupWorker } from 'msw/browser';
import { useMocks } from './hooks/useMocks';

declare const require: {
  context: (
    directory: string,
    useSubdirectories: boolean,
    regExp: RegExp
  ) => {
    keys: () => string[];
    (id: string): any;
  };
};

const ctx = require.context('./mocks', true, /\.(ts|tsx)$/);
const handlers = useMocks(ctx);

const worker = setupWorker(
  ...handlers
);

export default worker.start({
  onUnhandledRequest: 'bypass'
});
