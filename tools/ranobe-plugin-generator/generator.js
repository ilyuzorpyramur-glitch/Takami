// Генератор плагинов для ранобэ-сайтов: конфиг в JSON -> плагин для рантайма новелл Takami.
// Формат плагина повторяет LNReader-совместимый рантайм приложения: @libs/fetch, cheerio, dayjs.
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function sanitizeFileName(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]/g, '_');
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

// Проверка конфига: без сети и без обращений к сайту, только структура и типы.
function validateConfig(config) {
  const errors = [];
  const required = ['id', 'name', 'site', 'lang', 'version'];
  for (const field of required) {
    if (config[field] === undefined || config[field] === null || config[field] === '') {
      errors.push(`нет обязательного поля "${field}"`);
    }
  }
  if (config.site !== undefined && !/^https?:\/\/.+/.test(config.site)) {
    errors.push('поле "site" должно быть адресом http(s)');
  }
  if (config.version !== undefined) {
    const version = typeof config.version === 'string' ? Number(config.version) : config.version;
    if (!Number.isInteger(version) || version <= 0) {
      errors.push('поле "version" должно быть целым числом больше нуля');
    }
  }
  if (config.listings && config.listings.popular) {
    const popular = config.listings.popular;
    for (const field of ['path', 'item', 'name', 'link']) {
      if (!popular[field]) errors.push('listings.popular: нет поля "' + field + '"');
    }
  }
  if (config.search) {
    for (const field of ['path', 'item', 'name', 'link']) {
      if (!config.search[field]) errors.push('search: нет поля "' + field + '"');
    }
  }
  if (config.novel) {
    if (!config.novel.name) errors.push('novel: нет поля "name"');
    if (!config.novel.chapters || !config.novel.chapters.item || !config.novel.chapters.path) {
      errors.push('novel.chapters: нужны поля "item" и "path"');
    }
  }
  if (config.chapter) {
    if (!config.chapter.content) errors.push('chapter: нет поля "content"');
    const mode = config.chapter.mode || 'text';
    if (mode !== 'text' && mode !== 'html') {
      errors.push('chapter.mode: допустимо "text" или "html", получено "' + mode + '"');
    }
  }
  return errors;
}

function requireValidConfig(config) {
  const errors = validateConfig(config);
  if (errors.length) {
    throw new Error('конфиг не принят: ' + errors.join('; '));
  }
  return config;
}

// Выборка карточек каталога/поиска: item, name, link (селектор или селектор@атрибут), cover.
function cardShape(spec) {
  return {
    item: spec.item,
    name: spec.name,
    link: spec.link,
    cover: spec.cover || null,
  };
}

