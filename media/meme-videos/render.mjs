import {bundle} from '@remotion/bundler';
import {getCompositions, renderMedia, renderStill} from '@remotion/renderer';
import {mkdir, writeFile, rename} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
const base = dirname(fileURLToPath(import.meta.url));
const output = process.env.CALL_TRANSLATE_OUTPUT || join(base, 'out');
const browserExecutable = process.env.REMOTION_BROWSER_EXECUTABLE || undefined;
await mkdir(output, {recursive: true});
const serveUrl = await bundle({entryPoint: base + '/src/index.tsx', publicDir: base + '/public', outDir: base + '/bundle', enableCaching: false, webpackOverride: config => ({...config, cache: false})});
const compositions = await getCompositions(serveUrl, {browserExecutable});
await writeFile(base + '/compositions.json', JSON.stringify(compositions, null, 2));
const onlyStills = process.argv.includes('--stills');
for (const composition of compositions) {
  const slug = composition.id === 'WaterToBeer' ? '01-water-to-beer' : '02-perfect-phrase-wrong-wish';
  await mkdir(output + '/' + slug + '-keyframes', {recursive: true});
  const frames = composition.id === 'WaterToBeer' ? [36, 86, 115, 146, 173, 206, 232, 279, 343, 397, 440, 516] : [36, 78, 107, 129, 156, 206, 283, 341, 398, 450, 489, 540];
  for (const frame of frames) {
    await renderStill({serveUrl, composition, output: output + '/' + slug + '-keyframes/' + String(frame).padStart(3, '0') + '.png', frame, browserExecutable, imageFormat: 'png'});
  }
  console.log('stills', slug);
  if (!onlyStills) {
    let last = -1;
    await renderMedia({serveUrl, composition, codec: 'h264', outputLocation: output + '/' + slug + '.mp4', browserExecutable, concurrency: 4, crf: 17, pixelFormat: 'yuv420p', audioCodec: 'aac', audioBitrate: '192k', colorSpace: 'bt709', onProgress: ({progress}) => {const pct = Math.floor(progress * 10) * 10; if (pct !== last) {console.log(slug, pct + '%'); last = pct;}}});
    const finalFile = output + '/' + slug + '.mp4';
    const masteredFile = output + '/' + slug + '-mastered.mp4';
    const variant = composition.id === 'WaterToBeer' ? 'a' : 'b';
    execFileSync(process.env.FFMPEG || 'ffmpeg', ['-v', 'error', '-y', '-i', finalFile, '-i', base + '/public/audio/mix-' + variant + '.wav', '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-t', String(composition.durationInFrames / composition.fps), '-movflags', '+faststart', masteredFile]);
    await rename(masteredFile, finalFile);
    console.log('rendered', slug);
  }
}
