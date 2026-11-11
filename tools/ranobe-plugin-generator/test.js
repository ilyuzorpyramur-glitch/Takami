// Функциональные проверки генератора: без сети, приспособления вместо страниц сайта.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const cheerio = require('cheerio');
const dayjs = require('dayjs');

const {
  generate,
  generatePluginCode,
  indexEntryFor,
  sha256,
  sanitizeFileName,
  validateConfig,
} = require('./generator');

const SITE = 'https://example.org';
const ENCODED_QUERY = encodeURIComponent('Золото');

function fixture(name) {
  return fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8');
}

// Адреса приспособлений повторяют шаблоны адресов из примеров конфигов.
const FIXTURE_URLS = {
  [SITE + '/catalog?page=1']: 'catalog.html',
  [SITE + '/catalog?page=2']: 'catalog.html',
  [SITE + '/updates?page=1']: 'catalog.html',
  [SITE + '/search?q=' + ENCODED_QUERY + '&page=1']: 'search.html',
  [SITE + '/novels/example-novel']: 'novel.html',
  [SITE + '/novels/example-novel/chapter-1']: 'chapter.html',
};

FIXTURE_URLS['https://jut.su/anime/page-1/'] = 'real-jutsu.html';
FIXTURE_URLS['https://ranobehub.org/ranobe?page=1'] = 'real-ranobehub.html';

function makeFetch(log) {
  return async function fetchApi(url, options) {
    log.push({ url: String(url), options: options || null });
    const name = FIXTURE_URLS[String(url)];
    if (!name) throw new Error('нет приспособления для адреса ' + url);
    const body = fixture(name);
    return {
      status: 200,
      url: String(url),
      text: async () => body,
      json: async () => JSON.parse(body),
    };
  };
}

// Логика isUrlAbsolute скопирована из рантайма приложения, чтобы проверка была честной.
function isUrlAbsolute(value) {
  if (value == null) return false;
  const url = String(value);
  if (url) {
    if (url.indexOf('//') === 0) return true;
    if (url.indexOf('://') === -1) return false;
    if (url.indexOf('.') === -1) return false;
    if (url.indexOf('/') === -1) return false;
    if (url.indexOf(':') > url.indexOf('/')) return false;
    if (url.indexOf('://') < url.indexOf('.')) return true;
  }
  return false;
}

// Загрузка плагина в песочницу тем же способом, что NovelPluginScriptBuilder в приложении.
function loadPlugin(code, fetchLog) {
  const wrapped = [
    'var module = { exports: {} };',
    'var exports = module.exports;',
    code,
    'var __pluginExport = exports.default || module.exports.default || module.exports;',
    'if (!__pluginExport) throw new Error("нет экспорта");',
    'if (typeof __pluginExport === "function") { __plugin = new __pluginExport(); } else' +
    ' { __plugin = __pluginExport; }',
  ].join('\n');
  const context = {
    console: console,
    require(name) {
      if (name === '@libs/fetch') return { fetchApi: makeFetch(fetchLog) };
      if (name === 'cheerio') return cheerio;
      if (name === 'dayjs') return dayjs;
      if (name === '@libs/isAbsoluteUrl') return { isUrlAbsolute: isUrlAbsolute };
      throw new Error('неизвестный модуль ' + name);
    },
  };
  vm.createContext(context);
  vm.runInContext(wrapped, context, { filename: 'plugin.js' });
  return context.__plugin;
}

function writeExample(config, dir, options) {
  return generate(config, dir, options);
}

const exampleConfig = JSON.parse(fs.readFileSync(path.join(__dirname, 'examples', 'example-ranobe.json'), 'utf8'));
const htmlConfig = JSON.parse(fs.readFileSync(path.join(__dirname, 'examples', 'example-ranobe-html.json'), 'utf8'));

test('популярное: карточки и фильтр карточек без ссылки', async () => {
  const log = [];
  const plugin = loadPlugin(generatePluginCode(exampleConfig), log);
  const cards = await plugin.popularNovels('1');
  assert.equal(cards.length, 3);
  assert.equal(cards[0].name, 'Золото дракона');
  assert.equal(cards[0].path, SITE + '/novels/example-novel');
  assert.equal(cards[0].cover, SITE + '/img/cover-gold.jpg');
  assert.equal(log[0].url, SITE + '/catalog?page=1');
});

