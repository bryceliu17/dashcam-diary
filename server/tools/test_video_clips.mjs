// Only run against an isolated test API, NEVER the real archive.
// Generate first.mp4 (4s, top yellow/bottom red + audio) and second.mp4 (4s blue, silent)
// in a directory mounted at /testdata in the isolated API container.
// node server/tools/test_video_clips.mjs http://127.0.0.1:15001 data/clip-export-test CONTAINER
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

const [base, directory, container] = process.argv.slice(2)
assert.ok(base && directory && container, 'Provide isolated API URL, fixture directory and container name')
const request = (path, body) => fetch(`${base}${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})
const media = (tool, args) => execFileSync('docker', ['exec', container, tool, ...args], { maxBuffer: 8 * 1024 * 1024 })
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
assert.ok(new URL(base).hostname === '127.0.0.1' && !['5000', '8080', ''].includes(new URL(base).port),
  'Use a separate localhost test port, not the real API/dashboard')
assert.ok(container.includes('test'), 'Use an explicitly named test container')
media('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=red:s=320x180:r=30:d=4',
  '-f', 'lavfi', '-i', 'sine=frequency=440:duration=4', '-vf',
  "drawbox=x=0:y=0:w=iw:h=ih:color=green:t=fill:enable='gte(t,2)',drawbox=x=0:y=0:w=iw:h=ih/2:color=yellow:t=fill",
  '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '/testdata/first.mp4'])
media('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:r=30:d=4',
  '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '/testdata/second.mp4'])
async function upload(filename, seconds, rotation) {
  const bytes = await readFile(join(directory, filename))
  const form = new FormData()
  form.set('file', new Blob([bytes], { type: 'video/mp4' }), filename)
  form.set('filename', filename)
  form.set('startTime', new Date(Date.UTC(2026, 10, 4, 10, 0, seconds)).toISOString())
  form.set('endTime', new Date(Date.UTC(2026, 10, 4, 10, 0, seconds + 4)).toISOString())
  form.set('durationSeconds', '4')
  form.set('playbackRotationDegrees', String(rotation))
  const response = await fetch(`${base}/api/videos/upload`, { method: 'POST', body: form })
  assert.ok([200, 201].includes(response.status), await response.clone().text())
  return response.json()
}
const first = await upload('first.mp4', 0, 90)
const second = await upload('second.mp4', 6, 0)
const sourcePath = `/testdata/videos/2026-11-04/${first.filename}`
const originalHash = hash(media('cat', [sourcePath]))
async function exportClip(name, ids, startSeconds, endSeconds, expectedDuration, withTimestamp = false) {
  const response = await request('/api/video-exports', { ids, startSeconds, endSeconds, withTimestamp, timezoneOffsetMinutes: -600 })
  assert.equal(response.status, 202, await response.clone().text())
  let job = await response.json()
  const deadline = Date.now() + 90_000
  while (['queued', 'processing'].includes(job.status) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 250))
    job = await (await fetch(`${base}/api/video-exports/${job.jobId}`)).json()
  }
  assert.equal(job.status, 'ready', JSON.stringify(job))
  const download = await fetch(`${base}${job.downloadUrl}`)
  assert.equal(download.status, 200)
  await writeFile(join(directory, `${name}.mp4`), Buffer.from(await download.arrayBuffer()))
  const probe = JSON.parse(media('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', `/testdata/${name}.mp4`]))
  assert.ok(Math.abs(Number(probe.format.duration) - expectedDuration) < 0.12, `${name}: ${probe.format.duration}`)
  assert.ok(probe.streams.some(stream => stream.codec_type === 'audio'), `${name}: audio/silence is preserved`)
  console.log(`PASS ${name}: ${probe.format.duration}s`)
}
function pixel(name, second, x, y) {
  return [...media('ffmpeg', ['-v', 'error', '-ss', String(second), '-i', `/testdata/${name}.mp4`, '-vf',
    `format=rgb24,crop=1:1:${x}:${y}`, '-frames:v', '1', '-f', 'rawvideo', 'pipe:1'])]
}
await exportClip('single', [first.id], 1, 2.2, 1.2)
assert.ok(pixel('single', 0.5, 590, 300)[1] < 40, 'Clockwise rotation: left is red')
assert.ok(pixel('single', 0.5, 690, 300)[1] > 180, 'Clockwise rotation: right is yellow')
assert.ok(pixel('single', 1.1, 590, 300)[1] > 90, 'Accurate seek: output at 1.1s shows source at 2.1s (green)')
await exportClip('cross-gap', [first.id, second.id], 3, 7, 4)
assert.ok(pixel('cross-gap', 1.5, 640, 300).every(channel => channel < 20), 'Middle gap is black')
assert.ok(pixel('cross-gap', 3.5, 640, 300)[2] > 180, 'Next source clip is blue')
await exportClip('gap-only', [first.id, second.id], 4.5, 5.5, 1)
assert.ok(pixel('gap-only', 0.5, 640, 300).every(channel => channel < 20))
await exportClip('silent', [second.id], 0, 1, 1)
await exportClip('with-data', [first.id, second.id], 3, 7, 4, true)
await exportClip('whole-session', [first.id, second.id], undefined, undefined, 10)
for (const range of [{ startSeconds: -1, endSeconds: 2 }, { startSeconds: 2, endSeconds: 1 },
  { startSeconds: 0, endSeconds: 11 }, { startSeconds: 0 }, { startSeconds: 1, endSeconds: 1.01 }]) {
  const response = await request('/api/video-exports', { ids: [first.id, second.id], ...range })
  assert.equal(response.status, 400, JSON.stringify(range))
}
assert.equal(hash(media('cat', [sourcePath])), originalHash, 'Original video must remain untouched')
console.log('PASS invalid ranges and unchanged original; all clip export checks passed')