// Собирает код плагина. Правки генератора живут здесь, сам код плагина перегенерируется.
function generatePluginCode(config) {
  requireValidConfig(config);
  const version = typeof config.version === 'string' ? Number(config.version) : config.version;
  const listings = config.listings || {};
  const popular = listings.popular ? cardShape(listings.popular) : null;
  const latest = listings.latest ? cardShape(listings.latest) : null;
  const search = config.search || null;
  const novel = config.novel || null;
  const chapter = config.chapter || null;

  const popularPath = popular ? JSON.stringify(listings.popular.path) : null;
  const latestPath = latest ? JSON.stringify(listings.latest.path) : null;
  const searchPath = search ? JSON.stringify(search.path) : null;
  const queryEncoding = search && search.queryEncoding === 'none' ? 'none' : 'uriComponent';

  const hasPopular = Boolean(popular);
  const hasLatest = Boolean(latest);
  const hasSearch = Boolean(search);
  const hasNovel = Boolean(novel);
  const hasChapter = Boolean(chapter);

  const lines = [];
  lines.push('// Файл сгенерирован инструментом tools/ranobe-plugin-generator: правки лучше вносить в конфиг и перегенерировать.');
  lines.push('// Совместимо с рантаймом новелл Takami: @libs/fetch, cheerio, dayjs, @libs/isAbsoluteUrl.');
  lines.push("const { fetchApi } = require('@libs/fetch');");
  lines.push("const { load } = require('cheerio');");
  lines.push("const { isUrlAbsolute } = require('@libs/isAbsoluteUrl');");
  if (hasNovel && novel.chapters && novel.chapters.releaseTime) {
    lines.push("const dayjs = require('dayjs');");
  }
  lines.push('');
  lines.push('const site = ' + JSON.stringify(config.site) + ';');
  lines.push('const pluginId = ' + JSON.stringify(config.id) + ';');
  lines.push('const pluginName = ' + JSON.stringify(config.name) + ';');
  lines.push('const lang = ' + JSON.stringify(config.lang) + ';');
  lines.push('const version = ' + version + ';');
  lines.push('');
  lines.push('// Селекторы из конфига: «селектор» берёт текст, «селектор@атрибут» берёт атрибут.');
  if (popular) lines.push('const popularCard = ' + JSON.stringify(cardShape(popular), null, 2) + ';');
  if (latest) lines.push('const latestCard = ' + JSON.stringify(cardShape(latest), null, 2) + ';');
  if (search) lines.push('const searchCard = ' + JSON.stringify(cardShape(search), null, 2) + ';');
  if (search) lines.push('const searchPath = ' + searchPath + ';');
  if (search) lines.push('const queryEncoding = ' + JSON.stringify(queryEncoding) + ';');
  if (popular) lines.push('const popularPath = ' + popularPath + ';');
  if (latest) lines.push('const latestPath = ' + latestPath + ';');
  if (novel) lines.push('const novel = ' + JSON.stringify(novel, null, 2) + ';');
  if (chapter) lines.push('const chapter = ' + JSON.stringify(chapter, null, 2) + ';');
  lines.push('');
  lines.push('// Разбирает спецификацию на селектор и атрибут: «.poster img@src».');
  lines.push('function parseSpec(spec) {');
  lines.push('  const idx = spec.lastIndexOf("@");');
  lines.push('  if (idx > 0) return { sel: spec.slice(0, idx), attr: spec.slice(idx + 1) };');
  lines.push('  return { sel: spec, attr: null };');
  lines.push('}');
  lines.push('');
  lines.push('// Абсолютный адрес от корня сайта; внешние адреса не трогаем.');
  lines.push('function absoluteUrl(value) {');
  lines.push('  const text = String(value == null ? "" : value).trim();');
  lines.push('  if (!text) return null;');
  lines.push('  if (isUrlAbsolute(text)) return text;');
  lines.push('  const base = site.charAt(site.length - 1) === "/" ? site.slice(0, -1) : site;');
  lines.push('  return text.charAt(0) === "/" ? base + text : base + "/" + text;');
  lines.push('}');
  lines.push('');
  lines.push('// Значение карточки по спецификации: атрибут или текст первого совпадения.');
  lines.push('function pickValue($, root, spec) {');
  lines.push('  if (!spec) return null;');
  lines.push('  const parsed = parseSpec(spec);');
  lines.push('  const el = root.find(parsed.sel).first();');
  lines.push('  if (!el.length) return null;');
  lines.push('  if (parsed.attr) {');
  lines.push('    const value = el.attr(parsed.attr);');
  lines.push('    return value == null ? null : String(value).trim();');
  lines.push('  }');
  lines.push('  return el.text().trim() || null;');
  lines.push('}');
  lines.push('');
  lines.push('// Подстановка значений в шаблон адреса: «/catalog?page={page}».');
  lines.push('function urlFor(pattern, values) {');
  lines.push('  let url = String(pattern);');
  lines.push('  Object.keys(values).forEach((key) => {');
  lines.push('    url = url.split("{" + key + "}").join(values[key]);');
  lines.push('  });');
  lines.push('  return absoluteUrl(url);');
  lines.push('}');
  lines.push('');
  lines.push('// Кодировка поискового запроса: encodeURIComponent, если конфиг не просит «none».');
  lines.push('function encodeQuery(query) {');
  lines.push('  return queryEncoding === "none" ? String(query) : encodeURIComponent(String(query));');
  lines.push('}');
  lines.push('');
  lines.push('// Страница каталога: карточки новелл с абсолютными адресами.');
  lines.push('async function fetchCards(pageUrl, spec) {');
  lines.push('  const res = await fetchApi(pageUrl);');
  lines.push('  const body = await res.text();');
  lines.push('  const $ = load(body);');
  lines.push('  return $(spec.item).toArray().map((el) => {');
  lines.push('    const card = $(el);');
  lines.push('    const name = pickValue($, card, spec.name);');
  lines.push('    const path = pickValue($, card, spec.link);');
  lines.push('    const cover = absoluteUrl(pickValue($, card, spec.cover));');
  lines.push('    if (!name || !path) return null;');
  lines.push('    return { name: name, path: absoluteUrl(path), cover: cover };');
  lines.push('  }).filter(Boolean);');
  lines.push('}');
  if (hasPopular) {
    lines.push('');
    lines.push('// Популярное: каталог по шаблону popularPath со страницей {page}.');
    lines.push('async function popularNovels(page = 1, _options) {');
    lines.push('  return fetchCards(urlFor(popularPath, { page: String(page == null ? 1 : page) }), popularCard);');
    lines.push('}');
  }
  if (hasLatest) {
    lines.push('');
    lines.push('// Свежее: каталог по шаблону latestPath, отдельный от популярного.');
    lines.push('async function latestNovels(page = 1) {');
    lines.push('  return fetchCards(urlFor(latestPath, { page: String(page == null ? 1 : page) }), latestCard);');
    lines.push('}');
  }
  if (hasSearch) {
    lines.push('');
    lines.push('// Поиск по сайту: шаблон searchPath со значениями {query} и {page}.');
    lines.push('async function searchNovels(query, page = 1, _filters) {');
    lines.push('  const values = { query: encodeQuery(query), page: String(page == null ? 1 : page) };');
    lines.push('  return fetchCards(urlFor(searchPath, values), searchCard);');
    lines.push('}');
  }
  if (hasNovel) {
    lines.push('');
    lines.push('// Страница новеллы: поля, теги и главы с датами в ISO.');
    lines.push('async function novelDetails(novelUrl) {');
    lines.push('  const res = await fetchApi(absoluteUrl(novelUrl));');
    lines.push('  const body = await res.text();');
    lines.push('  const $ = load(body);');
    lines.push('  const root = $.root();');
    lines.push('  const tagsSpec = novel.tags ? parseSpec(novel.tags) : null;');
    lines.push('  const tags = tagsSpec && !tagsSpec.attr');
    lines.push('    ? root.find(tagsSpec.sel).toArray().map((el) => $(el).text().trim()).filter(Boolean)');
    lines.push('    : [];');
    lines.push('  const chaptersSpec = novel.chapters;');
    lines.push('  const timeSel = chaptersSpec.releaseTime || null;');
    lines.push('  const chapters = $(chaptersSpec.item).toArray().map((el) => {');
    lines.push('    const row = $(el);');
    lines.push('    const timeText = timeSel ? pickValue($, row, timeSel) : "";');
    lines.push('    const moment = timeText ? dayjs(timeText) : null;');
    lines.push('    return {');
    lines.push('      name: pickValue($, row, chaptersSpec.name),');
    lines.push('      path: absoluteUrl(pickValue($, row, chaptersSpec.path)),');
    lines.push('      releaseTime: moment && moment.isValid() ? moment.toISOString() : null,');
    lines.push('    };');
    lines.push('  }).filter((item) => item.path);');
    lines.push('  return {');
    lines.push('    name: pickValue($, root, novel.name),');
    lines.push('    author: pickValue($, root, novel.author),');
    lines.push('    artist: pickValue($, root, novel.artist),');
    lines.push('    cover: absoluteUrl(pickValue($, root, novel.cover)),');
    lines.push('    description: pickValue($, root, novel.description),');
    lines.push('    status: pickValue($, root, novel.status),');
    lines.push('    tags: tags,');
    lines.push('    chapters: chapters,');
    lines.push('  };');
    lines.push('}');
  }
  if (hasChapter) {
    lines.push('');
    lines.push('// Текст главы: чистка служебных блоков, режим «text» или «html» из конфига.');
    lines.push('async function chapterNovelContent(chapterUrl) {');
    lines.push('  const res = await fetchApi(absoluteUrl(chapterUrl));');
    lines.push('  const body = await res.text();');
    lines.push('  const $ = load(body);');
    lines.push('  const holder = $.root().find(chapter.content).first();');
    lines.push('  if (!holder.length) return "";');
    lines.push('  const cleaned = ["script", "style"].concat(chapter.remove || []);');
    lines.push('  cleaned.forEach((sel) => holder.find(sel).remove());');
    lines.push('  if (chapter.mode === "html") return holder.html().trim();');
    lines.push('  const paragraphs = holder.find("p").toArray()');
    lines.push('    .map((el) => $(el).text().trim())');
    lines.push('    .filter(Boolean);');
    lines.push('  if (paragraphs.length) return paragraphs.join("\\n");');
    lines.push('  return holder.text().trim();');
    lines.push('}');
  }
  lines.push('');
  lines.push('// Экспорт плагина: методы только те, что описаны в конфиге.');
  lines.push('module.exports = {');
  lines.push('  id: pluginId,');
  lines.push('  name: pluginName,');
  lines.push('  site: site,');
  lines.push('  lang: lang,');
  lines.push('  version: version,');
  if (hasPopular) lines.push('  popularNovels: popularNovels,');
  if (hasLatest) lines.push('  latestNovels: latestNovels,');
  if (hasSearch) lines.push('  searchNovels: searchNovels,');
  if (hasNovel) lines.push('  novelDetails: novelDetails,');
  if (hasChapter) lines.push('  chapterNovelContent: chapterNovelContent,');
  lines.push('};');
  lines.push('');
  return lines.join('\n');
}

