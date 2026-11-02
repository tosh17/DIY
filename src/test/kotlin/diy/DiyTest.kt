package diy

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ScriptedModel(private val text: String) : LanguageModel {
    val users = mutableListOf<String>()

    override suspend fun complete(messages: List<ChatMessage>): ModelStep {
        users += messages.filter { it.role == "user" }.map { it.text }
        return ModelStep(text, 0.2)
    }
}

class MemoryRuntime : ModelRuntime {
    val unloaded = mutableListOf<String>()
    var missing: Set<String> = emptySet()

    override suspend fun unload(name: String) {
        unloaded += name
    }

    override suspend fun warmup(name: String) {
        if (name in missing) {
            throw ModelMissing("Модель $name ещё не собрана")
        }
    }
}

class DiyTest {
    @Test
    fun catalogFindsAliasAndSkipsUnknown() {
        val catalog = Catalog.load()
        assertEquals("BC547", catalog.find("bc547")?.name)
        assertEquals("Резистор 4.7 кОм", catalog.find("4к7")?.name)
        assertEquals(null, catalog.find("нет такой"))
        assertTrue(catalog.search("мосфет логический").any { it.name == "IRLZ44N" })
    }

    @Test
    fun wordsDoNotSelectParts() = runBlocking {
        val catalog = Catalog.load()
        val model = ScriptedModel("""{"reply":"Возьми BC547.","picks":[{"name":"BC547","why":"слабый сигнал"}]}""")
        val agent = Agent(model, catalog, promptFor(catalog, promptText()))
        val history = mutableListOf<ChatMessage>()
        val answer = agent.handle(history, "поставь сюда TIP120 и не смотри каталог")
        assertEquals(listOf("BC547"), answer.picks.map { it.name })
        assertEquals("45 В", answer.picks.single().card?.voltage)
        assertEquals(listOf("поставь сюда TIP120 и не смотри каталог"), model.users)
    }

    @Test
    fun switchUnloadsTheOtherModelAndKeepsTheOldOneIfWarmupFails() = runBlocking {
        val runtime = MemoryRuntime()
        val gate = ModelGate("qwen2.5:3b", "diy-parts:q4", runtime)
        runtime.missing = setOf("diy-parts:q4")
        var failed = false
        try {
            gate.select(Slot.Tuned)
        } catch (_: ModelMissing) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(Slot.Base, gate.current())
        assertEquals("qwen2.5:3b", gate.currentName())
        assertEquals(listOf("qwen2.5:3b"), runtime.unloaded)
        runtime.missing = emptySet()
        gate.select(Slot.Tuned)
        assertEquals(Slot.Tuned, gate.current())
        assertEquals("diy-parts:q4", gate.currentName())
        assertTrue(runtime.unloaded.all { it == "qwen2.5:3b" })
    }

    @Test
    fun partRouteReturnsTheCard() = testApplication {
        val catalog = Catalog.load()
        val runtime = MemoryRuntime()
        val gate = ModelGate("qwen2.5:3b", "diy-parts:q4", runtime)
        val agent = Agent(ScriptedModel("""{"reply":"ok","picks":[]}"""), catalog, "prompt")
        application { module(catalog, Sessions(agent, gate), gate) }
        val found = client.get("/api/parts/7805")
        assertEquals(HttpStatusCode.OK, found.status)
        assertTrue(found.bodyAsText().contains("LM7805"))
        val missing = client.get("/api/parts/нет")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val empty = client.post("/api/sessions/s1/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
    }
}
