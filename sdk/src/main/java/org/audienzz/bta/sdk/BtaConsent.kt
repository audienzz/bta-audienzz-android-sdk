package org.audienzz.bta.sdk

import android.content.Context
import android.content.SharedPreferences

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
