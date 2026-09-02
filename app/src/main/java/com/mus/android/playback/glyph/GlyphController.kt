package com.mus.android.playback.glyph

/**
 * Interface for Nothing Phone Glyph LED integration.
 * The stub implementation does nothing; swap in NothingGlyphController
 * when the GDK .aar is available.
 */
interface GlyphController {
    fun onPlayStateChanged(playing: Boolean)
    fun onAmplitudeUpdate(amplitude: Float)
    fun release()
}

/**
 * No-op implementation for devices without Glyph hardware.
 */
class StubGlyphController : GlyphController {
    override fun onPlayStateChanged(playing: Boolean) {}
    override fun onAmplitudeUpdate(amplitude: Float) {}
    override fun release() {}
}
