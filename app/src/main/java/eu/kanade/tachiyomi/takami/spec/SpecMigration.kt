package eu.kanade.tachiyomi.takami.spec

import eu.kanade.tachiyomi.takami.SiteSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Что получилось при чтении файла спек и менялся ли он. */
data class MigrationResult(
    val specs: List<SiteSpec>,
    val changed: Boolean,
    val reason: String? = null,
)

/**
 * Чтение файла спек с оглядкой на прошлые установки.
 *
  * Файлы без поля версии писались до появления версий: у
  * них версия считается
  * первой (1), остальные поля читаются как есть.
  * Неизвестные поля игнорируются —
  * файл, записанный более новой сборкой, не должен ронять
  * приложение.
 *
  * Миграция идемпотентна: во второй раз версия уже
  * текущая, поэтому файл не
 * меняется и переписывать его не нужно.
 */
object SpecMigration {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val listSerializer = ListSerializer(SiteSpec.serializer())

    /**
     * Читает список спек, поднимая старые записи до
     * текущей версии схемы. */
    fun migrate(text: String): MigrationResult {
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return MigrationResult(emptyList(), false, "файл спек не разобрать")
        }
        val array = root as? JsonArray
            ?: return MigrationResult(emptyList(), false, "ожидался список спек")

        var changed = false
        val specs = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val version = obj["version"]?.jsonPrimitive?.intOrNull ?: SPEC_VERSION_LEGACY
            val spec = runCatching { json.decodeFromJsonElement(SiteSpec.serializer(), obj) }
                .getOrNull() ?: return@mapNotNull null
            if (version == SPEC_VERSION) {
                spec
            } else {
                changed = true
                spec.copy(version = SPEC_VERSION)
            }
        }
        return MigrationResult(specs, changed)
    }

    /**
     * Обратная запись теми же настройками: прочитанное
     * пишется без потерь. */
    fun encode(specs: List<SiteSpec>): String = json.encodeToString(listSerializer, specs)
}
