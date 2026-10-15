package echopc

/** Plays 12 s of a real track through the compatible engine: `gradlew :app:smoke -Pmain=echopc.CompatSmokeKt`. */
fun main() {
    var last = PlayerState()
    val p = CompatPlayer({ last = it }, { println("ended") })
    p.setVolume(0.3)
    p.play("dQw4w9WgXcQ", null)
    var t = 0
    while (t < 40 && last.positionMs < 8000 && last.error == null) { Thread.sleep(500); t++; if (t % 4 == 0) println("state: $last") }
    println("RESULT position=${last.positionMs} duration=${last.durationMs} error=${last.error}")
    p.seek(30_000); Thread.sleep(3000); println("after seek position=${last.positionMs}")
    p.togglePause(); Thread.sleep(1000); val a = last.positionMs; Thread.sleep(1000); println("paused: stable=${a == last.positionMs} playing=${last.playing}")
    p.stop()
    kotlin.system.exitProcess(0)
}
