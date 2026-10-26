const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '../src/backend.js'), 'utf8');
const urlFields = ['platformBaseUrl', 'mockBaseUrl', 'resourceBaseUrl', 'hybridJsUrl', 'vConsoleUrl', 'webSocketUrl'];
const emptyOptions = value => Object.fromEntries([
  ...urlFields.map(key => [key, value]), ['sdkToken', value], ['projectId', value], ['queryTimeoutMs', null],
]);
let backend;

beforeEach(() => {
  backend = vm.createContext({ URL });
  vm.runInContext(source.replace(/export /g, ''), backend);
});

test.each([null, '', ' \t\r\n '])('accepts all empty fields (%p)', value => {
  const config = backend.configureBackend(emptyOptions(value));
  for (const field of [...urlFields, 'sdkToken', 'projectId']) expect(config[field]).toBe('');
  expect(config.queryTimeoutMs).toBe(8000);
  expect(backend.isBackendUrl('https://business.example/api')).toBe(false);
});

test('empty platform URL does not hide a configured mock backend', () => {
  backend.configureBackend({ ...emptyOptions(null), mockBaseUrl: 'https://mock.example:8443/dokit/' });
  expect(backend.isBackendUrl('https://mock.example:8443/dokit/api')).toBe(true);
  expect(backend.isBackendUrl('https://mock.example:8443/dokit-other')).toBe(false);
  expect(backend.isBackendUrl('https://mock.example/dokit/api')).toBe(false);
});

test('invalid nonempty URLs still reject without replacing configuration', () => {
  const previous = backend.configureBackend(emptyOptions(null));
  for (const field of urlFields) {
    expect(() => backend.configureBackend({ ...emptyOptions(null), [field]: 'not-a-url' })).toThrow();
    expect(backend.getBackendConfig()).toBe(previous);
  }
});

test('null timeout uses the default, invalid token and timeout still reject', () => {
  backend.configureBackend({ ...emptyOptions(null), queryTimeoutMs: 12000 });
  expect(backend.configureBackend(emptyOptions(null)).queryTimeoutMs).toBe(8000);
  expect(() => backend.configureBackend({ ...emptyOptions(null), sdkToken: 'bad\r\ntoken' })).toThrow();
  expect(() => backend.configureBackend({ ...emptyOptions(null), queryTimeoutMs: 0 })).toThrow();
});

test('Android bootstrap can pass empty configuration before loading the bundle', () => {
  const context = vm.createContext({ URL, DOKIT_CONFIG: emptyOptions(null) });
  vm.runInContext(source.replace(/export /g, ''), context);
  expect(context.getBackendConfig().webSocketUrl).toBe('');
});
