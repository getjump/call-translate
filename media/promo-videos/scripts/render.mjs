import {bundle} from '@remotion/bundler';
import {renderMedia, renderStill, selectComposition} from '@remotion/renderer';
import {mkdir, readdir, rm} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = process.env.PROMO_OUTPUT || path.join(root, 'out');
const cachedBrowser = path.join(
  root,
  'node_modules/.remotion/chrome-headless-shell/mac-arm64/chrome-headless-shell-mac-arm64/chrome-headless-shell',
);
const browserExecutable =
  process.env.REMOTION_BROWSER_EXECUTABLE ||
  (existsSync(cachedBrowser) ? cachedBrowser : undefined);
await mkdir(output, {recursive: true});
const serveUrl = await bundle({
  entryPoint: path.join(root, 'src/index.ts'),
  publicDir: path.join(root, 'public'),
  outDir: path.join(root, '.bundle'),
  enableCaching: false,
});
for (const [id, slug] of [
  ['TranslationPromo', 'translation-promo'],
  ['VoiceAgentConcept', 'voice-agent-concept'],
  ['IncomingInheritance', 'incoming-inheritance'],
]) {
  if (process.env.PROMO_ONLY && !process.env.PROMO_ONLY.split(',').includes(slug)) continue;
  const composition = await selectComposition({serveUrl, id, browserExecutable});
  const stills = path.join(output, `${slug}-frames`);
  await mkdir(stills, {recursive: true});
  for (const name of await readdir(stills)) {
    if (/^\d{3}\.png$/.test(name)) await rm(path.join(stills, name));
  }
  const frames =
    id === 'TranslationPromo'
      ? [0, 65, 128, 228, 326, 382, 465, 530]
      : id === 'VoiceAgentConcept'
        ? [0, 65, 122, 213, 340, 376, 468, 530]
        : [0, 108, 160, 193, 225, 315, 518, 570];
  for (const frame of frames) {
    await renderStill({
      serveUrl,
      composition,
      output: path.join(stills, `${String(frame).padStart(3, '0')}.png`),
      frame,
      browserExecutable,
      imageFormat: 'png',
    });
  }
  console.log(`stills ready: ${slug}`);
  let lastProgress = -1;
  await renderMedia({
    serveUrl,
    composition,
    outputLocation: path.join(output, `${slug}.mp4`),
    codec: 'h264',
    crf: 16,
    pixelFormat: 'yuv420p',
    colorSpace: 'bt709',
    audioCodec: 'aac',
    audioBitrate: '192k',
    browserExecutable,
    concurrency: 4,
    onProgress: ({progress}) => {
      const percent = Math.floor(progress * 100);
      if (percent % 20 === 0 && percent !== lastProgress) {
        lastProgress = percent;
        console.log(`${slug}: ${percent}%`);
      }
    },
  });
  console.log(`rendered: ${slug}`);
}
