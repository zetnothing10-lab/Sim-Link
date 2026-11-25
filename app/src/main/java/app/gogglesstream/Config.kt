package app.gogglesstream

/** Stream settings. The goggles' "PC Sim" decoder expects a fixed 1920x1080 H.264 stream. */
data class StreamConfig(
    val host: String,
    val port: Int,
    val fps: Int,
    val bitrateMbps: Int,
    /** Gentler on other apps (DJI Fly): normal-priority encoder. Frame rate is capped to [fps] either way. */
    val lowImpact: Boolean = true,
) {
    val width = 1920
    val height = 1080
}
