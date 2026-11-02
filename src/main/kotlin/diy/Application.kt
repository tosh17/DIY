package diy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

data class Config(
    val host: String,
    val port: Int,
    val ollamaUrl: String,
    val baseModel: String,
    val tunedModel: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
            host = env["HOST"] ?: "0.0.0.0",
            port = env["PORT"]?.toIntOrNull() ?: 8092,
            ollamaUrl = (env["OLLAMA_URL"] ?: "http://10.10.1.101:11434").trimEnd('/'),
            baseModel = env["MODEL_BASE"] ?: "qwen2.5:3b",
            tunedModel = env["MODEL_TUNED"] ?: "diy-parts:q4",
        )
    }
}

fun promptText(): String =
    Config::class.java.getResourceAsStream("/prompt.txt")?.bufferedReader()?.readText()
        ?: error("В ресурсах нет prompt.txt")

fun main() {
    val config = Config.fromEnv()
    val catalog = Catalog.load()
    val prompt = promptFor(catalog, promptText())
    val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
            connectTimeoutMillis = 10_000
        }
    }
    val runtime = OllamaRuntime(http, config.ollamaUrl)
    val gate = ModelGate(config.baseModel, config.tunedModel, runtime)
    val agent = Agent(OllamaChat(http, config.ollamaUrl, gate), catalog, prompt)
    embeddedServer(Netty, host = config.host, port = config.port) {
        module(catalog, Sessions(agent, gate), gate)
    }.start(wait = true)
}
