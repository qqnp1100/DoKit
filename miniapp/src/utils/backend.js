const defaults = {"platformBaseUrl": "https://dokit.example.internal", "mockBaseUrl": "https://dokit.example.internal", "resourceBaseUrl": "https://dokit.example.internal/resources", "webSocketUrl": "wss://dokit.example.internal/ws", "projectId": "", "sdkToken": "", "queryTimeoutMs": 8000, "hybridJsUrl": "https://dokit.example.internal/resources/dokit.js", "vConsoleUrl": "https://dokit.example.internal/resources/vconsole.min.js"};
let config;
let locked = false;
function configureBackend(options = {}) {
  if (locked) throw new Error('Configure DoKit before opening Mock; restart to change backend.');
  const next = Object.assign({}, defaults, options);
  // Miniapp JS runtimes do not universally implement the browser URL class.
  ['platformBaseUrl', 'mockBaseUrl', 'resourceBaseUrl'].forEach(key => {
    if (typeof next[key] !== 'string' || !/^https?:\/\/[^\s/@?#:]+(?::\d+)?(?:\/[^\s?#]*)?$/.test(next[key]) || /[\\<>"']/.test(next[key])) throw new Error('Invalid DoKit ' + key);
    next[key] = next[key].replace(/\/+$/, '');
  });
  if (typeof next.sdkToken !== 'string' || /[^A-Za-z0-9._~+/=:-]/.test(next.sdkToken)) throw new Error('Invalid DoKit token');
  if (typeof next.projectId !== 'string') throw new Error('Invalid DoKit projectId');
  config = Object.freeze(next);
  return config;
}
function getBackendConfig() {
  if (!config) configureBackend((getApp() || {}).dokitBackend || {});
  return config;
}
function lockBackendConfig() { getBackendConfig(); locked = true; }
function isBackendUrl(value) {
  const current = getBackendConfig();
  return ['platformBaseUrl', 'mockBaseUrl', 'resourceBaseUrl'].some(key => value === current[key] || value.indexOf(current[key] + '/') === 0 || value.indexOf(current[key] + '?') === 0);
}
module.exports = { configureBackend, getBackendConfig, lockBackendConfig, isBackendUrl };
