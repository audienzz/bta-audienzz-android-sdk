package org.audienzz.bta.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Reads the IAB TCF v2 in-app consent that a CMP (e.g. Usercentrics) writes to the default
 * [SharedPreferences] (`<package>_preferences`) and derives what the SDK is allowed to do.
 *
 * The CMP persists the standard decoded keys — `IABTCF_TCString`, `IABTCF_gdprApplies` and
 * `IABTCF_PurposeConsents`. The feed's ad layer (Prebid) reads the TC string through the
 * `__tcfapi` bridge injected by `BtaFeedView`; first-party analytics is gated by
 * [analyticsAllowed].
 */
internal object BtaConsent {

    private const val KEY_TC_STRING = "IABTCF_TCString"
    private const val KEY_GDPR_APPLIES = "IABTCF_gdprApplies"
    private const val KEY_PURPOSE_CONSENTS = "IABTCF_PurposeConsents"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    /** The raw TC string a CMP stored, or `null` when none is present. */
    fun tcString(context: Context): String? = try {
        prefs(context).getString(KEY_TC_STRING, null)?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Whether GDPR applies. `1` = applies, `0` = does not. Absent but a TC string exists → applies. */
    fun gdprApplies(context: Context): Boolean = try {
        val p = prefs(context)
        if (p.contains(KEY_GDPR_APPLIES)) p.getInt(KEY_GDPR_APPLIES, 0) == 1
        else tcString(context) != null
    } catch (e: Exception) {
        false
    }

    /** Raw decoded IAB TCF v2 values read from storage, fed to [buildTcData]. */
    internal data class TcfInputs(
        val tcString: String,
        val gdprApplies: Boolean,
        val purposeConsents: String?,
        val purposeLegitimateInterests: String?,
        val vendorConsents: String?,
        val vendorLegitimateInterests: String?,
        val specialFeatureOptIns: String?,
        val publisherConsent: String?,
        val publisherLegitimateInterests: String?,
        val publisherCustomConsents: String?,
        val publisherCustomLegitimateInterests: String?,
        /** purposeId → per-vendor restriction string ('0'/'1'/'2' = type, other char = none). */
        val publisherRestrictions: Map<Int, String>,
        val publisherCC: String,
        val purposeOneTreatment: Boolean,
        val cmpId: Int,
        val cmpVersion: Int,
        val tcfPolicyVersion: Int,
    )

    /** A ready-to-inject TCData JSON payload plus whether the CMP snapshot is complete. */
    internal data class TcfSnapshot(val json: String, val ready: Boolean)

    /**
     * Builds an IAB TCF v2.2 `TCData` object (as JSON) from decoded CMP values. Pure, so it is
     * unit-testable without storage.
     *
     * - `*Consents`/`*LegitimateInterests` binary strings become `{id: bool}` maps (1-based).
     * - Missing grants map to an empty object (never defaulted to granted).
     * - Publisher restrictions are parsed into `{purposeId: {vendorId: type}}`, preserving numeric
     *   restriction types; characters that are not `0/1/2` mean "no restriction" and are dropped.
     * - `ready` is false when the TC string exists but the decoded purpose grants have not been
     *   written yet (CMP mid-update): the snapshot then reports `cmpuishown` rather than a
     *   confident `tcloaded` with empty grants, so callers can wait for the real update.
     * - No GVL version is fabricated; metadata comes from the CMP's own keys.
     */
    internal fun buildTcData(inp: TcfInputs): TcfSnapshot {
        val ready = inp.purposeConsents != null

        fun bits(s: String?): JSONObject {
            val o = JSONObject()
            if (s != null) for (i in s.indices) o.put((i + 1).toString(), s[i] == '1')
            return o
        }

        val restrictions = JSONObject()
        for ((purpose, s) in inp.publisherRestrictions) {
            var entry: JSONObject? = null
            for (i in s.indices) {
                val c = s[i]
                if (c in '0'..'2') {
                    if (entry == null) entry = JSONObject()
                    entry.put((i + 1).toString(), c - '0')
                }
            }
            if (entry != null) restrictions.put(purpose.toString(), entry)
        }

        val publisher = JSONObject()
            .put("consents", bits(inp.publisherConsent))
            .put("legitimateInterests", bits(inp.publisherLegitimateInterests))
            .put(
                "customPurpose",
                JSONObject()
                    .put("consents", bits(inp.publisherCustomConsents))
                    .put("legitimateInterests", bits(inp.publisherCustomLegitimateInterests)),
            )
            .put("restrictions", restrictions)

        val tcData = JSONObject()
            .put("tcString", inp.tcString)
            .put("gdprApplies", inp.gdprApplies)
            .put("eventStatus", if (ready) "tcloaded" else "cmpuishown")
            .put("cmpStatus", "loaded")
            .put("cmpId", inp.cmpId)
            .put("cmpVersion", inp.cmpVersion)
            .put("tcfPolicyVersion", inp.tcfPolicyVersion)
            .put("isServiceSpecific", true)
            .put("useNonStandardTexts", false)
            .put("purposeOneTreatment", inp.purposeOneTreatment)
            .put("publisherCC", inp.publisherCC)
            .put(
                "purpose",
                JSONObject()
                    .put("consents", bits(inp.purposeConsents))
                    .put("legitimateInterests", bits(inp.purposeLegitimateInterests)),
            )
            .put(
                "vendor",
                JSONObject()
                    .put("consents", bits(inp.vendorConsents))
                    .put("legitimateInterests", bits(inp.vendorLegitimateInterests)),
            )
            .put("specialFeatureOptins", bits(inp.specialFeatureOptIns))
            .put("publisher", publisher)

        return TcfSnapshot(tcData.toString(), ready)
    }

    /** Reads the full decoded TCF consent from storage and builds a snapshot, or `null` when no
     *  TC string is stored. */
    fun tcfSnapshot(context: Context): TcfSnapshot? {
        val tc = tcString(context) ?: return null
        val p = try { prefs(context) } catch (e: Exception) { return null }
        fun s(key: String): String? = try { p.getString(key, null) } catch (e: Exception) { null }
        fun i(key: String, default: Int): Int =
            try { if (p.contains(key)) p.getInt(key, default) else default } catch (e: Exception) { default }

        val restr = HashMap<Int, String>()
        for (purpose in 1..24) {
            s("IABTCF_PublisherRestrictions$purpose")?.let { if (it.isNotEmpty()) restr[purpose] = it }
        }

        return buildTcData(
            TcfInputs(
                tcString = tc,
                gdprApplies = gdprApplies(context),
                purposeConsents = s(KEY_PURPOSE_CONSENTS),
                purposeLegitimateInterests = s("IABTCF_PurposeLegitimateInterests"),
                vendorConsents = s("IABTCF_VendorConsents"),
                vendorLegitimateInterests = s("IABTCF_VendorLegitimateInterests"),
                specialFeatureOptIns = s("IABTCF_SpecialFeaturesOptIns"),
                publisherConsent = s("IABTCF_PublisherConsent"),
                publisherLegitimateInterests = s("IABTCF_PublisherLegitimateInterests"),
                publisherCustomConsents = s("IABTCF_PublisherCustomPurposesConsents"),
                publisherCustomLegitimateInterests = s("IABTCF_PublisherCustomPurposesLegitimateInterests"),
                publisherRestrictions = restr,
                publisherCC = s("IABTCF_PublisherCC") ?: "",
                purposeOneTreatment = i("IABTCF_PurposeOneTreatment", 0) == 1,
                cmpId = i("IABTCF_CmpSdkID", 0),
                cmpVersion = i("IABTCF_CmpSdkVersion", 1),
                tcfPolicyVersion = i("IABTCF_PolicyVersion", 4),
            ),
        )
    }

    /**
     * Whether analytics collection/sending is permitted under the current consent.
     *
     * The SDK stores a visitor ID and sends measurement events, which fall under TCF Purpose 1
     * (store and/or access information on a device). Analytics is suppressed **only** when a CMP
     * has positively signalled that GDPR applies *and* Purpose 1 consent is not granted. With no
     * CMP present (no TCF data) or when GDPR does not apply, analytics runs as before, so a
     * publisher without a CMP is not silently regressed.
     */
    fun analyticsAllowed(context: Context): Boolean {
        val p = try {
            prefs(context)
        } catch (e: Exception) {
            return true
        }
        // `IABTCF_gdprApplies` is stored as an Int (1/0); null = the key is absent.
        val gdprApplies: Int? = try {
            if (p.contains(KEY_GDPR_APPLIES)) p.getInt(KEY_GDPR_APPLIES, 0) else null
        } catch (e: Exception) {
            null
        }
        val purposeConsents: String? = try {
            p.getString(KEY_PURPOSE_CONSENTS, null)
        } catch (e: Exception) {
            null
        }
        return analyticsDecision(gdprApplies, purposeConsents)
    }

    /**
     * Pure consent decision, split out from storage so it is unit-testable.
     *
     * @param gdprApplies value of `IABTCF_gdprApplies` (1 = applies, 0 = does not), or `null` when absent.
     * @param purposeConsents value of `IABTCF_PurposeConsents` (a "1"/"0" string per purpose), or `null`.
     * @return `true` unless GDPR positively applies and Purpose 1 consent is not granted.
     */
    internal fun analyticsDecision(gdprApplies: Int?, purposeConsents: String?): Boolean {
        // GDPR positively applies only when the key is present and == 1.
        if (gdprApplies != 1) return true
        // GDPR applies: require Purpose 1 consent; suppress if it cannot be confirmed.
        return hasPurposeConsent(purposeConsents, 1)
    }

    /** Reads bit [purpose] (1-indexed) from a `IABTCF_PurposeConsents`-style "1"/"0" string. */
    private fun hasPurposeConsent(consents: String?, purpose: Int): Boolean {
        if (consents == null) return false
        return purpose in 1..consents.length && consents[purpose - 1] == '1'
    }
}