// Запись индекса: формат LNReader-репозитория, который читает приложение (plugins.json).
function indexEntryFor(config, pluginCode, fileName) {
  const version = typeof config.version === 'string' ? Number(config.version) : config.version;
  const entry = {
    id: config.id,
    name: config.name,
    site: config.site,
    lang: config.lang,
    version: version,
    url: fileName,
    sha256: sha256(Buffer.from(pluginCode, 'utf8')),
    hasSettings: Boolean(config.hasSettings),
  };
  if (config.iconUrl) entry.iconUrl = config.iconUrl;
  if (config.customJS) entry.customJS = config.customJS;
  if (config.customCSS) entry.customCSS = config.customCSS;
  return entry;
}

// Полная генерация в каталог: файл плагина и, по желанию, plugins.json со суммой sha256.
function generate(config, outDir, options) {
  const opts = options || {};
  const pluginCode = generatePluginCode(config);
  const fileName = sanitizeFileName(config.id) + '.js';
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, fileName), pluginCode, 'utf8');
  const result = { pluginFile: fileName, pluginCode: pluginCode };
  if (opts.index) {
    const entry = indexEntryFor(config, pluginCode, fileName);
    const indexPath = path.join(outDir, 'plugins.json');
    const existing = fs.existsSync(indexPath) ? JSON.parse(fs.readFileSync(indexPath, 'utf8')) : [];
    const next = existing.filter((item) => item.id !== entry.id).concat([entry]);
    fs.writeFileSync(indexPath, JSON.stringify(next, null, 2) + '\n', 'utf8');
    result.indexFile = 'plugins.json';
    result.indexEntry = entry;
  }
  return result;
}

module.exports = {
  generate,
  generatePluginCode,
  indexEntryFor,
  sha256,
  sanitizeFileName,
  validateConfig,
};