test('подстановка {page}: второй лист идёт по ?page=2', async () => {
  const log = [];
  const plugin = loadPlugin(generatePluginCode(exampleConfig), log);
  await plugin.popularNovels('2');
  assert.equal(log[0].url, SITE + '/catalog?page=2');
});

test('свежее использует отдельный шаблон /updates', async () => {
  const log = [];
  const plugin = loadPlugin(generatePluginCode(exampleConfig), log);
  await plugin.latestNovels('1');
  assert.equal(log[0].url, SITE + '/updates?page=1');
});

test('поиск кодирует запрос и подставляет {query} и {page}', async () => {
  const log = [];
  const plugin = loadPlugin(generatePluginCode(exampleConfig), log);
  const cards = await plugin.searchNovels('Золото', '1');
  assert.equal(log[0].url, SITE + '/search?q=' + ENCODED_QUERY + '&page=1');
  assert.equal(cards.length, 2);
  assert.equal(cards[1].name, 'Золотой берег');
});

test('новелла: поля, теги и главы с датами в ISO', async () => {
  const log = [];
  const plugin = loadPlugin(generatePluginCode(exampleConfig), log);
  const details = await plugin.novelDetails('/novels/example-novel');
  assert.equal(details.name, 'Золото дракона');
  assert.equal(details.author, 'Иван Тестовый');
  assert.equal(details.artist, 'Пётр Рисовальный');
  assert.equal(details.cover, SITE + '/img/cover-gold.jpg');
  assert.equal(details.status, 'В процессе');
  assert.deepEqual(details.tags, ['фэнтези', 'приключения']);
  assert.equal(details.chapters.length, 3);
  assert.equal(details.chapters[0].name, 'Глава 1. Отъезд');
  assert.equal(details.chapters[0].path, SITE + '/novels/example-novel/chapter-1');
  assert.equal(details.chapters[0].releaseTime, dayjs('2026-01-05').toISOString());
  assert.equal(details.chapters[2].releaseTime, dayjs('2026-01-03').toISOString());
});

test('глава в режиме text: абзацы и чистка', async () => {
  const plugin = loadPlugin(generatePluginCode(exampleConfig), []);
  const content = await plugin.chapterNovelContent('/novels/example-novel/chapter-1');
  assert.equal(
    content,
    'Первый абзац истории про экспедицию.\n' +
      'Второй абзац с описанием дороги.',
  );
  assert.ok(!content.includes('Рекламный блок'));
  assert.ok(!content.includes('alert'));
});

test('глава в режиме html: разметка и чистка', async () => {
  const plugin = loadPlugin(generatePluginCode(htmlConfig), []);
  const content = await plugin.chapterNovelContent('/novels/example-novel/chapter-1');
  assert.ok(content.includes('<p>Первый абзац истории про экспедицию.</p>'));
  assert.ok(content.includes('<p>Второй абзац с описанием дороги.</p>'));
  assert.ok(!content.includes('Рекламный блок'));
  assert.ok(!content.includes('alert'));
});

test('в плагине только методы, описанные в конфиге', () => {
  const plugin = loadPlugin(generatePluginCode(htmlConfig), []);
  assert.equal(typeof plugin.popularNovels, 'function');
  assert.equal(plugin.latestNovels, undefined);
  assert.equal(plugin.searchNovels, undefined);
  assert.equal(typeof plugin.novelDetails, 'function');
  assert.equal(typeof plugin.chapterNovelContent, 'function');
  assert.equal(plugin.id, 'example-html.ranobe');
  assert.equal(plugin.lang, 'ru');
});

test('индексная запись несёт sha256 файла плагина', () => {
  const code = generatePluginCode(exampleConfig);
  const entry = indexEntryFor(exampleConfig, code, 'example.ranobe.js');
  assert.equal(entry.sha256, sha256(Buffer.from(code, 'utf8')));
  assert.equal(entry.version, 1);
  assert.equal(entry.url, 'example.ranobe.js');
  assert.equal(entry.hasSettings, false);
  assert.equal(entry.iconUrl, SITE + '/icon.png');
});

test('командная строка: плагин и plugins.json', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ranobe-gen-cli-'));
  const input = path.join(__dirname, 'examples', 'example-ranobe.json');
  execFileSync(process.execPath, [path.join(__dirname, 'index.js'), '--input', input, '--out-dir', tmp, '--index']);
  const pluginFile = path.join(tmp, 'example.ranobe.js');
  const indexFile = path.join(tmp, 'plugins.json');
  assert.ok(fs.existsSync(pluginFile));
  const index = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
  assert.equal(index.length, 1);
  assert.equal(index[0].id, 'example.ranobe');
  assert.equal(index[0].sha256, sha256(fs.readFileSync(pluginFile)));
});

