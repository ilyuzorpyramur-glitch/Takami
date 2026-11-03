/**
 * Приёмник отчётов о сбоях.
 *
 * Только POST и только JSON по схеме: неизвестные поля отбрасываются,
 * неверные типы дают 400. Без общего секрета — 401. Записи живут
 * RETENTION_DAYS дней.
 */

const MAX_BYTES = 64 * 1024;
const MAX_EVENTS = 50;
const PREFIX = "r:";

const FIELDS = {
    host: "string",
    step: "string",
    error: "string",
    health: "string",
    code: "number",
    durationMs: "number",
    appVersion: "string",
    specVersion: "number",
    installId: "string",
    at: "number",
};

function json(body, status) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json; charset=utf-8" },
    });
}

/** Оставляет только известные поля и проверяет их типы. */
function cleanEvent(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
    const event = {};
    for (const [name, type] of Object.entries(FIELDS)) {
        const value = raw[name];
        if (value === undefined || value === null) continue;
        if (typeof value !== type) throw new Error("поле " + name + " не того типа");
        event[name] = type === "string" ? String(value).slice(0, 120) : value;
    }
    if (!event.host) throw new Error("нет домена источника");
    return event;
}

async function handlePost(request, env) {
    const expected = env.TELEMETRY_TOKEN;
    if (!expected) return json({ ok: false, error: "приёмник не настроен" }, 503);
    const given = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
    if (given !== expected) return json({ ok: false }, 401);

    const declared = Number(request.headers.get("content-length") || "0");
    if (declared > MAX_BYTES) return json({ ok: false, error: "тело слишком большое" }, 413);
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BYTES) {
        return json({ ok: false, error: "тело слишком большое" }, 413);
    }

    let payload;
    try {
        payload = JSON.parse(text);
    } catch {
        return json({ ok: false, error: "не JSON" }, 400);
    }
    if (!payload || !Array.isArray(payload.events)) {
        return json({ ok: false, error: "ожидался список событий" }, 400);
    }
    if (payload.events.length > MAX_EVENTS) {
        return json({ ok: false, error: "слишком много событий" }, 400);
    }

    const events = [];
    try {
        for (const raw of payload.events) {
            const event = cleanEvent(raw);
            if (event) events.push(event);
        }
    } catch (error) {
        return json({ ok: false, error: String(error.message || error) }, 400);
    }

    const now = Date.now();
    const kept = [];
    for (const event of events) {
        const key = PREFIX + now + ":" + crypto.randomUUID();
        await env.REPORTS.put(key, JSON.stringify(event), {
            metadata: { at: now },
            // Срок жизни ключа закрывает удаление сам, чистка ниже — на случай
            // смены настройки.
            expirationTtl: retentionSeconds(env),
        });
        kept.push(key);
    }
    return json({ ok: true, accepted: kept.length }, 200);
}

function retentionSeconds(env) {
    const days = Number(env.RETENTION_DAYS || "90");
    const safe = Number.isFinite(days) && days > 0 ? Math.min(days, 365) : 90;
    return Math.floor(safe * 24 * 60 * 60);
}

/** Удаляет записи старше срока хранения. */
async function purge(env) {
    const cutoff = Date.now() - retentionSeconds(env) * 1000;
    let cursor;
    do {
        const page = await env.REPORTS.list({ prefix: PREFIX, cursor });
        for (const key of page.keys) {
            const stamp = Number(key.name.slice(PREFIX.length).split(":")[0]);
            if (Number.isFinite(stamp) && stamp < cutoff) {
                await env.REPORTS.delete(key.name);
            }
        }
        cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
}

export default {
    async fetch(request, env) {
        if (request.method !== "POST") {
            return json({ ok: false, error: "только POST" }, 405);
        }
        try {
            return await handlePost(request, env);
        } catch (error) {
            return json({ ok: false, error: "внутренняя ошибка" }, 500);
        }
    },

    async scheduled(_event, env) {
        await purge(env);
    },
};
