/** @jest-environment node */
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');
const path = require('path');

test('Android embedded bundle mounts with empty backends without HTTP or WebSocket requests', async () => {
  const errors = [];
  const console = new VirtualConsole();
  console.on('jsdomError', error => errors.push(error.message));
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: 'https://business.example/', runScripts: 'outside-only', pretendToBeVisual: true,
    virtualConsole: console,
  });
  const win = dom.window;
  const fetch = jest.fn(() => Promise.reject(new Error('Unexpected HTTP request')));
  win.fetch = fetch;
  win.WebSocket = jest.fn(() => { throw new Error('Unexpected WebSocket connection'); });
  win.DOKIT_CONFIG = {
    platformBaseUrl: '', mockBaseUrl: '', resourceBaseUrl: '', hybridJsUrl: '', vConsoleUrl: '',
    webSocketUrl: '', projectId: '', sdkToken: '', queryTimeoutMs: 8000,
  };
  try {
    win.eval(fs.readFileSync(path.resolve(__dirname, '../../../../Android/dokit/src/main/assets/h5help/dokit.js'), 'utf8'));
    win.Dokit.setProductId('optional-backend-test');
    win.Dokit.isNativeContainer();
    win.Dokit.startMultiControl('', 'client');
    win.Dokit.startMultiControl(undefined, 'host');
    await new Promise(resolve => setImmediate(resolve));
    expect(win.document.querySelector('#dokit-root')).not.toBeNull();
    expect(win.__DOKIT_BACKEND_STATE__.config.webSocketUrl).toBe('');
    const store = win.Dokit.app.config.globalProperties.$store;
    expect(store.state.socketConnect).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
    expect(win.WebSocket).not.toHaveBeenCalled();
    expect(errors).toEqual([]);
  } finally {
    win.close();
  }
});
