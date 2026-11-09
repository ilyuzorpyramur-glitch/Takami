#!/usr/bin/env node
// Командная строка генератора: конфиг JSON -> файл плагина и, по желанию, plugins.json.
'use strict';

const fs = require('fs');
const { generate, validateConfig } = require('./generator');

function usage() {
  console.log('Использование: node index.js --input config.json --out-dir ./out [--index]');
  console.log('  --input   конфиг сайта (JSON)');
  console.log('  --out-dir каталог для файла плагина');
  console.log('  --index   дополнительно записать plugins.json с суммой sha256');
  console.log('  --help    эта подсказка');
}

function parseArgs(args) {
  const parsed = {};
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === '--input') { parsed.input = args[i + 1]; i += 1; continue; }
    if (arg === '--out-dir') { parsed.outDir = args[i + 1]; i += 1; continue; }
    if (arg === '--index') { parsed.index = true; continue; }
    if (arg === '--help' || arg === '-h') { parsed.help = true; continue; }
  }
  return parsed;
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));
  if (parsed.help || !parsed.input || !parsed.outDir) {
    usage();
    process.exit(parsed.help ? 0 : 1);
  }
  let config;
  try {
    config = JSON.parse(fs.readFileSync(parsed.input, 'utf8'));
  } catch (error) {
    console.error('конфиг не читается: ' + error.message);
    process.exit(1);
  }
  const errors = validateConfig(config);
  if (errors.length) {
    console.error('конфиг не принят: ' + errors.join('; '));
    process.exit(1);
  }
  const result = generate(config, parsed.outDir, { index: Boolean(parsed.index) });
  console.log('Плагин: ' + parsed.outDir + '/' + result.pluginFile);
  if (result.indexFile) {
    console.log('Индекс: ' + parsed.outDir + '/' + result.indexFile + ' (id ' + result.indexEntry.id + ', sha256 ' + result.indexEntry.sha256.slice(0, 12) + '…)');
  }
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
