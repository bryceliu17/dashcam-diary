// Run against an isolated API: node server/tools/test_esp_live.mjs http://127.0.0.1:15000
// Creates test device-status rows; never use a production archive for this check.
import assert from 'node:assert/strict'
import { once } from 'node:events'

const base = process.argv[2] || 'http://127.0.0.1:15000'
const id = `esp32-test-${Date.now()}`
const path = `/api/devices/${id}`
const status = {
  deviceId: id, deviceName: 'ESP live integration test', clientType: 'esp32',
  manufacturer: 'Seeed Studio', model: 'XIAO ESP32-S3 Sense', androidVersion: '',
  appVersion: '0.1.0', ipAddress: '192.0.2.1', batteryLevel: 0,
  liveAccessEnabled: true, liveStreaming: false,
  remoteControlEnabled: true, // Must be rejected for a live-only ESP client.
}
const post = async (url, data) => fetch(base + url, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  signal: AbortSignal.timeout(5000),
})
const devices = async () => (await (await fetch(base + '/api/devices')).json()).items
const currentDevice = async () => (await devices()).find(device => device.deviceId === id)
const waitFor = async predicate => {
  for (let attempt = 0; attempt < 30; attempt++) {
    if (await predicate()) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  assert.fail('Timed out waiting for device state')
}
let socket
try {
  assert.equal((await post('/api/devices/heartbeat', status)).status, 200)
  assert.equal((await currentDevice()).clientType, 'esp32')
  assert.equal((await currentDevice()).remoteControlEnabled, false)
  assert.equal((await post(path + '/recording', { action: 'get_settings' })).status, 403)
  assert.equal((await fetch(base + path + '/battery-history')).status, 400)
  assert.equal((await post(path + '/live/torch', { enabled: true })).status, 400)
  assert.equal((await post(path + '/live/camera', { facing: 'front' })).status, 400)

  // Older phone clients omit clientType and must retain their Android behavior.
  assert.equal((await post('/api/devices/heartbeat', {
    ...status, deviceId: id + '-android', clientType: undefined,
  })).status, 200)
  assert.equal((await devices()).find(device => device.deviceId === id + '-android').clientType, 'android')

  const commands = []
  socket = new WebSocket(`${base.replace(/^http/, 'ws')}/api/devices/socket?deviceId=${id}`)
  socket.addEventListener('message', event => commands.push(JSON.parse(event.data)))
  await once(socket, 'open')
  await waitFor(() => commands.some(command => command.type === 'live_request' && !command.enabled))
  socket.send(JSON.stringify({ type: 'device_status', status }))
  await waitFor(async () => (await currentDevice()).onlineSource === 'websocket')

  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]) // Transport fixture, not a rendered image.
  const frame = () => fetch(base + path + '/live/frame', {
    method: 'POST', headers: { 'Content-Type': 'image/jpeg' }, body: jpeg,
  })
  assert.equal((await frame()).status, 409, 'No frame acceptance before a viewer requests live')
  assert.equal((await post(path + '/live', { enabled: true })).status, 200)
  await waitFor(() => commands.some(command => command.type === 'live_request' && command.enabled))
  assert.equal((await frame()).status, 204)
  const received = await fetch(base + path + '/live/frame?after=-1')
  assert.equal(received.status, 200)
  assert.equal(received.headers.get('content-type'), 'image/jpeg')
  const sequence = Number(received.headers.get('x-live-sequence'))
  assert.ok(sequence > 0)
  assert.deepEqual(Buffer.from(await received.arrayBuffer()), jpeg)
  status.liveStreaming = true
  socket.send(JSON.stringify({ type: 'device_status', status }))
  await waitFor(async () => (await currentDevice()).liveStreaming)

  // The first returned status may still report streaming; the response must
  // reconcile the abandoned viewer and refuse later frames.
  await new Promise(resolve => setTimeout(resolve, 10500))
  const stale = await post('/api/devices/heartbeat', status)
  assert.equal((await stale.json()).liveRequested, false)
  assert.equal((await frame()).status, 409)
  status.liveStreaming = false

  assert.equal((await post(path + '/live', { enabled: true })).status, 200)
  commands.length = 0
  assert.equal((await post(path + '/live', { enabled: false })).status, 200)
  await waitFor(() => commands.some(command => command.type === 'live_request' && !command.enabled))
  assert.equal((await frame()).status, 409)
  status.deviceName = 'ESP live integration test finished'
  socket.send(JSON.stringify({ type: 'device_status', status }))
  await waitFor(async () => (await currentDevice()).deviceName === status.deviceName)
  socket.close()
  await once(socket, 'close')
  await waitFor(async () => !(await currentDevice()).online)
  console.log('PASS: ESP registration, Android compatibility, WebSocket controls, JPEG relay, abandoned-view expiry, stop and disconnect')
} finally {
  socket?.close()
}
