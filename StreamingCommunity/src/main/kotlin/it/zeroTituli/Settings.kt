package it.zeroTituli

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
// Le risorse stanno sotto il `namespace` del modulo (build.gradle.kts alla radice), non sotto il
// package di queste classi: l'identificatore va cercato lì.
import it.fixered.zeroTituli.BuildConfig

class Settings(
    private val plugin: StreamingCommunityPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {

    private var currentLang: String =
        sharedPref?.getString(StreamingCommunityPlugin.PREF_LANG, "it") ?: "it"
    private var currentLangPosition: Int =
        sharedPref?.getInt(StreamingCommunityPlugin.PREF_LANG_POSITION, 0) ?: 0
    private var currentBaseUrl: String =
        SiteDomain.normalize(sharedPref?.getString(StreamingCommunityPlugin.PREF_BASE_URL, "")).orEmpty()

    private fun View.makeTvCompatible() {
        setPadding(paddingLeft + 10, paddingTop + 10, paddingRight + 10, paddingBottom + 10)
        background = drawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    private fun drawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    private fun text(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.byName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let { inflater.inflate(plugin.resources?.getLayout(it), container, false) }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var showUpcoming =
            sharedPref?.getBoolean(StreamingCommunityPlugin.PREF_SHOW_UPCOMING, true) ?: true

        view.byName<TextView>("header_tw")?.text = text("header_tw")
        view.byName<TextView>("lang_label")?.text = text("lang_label")

        val upcomingSw: Switch? = view.byName("upcoming_switch")
        upcomingSw?.makeTvCompatible()
        upcomingSw?.text = text("upcoming_label")
        upcomingSw?.isChecked = showUpcoming
        upcomingSw?.setOnCheckedChangeListener { _, checked -> showUpcoming = checked }

        view.byName<TextView>("server_address_label")?.text = text("server_address_label")
        val addressInput: EditText? = view.byName("server_address_input")
        addressInput?.hint = text("server_address_hint")
        addressInput?.setText(currentBaseUrl)

        // Il dominio in uso adesso: quello scritto a mano, altrimenti quello trovato da solo.
        view.byName<TextView>("current_domain_tw")?.text =
            (text("current_domain_label") ?: "Dominio in uso") + ": " +
                (currentBaseUrl.ifBlank { SiteDomain.cached(sharedPref) ?: SiteDomain.DEFAULT })

        val langs = arrayOf("it", "en")
        val langsDropdown: Spinner? = view.byName("lang_spinner")
        langsDropdown?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            langs.map { text(it) ?: it }
        )
        langsDropdown?.setSelection(currentLangPosition)
        langsDropdown?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentLang = langs[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val saveBtn: ImageButton? = view.byName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(drawable("save_icon"))
        saveBtn?.setOnClickListener {
            val normalized = SiteDomain.normalize(addressInput?.text?.toString())
            sharedPref?.edit {
                putInt(StreamingCommunityPlugin.PREF_LANG_POSITION, langs.indexOf(currentLang))
                putString(StreamingCommunityPlugin.PREF_LANG, currentLang)
                putBoolean(StreamingCommunityPlugin.PREF_SHOW_UPCOMING, showUpcoming)
                if (normalized.isNullOrBlank()) remove(StreamingCommunityPlugin.PREF_BASE_URL)
                else putString(StreamingCommunityPlugin.PREF_BASE_URL, normalized)
            }
            currentBaseUrl = normalized.orEmpty()
            showToast(text("settings_saved") ?: "Salvato. Riavvia l'app per applicare")
            dismiss()
        }
    }
}
