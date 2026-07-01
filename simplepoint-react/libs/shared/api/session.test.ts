import assert from 'node:assert/strict';
import test from 'node:test';
import { redirectToLogout } from './session';

test('redirectToLogout submits a top-level POST logout form', async () => {
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  const originalCustomEvent = globalThis.CustomEvent;
  const originalLocalStorage = globalThis.localStorage;
  const originalSessionStorage = globalThis.sessionStorage;
  const originalCaches = globalThis.caches;
  const originalNavigator = globalThis.navigator;
  const originalIndexedDb = globalThis.indexedDB;

  let localStorageCleared = 0;
  let sessionStorageCleared = 0;
  let requestSubmitCalled = 0;
  let assignCalled = 0;
  const appended: unknown[] = [];

  const form = {
    method: '',
    action: '',
    style: {} as Record<string, string>,
    requestSubmit() {
      requestSubmitCalled += 1;
    },
    submit() {
      requestSubmitCalled += 1;
    },
  };

  try {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {
        dispatchEvent() {},
        location: {
          assign() {
            assignCalled += 1;
          },
          href: '',
        },
      },
    });
    Object.defineProperty(globalThis, 'document', {
      configurable: true,
      value: {
        body: {
          appendChild(node: unknown) {
            appended.push(node);
            return node;
          },
        },
        documentElement: {
          appendChild(node: unknown) {
            appended.push(node);
            return node;
          },
        },
        createElement(tagName: string) {
          assert.equal(tagName, 'form');
          return form;
        },
      },
    });
    Object.defineProperty(globalThis, 'CustomEvent', {
      configurable: true,
      value: class CustomEvent {
        type: string;
        init?: unknown;

        constructor(type: string, init?: unknown) {
          this.type = type;
          this.init = init;
        }
      },
    });
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        clear() {
          localStorageCleared += 1;
        },
        getItem() {
          return null;
        },
        setItem() {},
        removeItem() {},
      },
    });
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: {
        clear() {
          sessionStorageCleared += 1;
        },
      },
    });
    Object.defineProperty(globalThis, 'caches', {
      configurable: true,
      value: {
        keys: async () => [],
      },
    });
    Object.defineProperty(globalThis, 'navigator', {
      configurable: true,
      value: {
        serviceWorker: {
          getRegistrations: async () => [],
        },
      },
    });
    Object.defineProperty(globalThis, 'indexedDB', {
      configurable: true,
      value: {
        databases: async () => [],
      },
    });

    await redirectToLogout();

    assert.equal(localStorageCleared, 1);
    assert.equal(sessionStorageCleared, 1);
    assert.equal(assignCalled, 0);
    assert.equal(requestSubmitCalled, 1);
    assert.equal(appended.length, 1);
    assert.equal(appended[0], form);
    assert.equal(form.method, 'post');
    assert.equal(form.action, '/logout');
    assert.equal(form.style.display, 'none');
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
    Object.defineProperty(globalThis, 'document', {
      configurable: true,
      value: originalDocument,
    });
    Object.defineProperty(globalThis, 'CustomEvent', {
      configurable: true,
      value: originalCustomEvent,
    });
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: originalLocalStorage,
    });
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: originalSessionStorage,
    });
    Object.defineProperty(globalThis, 'caches', {
      configurable: true,
      value: originalCaches,
    });
    Object.defineProperty(globalThis, 'navigator', {
      configurable: true,
      value: originalNavigator,
    });
    Object.defineProperty(globalThis, 'indexedDB', {
      configurable: true,
      value: originalIndexedDb,
    });
  }
});
