package it.zeroTituli

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    companion object {
        const val PREFS = "zeroTituliStreamingCommunity"
        const val PREF_LANG = "lang"
        const val PREF_LANG_POSITION = "langPosition"
        const val PREF_BASE_URL = "baseUrl"
        const val PREF_SHOW_UPCOMING = "showUpcoming"
    }

    private val sharedPref = activity?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(context: Context) {
        val lang = sharedPref?.getString(PREF_LANG, "it") ?: "it"
        // Vuoto significa "cercalo da solo": è il caso normale, l'indirizzo scritto a mano serve
        // solo se il sito trasloca su un dominio che nessuno di quelli vecchi indica ancora.
        val baseUrl = sharedPref?.getString(PREF_BASE_URL, null)?.takeIf { it.isNotBlank() }
        val showUpcoming = sharedPref?.getBoolean(PREF_SHOW_UPCOMING, true) ?: true

        registerMainAPI(
            StreamingCommunity(
                lang = lang,
                prefs = sharedPref,
                customBaseUrl = baseUrl,
                showUpcoming = showUpcoming
            )
        )
        registerExtractorAPI(VixCloudExtractor())
        registerExtractorAPI(VixSrcExtractor())

        openSettings = { ctx ->
            val appCompatActivity = ctx as AppCompatActivity
            Settings(this, sharedPref).show(appCompatActivity.supportFragmentManager, "Frag")
        }
    }
}
