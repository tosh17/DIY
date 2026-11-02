package diy

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.http.content.staticResources
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class IncomingMessage(val text: String)

@Serializable
data class ModelRequest(val slot: String)

@Serializable
data class ErrorBody(val error: String)

@Serializable
data class ModelState(val slot: String, val model: String)

@Serializable
data class PickBody(
    val name: String,
    val why: String,
    val summary: String?,
    val voltage: String?,
    val current: String?,
    val pack: String?,
)

@Serializable
data class AnswerBody(
    val reply: String,
    val picks: List<PickBody>,
    val model: String,
    val seconds: Double,
)

class BadRequest(message: String) : RuntimeException(message)

class NotFound(message: String) : RuntimeException(message)

private class Chat {
    val messages = mutableListOf<ChatMessage>()
    val mutex = Mutex()
}

class Sessions(
    private val agent: Agent,
    private val gate: ModelGate,
) {
    private val chats = ConcurrentHashMap<String, Chat>()

    suspend fun message(id: String, text: String): AnswerBody {
        if (id.isBlank()) {
            throw BadRequest("Нет сессии")
        }
        if (text.isBlank()) {
            throw BadRequest("Пустое сообщение")
        }
        val chat = chats.getOrPut(id) { Chat() }
        val answer = chat.mutex.withLock {
            agent.handle(chat.messages, text.trim())
        }
        return answer.toBody(gate.currentName())
    }
}

fun Answer.toBody(modelName: String) = AnswerBody(
    reply = reply,
    picks = picks.map { pick ->
        PickBody(
            name = pick.name,
            why = pick.why,
            summary = pick.card?.summary,
            voltage = pick.card?.voltage,
            current = pick.card?.current,
            pack = pick.card?.pack,
        )
    },
    model = modelName,
    seconds = seconds,
)

fun Application.module(catalog: Catalog, sessions: Sessions, gate: ModelGate) {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<BadRequest> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(error.message ?: "Плохой запрос"))
        }
        exception<ModelMissing> { call, error ->
            call.respond(HttpStatusCode.Conflict, ErrorBody(error.message ?: "Модели нет"))
        }
        exception<ModelFailure> { call, error ->
            call.respond(HttpStatusCode.BadGateway, ErrorBody(error.message ?: "Модель недоступна"))
        }
        exception<NotFound> { call, error ->
            call.respond(HttpStatusCode.NotFound, ErrorBody(error.message ?: "Не найдено"))
        }
    }
    routing {
        staticResources("/", "web")
        get("/api/health") {
            call.respondText("ok")
        }
        get("/api/model") {
            call.respond(ModelState(slot = gate.current().name.lowercase(), model = gate.currentName()))
        }
        post("/api/model") {
            val body = call.receive<ModelRequest>()
            val slot = when (body.slot) {
                "base" -> Slot.Base
                "tuned" -> Slot.Tuned
                else -> throw BadRequest("Слот бывает base или tuned")
            }
            gate.select(slot)
            call.respond(ModelState(slot = gate.current().name.lowercase(), model = gate.currentName()))
        }
        get("/api/parts") {
            val query = call.request.queryParameters["q"].orEmpty()
            val cards = if (query.isBlank()) catalog.all() else catalog.search(query)
            call.respond(cards)
        }
        get("/api/parts/{name}") {
            val name = call.parameters["name"].orEmpty()
            val card = catalog.find(name) ?: throw NotFound("Нет такой детали")
            call.respond(card)
        }
        post("/api/sessions/{id}/messages") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<IncomingMessage>()
            call.respond(sessions.message(id, body.text))
        }
    }
}
