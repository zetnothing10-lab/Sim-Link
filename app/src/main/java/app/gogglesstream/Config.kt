package app.gogglesstream

/** Stream settings. The goggles' "PC Sim" decoder expects a fixed 1920x1080 H.264 stream. */
data class StreamConfig(
    val host: String,
    val port: Int,
    val fps: Int,
    val bitrateMbps: Int,
) {
    val width = 1920
    val height = 1080
}
