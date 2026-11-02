package diy

import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatMessage(val role: String, val text: String)

data class Pick(val name: String, val why: String, val card: PartCard?)

data class Answer(
    val reply: String,
    val picks: List<Pick>,
    val model: String,
    val seconds: Double,
)

interface LanguageModel {
    suspend fun complete(messages: List<ChatMessage>): ModelStep
}

data class ModelStep(val text: String, val seconds: Double)

class Agent(
    private val model: LanguageModel,
    private val catalog: Catalog,
    private val prompt: String,
) {
    suspend fun handle(history: MutableList<ChatMessage>, userText: String): Answer {
        history += ChatMessage("user", userText)
        val step = model.complete(listOf(ChatMessage("system", prompt)) + history)
        val parsed = parseReply(step.text)
        val picks = parsed.picks.map { (name, why) ->
            Pick(name = name, why = why, card = catalog.find(name))
        }
        val reply = parsed.reply.ifBlank { step.text.trim() }
        history += ChatMessage("assistant", reply)
        return Answer(reply = reply, picks = picks, model = "", seconds = step.seconds)
    }
}

data class ParsedReply(val reply: String, val picks: List<Pair<String, String>>)

fun parseReply(text: String): ParsedReply {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) {
        return ParsedReply(text.trim(), emptyList())
    }
    val obj = try {
        Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
    } catch (_: Exception) {
        return ParsedReply(text.trim(), emptyList())
    }
    val reply = obj["reply"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val picks = (obj["picks"] as? JsonArray)?.jsonArray.orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) {
            null
        } else {
            name to item["why"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        }
    }
    return ParsedReply(if (reply.isEmpty()) text.trim() else reply, picks)
}

fun promptFor(catalog: Catalog, template: String): String = template.replace("{{catalog}}", catalog.index())

fun roundSeconds(value: Double): Double = round(value * 10.0) / 10.0
