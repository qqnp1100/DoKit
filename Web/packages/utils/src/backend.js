// Shared by all bundled Web packages; configure before selecting a project.
const defaults = {"platformBaseUrl": "https://dokit.example.internal", "mockBaseUrl": "https://dokit.example.internal", "resourceBaseUrl": "https://dokit.example.internal/resources", "webSocketUrl": "wss://dokit.example.internal/ws", "projectId": "", "sdkToken": "", "queryTimeoutMs": 8000, "hybridJsUrl": "https://dokit.example.internal/resources/dokit.js", "vConsoleUrl": "https://dokit.example.internal/resources/vconsole.min.js"};
const host = typeof window === 'undefined' ? globalThis : window;
const state = host.__DOKIT_BACKEND_STATE__ || (host.__DOKIT_BACKEND_STATE__ = {
  config: Object.freeze({ ...defaults }), locked: false,
});

export function getBackendConfig() { return state.config; }
export function configureBackend(options = {}) {
  if (state.locked) throw new Error('Configure DoKit before setProductId; reload to change backend.');
  const next = { ...defaults, ...options };
  for (const key of ['platformBaseUrl', 'mockBaseUrl', 'resourceBaseUrl', 'webSocketUrl', 'hybridJsUrl', 'vConsoleUrl']) {
    if (next[key] == null || (typeof next[key] === 'string' && !next[key].trim())) {
      next[key] = '';
      continue;
    }
    if (typeof next[key] !== 'string' || /[\s\\<>"']/.test(next[key])) throw new Error('Invalid DoKit ' + key);
    const url = new URL(next[key]);
    const protocols = key === 'webSocketUrl' ? ['ws:', 'wss:'] : ['http:', 'https:'];
    if (!protocols.includes(url.protocol) || !url.hostname || url.username || url.password || url.hash ||
        (key !== 'webSocketUrl' && url.search)) throw new Error('Invalid DoKit ' + key);
    next[key] = next[key].replace(/\/+$/, '');
  }
  for (const key of ['sdkToken', 'projectId']) {
    if (next[key] == null || (typeof next[key] === 'string' && !next[key].trim())) next[key] = '';
  }
  if (next.queryTimeoutMs == null) next.queryTimeoutMs = defaults.queryTimeoutMs;
  if (typeof next.sdkToken !== 'string' || /[^A-Za-z0-9._~+/=:-]/.test(next.sdkToken)) throw new Error('Invalid DoKit token');
  if (typeof next.projectId !== 'string') throw new Error('Invalid DoKit projectId');
  if (!Number.isInteger(next.queryTimeoutMs) || next.queryTimeoutMs < 100 || next.queryTimeoutMs > 60000) throw new Error('Invalid DoKit queryTimeoutMs');
  state.config = Object.freeze(next);
  return state.config;
}
export function lockBackendConfig() { state.locked = true; }
export function isBackendUrl(value) {
  try {
    const target = new URL(value.url || value, host.location && host.location.href);
    return ['platformBaseUrl', 'mockBaseUrl', 'resourceBaseUrl'].some(key => {
      if (!state.config[key]) return false;
      const own = new URL(state.config[key]);
      const prefix = own.pathname.replace(/\/+$/, '');
      return target.origin === own.origin && (target.pathname === prefix || target.pathname.startsWith(prefix + '/'));
    });
  } catch (_) { return false; }
}
if (host.DOKIT_CONFIG) configureBackend(host.DOKIT_CONFIG);
