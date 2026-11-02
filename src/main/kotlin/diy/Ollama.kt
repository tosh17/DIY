package diy

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("diy.Ollama")

class ModelMissing(message: String) : RuntimeException(message)

class ModelFailure(message: String) : RuntimeException(message)

interface ModelRuntime {
    suspend fun unload(name: String)
    suspend fun warmup(name: String)
}

class OllamaChat(
    private val http: HttpClient,
    private val baseUrl: String,
    private val gate: ModelGate,
) : LanguageModel {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(messages: List<ChatMessage>): ModelStep {
        val model = gate.currentName()
        val started = System.nanoTime()
        val response = try {
            http.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(body(model, messages, predict = 220).toString())
            }
        } catch (error: ModelMissing) {
            throw error
        } catch (error: Exception) {
            logger.warn("запрос модели не удался", error)
            throw ModelFailure("Модель недоступна")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ModelFailure("Ollama ответила ${response.status.value}")
        }
        val content = json.parseToJsonElement(text).jsonObject["message"]?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        return ModelStep(content, roundSeconds(seconds))
    }

    private fun body(model: String, messages: List<ChatMessage>, predict: Int) = buildJsonObject {
        put("model", model)
        put("stream", false)
        put("messages", JsonArray(messages.map { message ->
            buildJsonObject {
                put("role", message.role)
                put("content", message.text)
            }
        }))
        put("options", buildJsonObject {
            put("temperature", 0)
            put("num_predict", predict)
        })
        put("keep_alive", "10m")
    }
}

class OllamaRuntime(
    private val http: HttpClient,
    private val baseUrl: String,
) : ModelRuntime {
    override suspend fun unload(name: String) {
        val response = try {
            http.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", name)
                    put("keep_alive", 0)
                }.toString())
            }
        } catch (error: Exception) {
            logger.warn("не удалось выгрузить {}", name, error)
            return
        }
        if (!response.status.isSuccess()) {
            logger.info("выгрузка {} вернула {}", name, response.status.value)
        }
    }

    override suspend fun warmup(name: String) {
        val response = try {
            http.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", name)
                    put("stream", false)
                    put("keep_alive", "10m")
                    put("messages", JsonArray(listOf(buildJsonObject {
                        put("role", "user")
                        put("content", "Ответь одним JSON: {\"reply\":\"готово\",\"picks\":[]}")
                    })))
                    put("options", buildJsonObject { put("num_predict", 16); put("temperature", 0) })
                }.toString())
            }
        } catch (error: Exception) {
            throw ModelFailure("Модель $name не поднялась")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            if (response.status.value == 404 || text.contains("not found", ignoreCase = true)) {
                throw ModelMissing("Модель $name ещё не собрана")
            }
            throw ModelFailure("Модель $name не поднялась: ${response.status.value}")
        }
    }
}