test('правила репозитория в сгенерированном коде', () => {
  for (const config of [exampleConfig, htmlConfig]) {
    const code = generatePluginCode(config);
    assert.ok(!code.includes('\t'), 'табуляция в коде');
    const long = code.split('\n').filter((line) => line.length > 120);
    assert.deepEqual(long, [], 'строки длиннее 120 символов: ' + JSON.stringify(long));
    assert.ok(!/TODO|FIXME/.test(code), 'TODO/FIXME в коде');
    assert.ok(/^\s*\/\/\s*[А-Яа-яЁё]/m.test(code), 'нет комментариев по-русски');
  }
});

test('плохой конфиг отклоняется с понятным сообщением', () => {
  const bad = Object.assign({}, exampleConfig, { site: 'example.org' });
  assert.throws(() => generatePluginCode(bad), /site/);
  const badVersion = Object.assign({}, exampleConfig, { version: 'abc' });
  const errors = validateConfig(badVersion);
  assert.ok(errors.some((message) => /version/.test(message)));
  const badMode = Object.assign({}, htmlConfig, { chapter: { content: '.x', mode: 'markdown' } });
  assert.throws(() => generatePluginCode(badMode), /mode/);
});

test('имена файлов чистятся от недопустимых символов', () => {
  assert.equal(sanitizeFileName('example.ranobe'), 'example.ranobe');
  assert.equal(sanitizeFileName('a b/c:d'), 'a_b_c_d');
});

test('генерация не трогает чужие записи индекса', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ranobe-gen-'));
  fs.writeFileSync(path.join(tmp, 'plugins.json'), JSON.stringify([{ id: 'other.plugin' }], null, 2), 'utf8');
  writeExample(exampleConfig, tmp, { index: true });
  const index = JSON.parse(fs.readFileSync(path.join(tmp, 'plugins.json'), 'utf8'));
  assert.deepEqual(index.map((entry) => entry.id), ['other.plugin', 'example.ranobe']);
});

test('сгенерированный код синтаксически корректен', () => {
  for (const config of [exampleConfig, htmlConfig]) {
    assert.doesNotThrow(() => new Function(generatePluginCode(config)));
  }
});

test('селектор карточки совпадает с самим элементом: имя и ссылка из него', async () => {
  FIXTURE_URLS[SITE + '/catalog2?page=1'] = 'catalog-self.html';
  const selfConfig = JSON.parse(JSON.stringify(exampleConfig));
  selfConfig.listings = {
    popular: { path: '/catalog2?page={page}', item: '.card-link', name: '.card-link', link: '.card-link@href' },
  };
  const plugin = loadPlugin(generatePluginCode(selfConfig), []);
  const cards = await plugin.popularNovels('1');
  assert.equal(cards.length, 2);
  assert.equal(cards[0].name, 'Альфа');
  assert.equal(cards[0].path, SITE + '/novels/alpha');
});

test('реальный jut.su: живая разметка, предок-ссылка, обложка из style, кодировка', async () => {
  const log = [];
  const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'examples', 'real-jutsu.json'), 'utf8'));
  const plugin = loadPlugin(generatePluginCode(cfg), log);
  const cards = await plugin.popularNovels('1');
  assert.ok(cards.length >= 3, 'карточек должно быть не меньше трёх');
  assert.equal(cards[0].name, 'Атака титанов');
  assert.equal(cards[0].path, 'https://jut.su/shingekii-no-kyojin/');
  assert.ok(String(cards[0].cover).startsWith('https://gen.jut.su/uploads/'), 'обложка из url(...)');
  assert.equal(log[0].options && log[0].options.textEncoding, 'windows-1251');
});

test('реальный ranobehub: живая разметка, карточки с адресами и именами', async () => {
  const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'examples', 'real-ranobehub.json'), 'utf8'));
  const plugin = loadPlugin(generatePluginCode(cfg), []);
  const cards = await plugin.popularNovels('1');
  assert.ok(cards.length >= 3, 'карточек должно быть не меньше трёх');
  assert.ok(cards[0].path.startsWith('https://ranobehub.org/ranobe/'), 'адрес карточки');
  assert.ok(cards[0].name && cards[0].name.length > 2, 'имя карточки не пустое');
});
