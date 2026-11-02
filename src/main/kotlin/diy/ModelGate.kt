package diy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

enum class Slot { Base, Tuned }

class ModelGate(
    private val baseName: String,
    private val tunedName: String,
    private val runtime: ModelRuntime,
) {
    private val logger = LoggerFactory.getLogger(ModelGate::class.java)
    private val mutex = Mutex()
    private var slot: Slot = Slot.Base

    fun current(): Slot = slot

    fun currentName(): String = nameOf(slot)

    fun nameOf(slot: Slot): String = when (slot) {
        Slot.Base -> baseName
        Slot.Tuned -> tunedName
    }

    suspend fun select(next: Slot) {
        mutex.withLock {
            if (next == slot) {
                return
            }
            val previousName = nameOf(slot)
            val nextName = nameOf(next)
            logger.info("гашу {} и поднимаю {}", previousName, nextName)
            runtime.unload(previousName)
            try {
                runtime.warmup(nextName)
            } catch (error: Exception) {
                logger.warn("новая модель не поднялась, остаюсь на {}", previousName, error)
                throw error
            }
            runtime.unload(previousName)
            slot = next
        }
    }
}
