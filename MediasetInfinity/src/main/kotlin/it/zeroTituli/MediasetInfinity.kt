package it.zeroTituli

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Mediaset Infinity.
 *
 * Il catalogo arriva dai feed theplatform di Mediaset, aperti; la riproduzione passa
 * da una sessione anonima. Le dirette sono in chiaro e si castano, il catalogo on
 * demand è protetto con Widevine e si vede solo sul dispositivo: il perché sta nel
 * progetto, in docs/superpowers/specs/2026-08-01-mediaset-infinity-design.md.
 */
class MediasetInfinity : MainAPI() {
    override var mainUrl = MediasetUrls.SITE
    override var name = "Mediaset Infinity"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Documentary,
        TvType.Cartoon,
        TvType.Live
    )
}
