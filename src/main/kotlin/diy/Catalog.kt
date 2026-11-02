package diy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Part(
    val name: String,
    val aliases: List<String> = emptyList(),
    val kind: String,
    val summary: String,
    val voltage: String,
    val current: String,
    @SerialName("package") val pack: String,
    val held: Boolean = false,
)

@Serializable
data class PartCard(
    val name: String,
    val kind: String,
    val summary: String,
    val voltage: String,
    val current: String,
    val pack: String,
)

class Catalog(
    private val parts: List<Part>,
) {
    fun all(): List<PartCard> = parts.map { it.card() }

    fun find(raw: String): PartCard? {
        val key = key(raw)
        if (key.isEmpty()) {
            return null
        }
        return parts.firstOrNull { part ->
            key(part.name) == key || part.aliases.any { key(it) == key }
        }?.card()
    }

    fun search(query: String): List<PartCard> {
        val words = words(query)
        if (words.isEmpty()) {
            return emptyList()
        }
        return parts.map { part -> part to score(part, words) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first.card() }
    }

    fun index(): String = parts.joinToString("\n") { part -> "- ${part.name} — ${part.summary}" }

    private fun score(part: Part, words: List<String>): Int {
        val blob = key(listOf(part.name, part.kind, part.summary, part.voltage, part.current, part.pack).joinToString(" ") + " " + part.aliases.joinToString(" "))
        return words.count { word -> blob.contains(key(word)) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(): Catalog {
            val text = Catalog::class.java.getResourceAsStream("/catalog.json")?.bufferedReader()?.readText()
                ?: error("В ресурсах нет catalog.json")
            return Catalog(json.decodeFromString(text))
        }
    }
}

fun Part.card(): PartCard = PartCard(
    name = name,
    kind = kind,
    summary = summary,
    voltage = voltage,
    current = current,
    pack = pack,
)

fun key(text: String): String = text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

fun words(text: String): List<String> = text.split(' ', '\n', '\t', ',', '.', ':', ';', '?', '!', '(', ')')
    .map { it.trim() }
    .filter { it.length >= 2 }
