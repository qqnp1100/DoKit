// Run alongside BackendIntegrationTest from the backend checkout.
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'
import path from 'node:path'
import assert from 'node:assert/strict'

const repo = path.resolve(process.argv[2])
const target = process.argv[3]
const fixture = JSON.parse(readFileSync(path.join(repo, 'build/sdk-e2e/fixture.json')))
process.env.DOKIT_FILE_TARGETS = target
const { createFileSession, fileOperation } = await import(pathToFileURL(path.resolve('lib/dokit/file-bridge.mjs')))
const { close } = await import(pathToFileURL(path.resolve('lib/dokit/store.mjs')))
const actor = 'sdk-device-integration'
const directory = '/root/com.didichuxing.doraemondemo/files/dokit-sdk-e2e'
const deadline = Date.now() + 120000
let session
while (Date.now() < deadline) {
  try {
    session = await createFileSession(fixture.projectId, {
      name: 'SDK 真机只读验证', baseUrl: target, roots: [directory], writable: false, ttlSeconds: 3600,
    }, actor)
    break
  } catch { await new Promise(resolve => setTimeout(resolve, 750)) }
}
assert.ok(session, 'The real device file server did not become reachable')
const call = (route, params = {}) => fileOperation(fixture.projectId, session.id, route,
  { dirPath: directory, ...params }, actor)
const listing = await call('getFileList')
assert.equal(listing.data.dirPath.replace(/\/$/, ''), directory)
assert.ok(listing.data.fileList.some(file => file.fileName === 'probe.txt'))
const download = await call('downloadFile', { fileName: 'probe.txt' })
assert.equal(download.bytes.toString(), fixture.runName)
const tables = await call('getAllTable', { fileName: 'probe.db' })
assert.ok(JSON.stringify(tables.data).includes('sdk_probe'))
const rows = await call('getTableData', { fileName: 'probe.db', tableName: 'sdk_probe' })
assert.ok(JSON.stringify(rows.data).includes(fixture.runName))
await assert.rejects(call('getFileList', { dirPath: '/root' }), error => error.status === 403)
const result = { sessionId: session.id, device: session.deviceInfo.deviceName,
  fileList: 'passed', download: 'passed', sqliteRead: 'passed', pathBoundary: '403 verified' }
writeFileSync(path.join(repo, 'build/sdk-e2e/file-bridge-results.json'), JSON.stringify(result, null, 2))
execFileSync('adb', ['shell', 'run-as', 'com.didichuxing.doraemondemo', 'touch', 'files/dokit-sdk-e2e/backend-verified.txt'])
console.log(JSON.stringify(result))
close()
