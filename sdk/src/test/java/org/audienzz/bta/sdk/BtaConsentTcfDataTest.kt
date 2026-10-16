package org.audienzz.bta.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure TCData builder [BtaConsent.buildTcData] — the JSON served through the
 * `__tcfapi` bridge. Covers the review findings: restrictions, readiness, missing-key handling,
 * special-feature/custom-purpose mapping, and metadata.
 */
class BtaConsentTcfDataTest {

    private fun inputs(
        tcString: String = "CPtest",
        gdprApplies: Boolean = true,
        purposeConsents: String? = "1111111111",
        purposeLI: String? = "",
        vendorConsents: String? = "",
        vendorLI: String? = "",
        specialFeatures: String? = "",
        publisherConsent: String? = "",
        publisherLI: String? = "",
        customConsents: String? = "",
        customLI: String? = "",
        restrictions: Map<Int, String> = emptyMap(),
        publisherCC: String = "CH",
        purposeOneTreatment: Boolean = false,
        cmpId: Int = 10,
        cmpVersion: Int = 7,
        policyVersion: Int = 4,
    ) = BtaConsent.TcfInputs(
        tcString = tcString,
        gdprApplies = gdprApplies,
        purposeConsents = purposeConsents,
        purposeLegitimateInterests = purposeLI,
        vendorConsents = vendorConsents,
        vendorLegitimateInterests = vendorLI,
        specialFeatureOptIns = specialFeatures,
        publisherConsent = publisherConsent,
        publisherLegitimateInterests = publisherLI,
        publisherCustomConsents = customConsents,
        publisherCustomLegitimateInterests = customLI,
        publisherRestrictions = restrictions,
        publisherCC = publisherCC,
        purposeOneTreatment = purposeOneTreatment,
        cmpId = cmpId,
        cmpVersion = cmpVersion,
        tcfPolicyVersion = policyVersion,
    )

    private fun build(inp: BtaConsent.TcfInputs) = JSONObject(BtaConsent.buildTcData(inp).json)

    @Test
    fun `purpose and vendor consents map 1-based`() {
        val d = build(inputs(purposeConsents = "101", vendorConsents = "0110"))
        val pc = d.getJSONObject("purpose").getJSONObject("consents")
        assertTrue(pc.getBoolean("1")); assertFalse(pc.getBoolean("2")); assertTrue(pc.getBoolean("3"))
        val vc = d.getJSONObject("vendor").getJSONObject("consents")
        assertFalse(vc.getBoolean("1")); assertTrue(vc.getBoolean("2"))
        assertTrue(vc.getBoolean("3")); assertFalse(vc.getBoolean("4"))
    }

    @Test
    fun `missing grants are empty objects, never defaulted to true`() {
        val d = build(inputs(vendorConsents = null, purposeLI = null))
        assertEquals(0, d.getJSONObject("vendor").getJSONObject("consents").length())
        assertEquals(0, d.getJSONObject("purpose").getJSONObject("legitimateInterests").length())
    }

    @Test
    fun `absent purpose consents means not ready and cmpuishown`() {
        val snap = BtaConsent.buildTcData(inputs(purposeConsents = null))
        assertFalse(snap.ready)
        assertEquals("cmpuishown", JSONObject(snap.json).getString("eventStatus"))
    }

    @Test
    fun `purpose present but vendor absent is not ready`() {
        val snap = BtaConsent.buildTcData(inputs(purposeConsents = "1", vendorConsents = null))
        assertFalse(snap.ready)
        assertEquals("cmpuishown", JSONObject(snap.json).getString("eventStatus"))
    }

    @Test
    fun `purpose and vendor present means ready and tcloaded`() {
        val snap = BtaConsent.buildTcData(inputs(purposeConsents = "", vendorConsents = ""))
        assertTrue(snap.ready)
        assertEquals("tcloaded", JSONObject(snap.json).getString("eventStatus"))
        // Empty strings → empty grants, not defaulted true.
        assertEquals(0, JSONObject(snap.json).getJSONObject("purpose").getJSONObject("consents").length())
    }

    @Test
    fun `empty TC string under GDPR is not ready`() {
        val snap = BtaConsent.buildTcData(inputs(tcString = "", gdprApplies = true, purposeConsents = "", vendorConsents = ""))
        assertFalse(snap.ready)
        assertEquals("cmpuishown", JSONObject(snap.json).getString("eventStatus"))
    }

    @Test
    fun `empty TC string without GDPR is ready`() {
        val snap = BtaConsent.buildTcData(inputs(tcString = "", gdprApplies = false, purposeConsents = "", vendorConsents = ""))
        assertTrue(snap.ready)
        assertEquals("tcloaded", JSONObject(snap.json).getString("eventStatus"))
    }

    @Test
    fun `publisher restriction preserves numeric type and drops non-restricted vendors`() {
        // Restriction for purpose 2: vendor 32 = type 0 (not allowed); others no restriction ('_').
        val restr = "_".repeat(31) + "0"
        val d = build(inputs(restrictions = mapOf(2 to restr)))
        val r = d.getJSONObject("publisher").getJSONObject("restrictions")
        assertEquals(0, r.getJSONObject("2").getInt("32"))
        assertFalse(r.getJSONObject("2").has("1")) // '_' → no restriction
    }

    @Test
    fun `restriction types 1 and 2 preserved`() {
        val d = build(inputs(restrictions = mapOf(1 to "12")))
        val r = d.getJSONObject("publisher").getJSONObject("restrictions").getJSONObject("1")
        assertEquals(1, r.getInt("1"))
        assertEquals(2, r.getInt("2"))
    }

    @Test
    fun `special feature optins and custom purposes are mapped`() {
        val d = build(inputs(specialFeatures = "10", customConsents = "01"))
        assertTrue(d.getJSONObject("specialFeatureOptins").getBoolean("1"))
        assertFalse(d.getJSONObject("specialFeatureOptins").getBoolean("2"))
        val cp = d.getJSONObject("publisher").getJSONObject("customPurpose").getJSONObject("consents")
        assertFalse(cp.getBoolean("1")); assertTrue(cp.getBoolean("2"))
    }

    @Test
    fun `metadata comes from inputs and no gvlVersion is fabricated`() {
        val d = build(inputs(cmpId = 42, cmpVersion = 9, policyVersion = 4))
        assertEquals(42, d.getInt("cmpId"))
        assertEquals(9, d.getInt("cmpVersion"))
        assertEquals(4, d.getInt("tcfPolicyVersion"))
        assertFalse("gvlVersion must not be fabricated", d.has("gvlVersion"))
        assertFalse("useNonStandardTexts not sourced from storage → omitted", d.has("useNonStandardTexts"))
        assertFalse("TCF 2.0 field should be gone", d.has("useNonStandardStacks"))
    }
}
