// Run from the self-hosted backend checkout; writes only named SDK test fixtures.
// Usage: node /path/to/DoKit/Android/scripts/prepare-backend-e2e.mjs /path/to/DoKit
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { pathToFileURL } from 'node:url'
import path from 'node:path'

const repo = path.resolve(process.argv[2])
const { list, put, hash, close } = await import(pathToFileURL(path.resolve('lib/dokit/store.mjs')))
const config = JSON.parse(readFileSync(path.join(repo, 'dokit-backend.local.json'), 'utf8'))
const project = list('system', 'projects').find(p => p.sdkTokenHash === hash(config.sdkToken))
if (!project || project.legacyProjectId !== config.projectId) throw new Error('SDK project/token mismatch')
const actor = 'sdk-device-integration'
const runName = `Android SDK E2E ${new Date().toISOString()}`
const category = put(project.id, 'categories', { name: 'SDK 真机联调', status: 'active' }, { id: 'sdk-e2e-category', actor })
const api = put(project.id, 'interfaces', {
  name: 'SDK 真机 Mock 验证', path: '/dokit-sdk-e2e/response', method: 'GET',
  categoryId: category.id, query: {}, body: {}, status: 'active', owner: { name: 'SDK E2E' },
}, { id: 'sdk-e2e-interface', actor })
function scene(id, name, responseStatus, bodyRaw) {
  return put(project.id, 'scenes', { name, interfaceId: api.id, responseStatus, bodyRaw,
    contentType: 'application/json; charset=utf-8', status: 'published',
    published: { bodyRaw, responseStatus, contentType: 'application/json; charset=utf-8' },
  }, { id, actor })
}
const successBody = JSON.stringify({ source: 'self-hosted-dokit', result: 'android-sdk-ok', runName })
const errorBody = JSON.stringify({ source: 'self-hosted-dokit', result: 'expected-error-scene', runName })
const success = scene('sdk-e2e-success', 'SDK 200 成功响应', 200, successBody)
const error = scene('sdk-e2e-error', 'SDK 503 错误响应', 503, errorBody)
const token = randomBytes(32).toString('base64url')
const room = put(project.id, 'rooms', {
  name: 'SDK 真机配对验证', state: 'open', tokenHash: hash(token),
  expiresAt: Date.now() + 3600000, channelPolicy: 'same-profile', allowedOrigins: project.allowedOrigins || [],
}, { actor })
const base = new URL(config.platformBaseUrl)
const fixture = { projectId: project.id, legacyProjectId: project.legacyProjectId, runName,
  interfaceId: api.id, path: api.path, successSceneId: success.id, errorSceneId: error.id,
  successBody, errorBody, roomId: room.id, wsUrl: `ws://${base.hostname}:4001/proxy/multicontrol/${token}` }
const output = path.join(repo, 'build', 'sdk-e2e')
mkdirSync(output, { recursive: true })
writeFileSync(path.join(output, 'fixture.json'), JSON.stringify(fixture, null, 2), { mode: 0o600 })
console.log(JSON.stringify({ project: project.name, runName, interfaceId: api.id, roomId: room.id, output }))
close()
