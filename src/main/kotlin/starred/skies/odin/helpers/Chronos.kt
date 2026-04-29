package starred.skies.odin.helpers

import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import xyz.aerii.library.handlers.time.AbstractChronos

object Chronos : AbstractChronos() {
    init {
        on<TickEvent.Start> {
            client0()
        }

        on<TickEvent.End> {
            client1()
        }
    }
}