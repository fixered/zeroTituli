package it.zeroTituli

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Fctv33BasketPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(Fctv33Basket())
    }
}
