// Isolated API only. /testdata must be mounted to the fixture directory.
// node server/tools/test_audio_clips.mjs http://127.0.0.1:15002 data/audio-clip-test dashcam-audio-clip-test
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
const [base, directory, container] = process.argv.slice(2)
assert.ok(base && directory && container?.includes('test'))
assert.ok(new URL(base).hostname === '127.0.0.1' && !['', '5000', '8080'].includes(new URL(base).port))
const media = (tool, args) => execFileSync('docker', ['exec', container, tool, ...args], { maxBuffer: 8 * 1024 * 1024 })
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const post = body => fetch(`${base}/api/audio-exports`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})
for (const [name, frequency] of [['first', 440], ['second', 880]]) {
  media('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', `sine=frequency=${frequency}:duration=4`,
    ...(name === 'first' ? ['-af', "volume=0:enable='lt(t,2)'"] : []), '-c:a', 'aac', `/testdata/${name}.m4a`])
}
async function upload(name, seconds) {
  const form = new FormData()
  form.set('file', new Blob([await readFile(join(directory, `${name}.m4a`))], { type: 'audio/mp4' }), `${name}.m4a`)
  form.set('filename', `${name}.m4a`)
  form.set('startTime', new Date(Date.UTC(2026, 10, 4, 10, 0, seconds)).toISOString())
  form.set('endTime', new Date(Date.UTC(2026, 10, 4, 10, 0, seconds + 4)).toISOString())
  form.set('durationSeconds', '4')
  const response = await fetch(`${base}/api/audio/upload`, { method: 'POST', body: form })
  assert.ok(response.ok, await response.clone().text())
  return response.json()
}
const first = await upload('first', 0)
const second = await upload('second', 6)
const originals = [first, second].map(item => `/testdata/audio/2026-11-04/${item.filename}`)
const hashes = originals.map(path => hash(media('cat', [path])))
async function exportClip(name, ids, startSeconds, endSeconds, duration) {
  const response = await post({ ids, startSeconds, endSeconds })
  assert.equal(response.status, 202, await response.clone().text())
  let job = await response.json()
  const deadline = Date.now() + 60_000
  while (['queued', 'processing'].includes(job.status) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 200))
    job = await (await fetch(`${base}/api/audio-exports/${job.jobId}`)).json()
  }
  assert.equal(job.status, 'ready', JSON.stringify(job))
  const result = await fetch(`${base}${job.downloadUrl}`)
  assert.equal(result.status, 200)
  await writeFile(join(directory, `${name}.m4a`), Buffer.from(await result.arrayBuffer()))
  const probe = JSON.parse(media('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', `/testdata/${name}.m4a`]))
  assert.ok(Math.abs(Number(probe.format.duration) - duration) < 0.06, `${name}: ${probe.format.duration}`)
  assert.equal(probe.streams[0].codec_name, 'aac')
  console.log(`PASS ${name}: ${probe.format.duration}s`)
}
function peak(name, second) {
  const bytes = media('ffmpeg', ['-v', 'error', '-ss', String(second), '-i', `/testdata/${name}.m4a`, '-t', '0.05', '-f', 's16le', '-ac', '1', 'pipe:1'])
  let maximum = 0
  for (let i = 0; i < bytes.length - 1; i += 2) maximum = Math.max(maximum, Math.abs(bytes.readInt16LE(i)))
  return maximum
}
await exportClip('single', [first.id], 1, 2.2, 1.2)
assert.ok(peak('single', 0.5) < 10, 'Requested first second is silent')
assert.ok(peak('single', 1.1) > 100, 'Precise seek reaches source after second 2')
await exportClip('cross-gap', [first.id, second.id], 3, 7, 4)
assert.ok(peak('cross-gap', 0.3) > 100)
assert.ok(peak('cross-gap', 1.5) < 10, 'Gap is silent')
assert.ok(peak('cross-gap', 3.5) > 100, 'Second file is audible')
await exportClip('gap-only', [first.id, second.id], 4.5, 5.5, 1)
assert.ok(peak('gap-only', 0.5) < 10)
await exportClip('second-only', [first.id, second.id], 7, 8, 1)
await exportClip('whole', [first.id, second.id], undefined, undefined, 10)
for (const range of [{ startSeconds: -1, endSeconds: 2 }, { startSeconds: 2, endSeconds: 1 },
  { startSeconds: 0, endSeconds: 11 }, { startSeconds: 0 }, { startSeconds: 1, endSeconds: 1.01 }])
  assert.equal((await post({ ids: [first.id, second.id], ...range })).status, 400)
originals.forEach((path, index) => assert.equal(hash(media('cat', [path])), hashes[index]))
console.log('PASS invalid ranges and unchanged originals; all audio clip checks passed')
