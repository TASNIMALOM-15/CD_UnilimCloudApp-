package compress.joshattic.us.model

/** User choices for the custom background and the Liquid Glass look. */
data class GlassSettings(
    val enabled: Boolean = false,
    val imagePath: String? = null,   // private copy of the chosen picture
    val imageVersion: Long = 0L,     // changes when a new picture is chosen (forces reload)
    val blur: Float = 8f,            // 0..25
    val dim: Float = 0.25f,          // -0.5 (brighter) .. 0.7 (darker)
    val fit: Int = 0,                // 0 cover, 1 contain, 2 center
    val alpha: Float = 0.25f,        // glass transparency 0.08..0.5
    val textMode: Int = 0,           // 0 auto, 1 light text, 2 dark text
    val isVideo: Boolean = false,    // background is a (short) video instead of a picture
    val videoMuted: Boolean = true,  // video sound is OFF by default
    val forcePlay: Boolean = false   // keep the video playing no matter what (retry, never pause)
)
