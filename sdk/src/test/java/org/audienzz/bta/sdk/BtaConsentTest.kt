package org.audienzz.bta.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure analytics-consent decision in [BtaConsent.analyticsDecision].
 *
 * Covers the decision table: analytics is suppressed only when GDPR positively applies
 * (`IABTCF_gdprApplies == 1`) and TCF Purpose 1 consent is not granted.
 */
class BtaConsentTest {

    // ── No CMP / GDPR not applicable → always allowed (legacy behaviour) ──────────

    @Test
    fun `no CMP data allows analytics`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = null, purposeConsents = null))
    }

    @Test
    fun `gdpr not applicable allows analytics regardless of purposes`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = 0, purposeConsents = null))
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = 0, purposeConsents = "000000000000"))
    }

    @Test
    fun `gdprApplies absent but purposes present still allows (not positively applicable)`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = null, purposeConsents = "000000000000"))
    }

    // ── GDPR applies → gated on Purpose 1 ────────────────────────────────────────

    @Test
    fun `gdpr applies with purpose 1 granted allows analytics`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "100000000000"))
    }

    @Test
    fun `gdpr applies with purpose 1 granted among others allows analytics`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "111100001010"))
    }

    @Test
    fun `gdpr applies with purpose 1 denied suppresses analytics`() {
        assertFalse(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "011111111111"))
    }

    @Test
    fun `gdpr applies with all purposes denied suppresses analytics`() {
        assertFalse(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "000000000000"))
    }

    @Test
    fun `gdpr applies with missing purposes string suppresses analytics`() {
        assertFalse(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = null))
    }

    @Test
    fun `gdpr applies with empty purposes string suppresses analytics`() {
        assertFalse(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = ""))
    }

    // ── Robustness: short / malformed purposes string under GDPR ──────────────────

    @Test
    fun `single granted purpose bit allows`() {
        assertTrue(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "1"))
    }

    @Test
    fun `single denied purpose bit suppresses`() {
        assertFalse(BtaConsent.analyticsDecision(gdprApplies = 1, purposeConsents = "0"))
    }
}
