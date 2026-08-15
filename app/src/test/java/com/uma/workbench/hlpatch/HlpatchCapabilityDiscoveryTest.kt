package com.uma.workbench.hlpatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlpatchCapabilityDiscoveryTest {
    private fun endpoint(
        path: String,
        required: Boolean,
        supported: Boolean,
        status: Int = if (supported) 200 else 404,
        body: String = "",
        error: String? = null
    ) = HlpatchEndpointCapability(path, required, supported, status, body, error)

    @Test fun allObservedEndpointsSupportedIsCompatible() {
        val endpoints = listOf(
            endpoint("/health", true, true),
            endpoint("/summary", true, true),
            endpoint("/api/proxy", false, true)
        )
        assertEquals(HlpatchCompatibility.COMPATIBLE, HlpatchCapabilityClassifier.classify(endpoints))
    }

    @Test fun optionalDifferenceIsDegradedButRequiredDifferenceIsIncompatible() {
        val optionalMissing = listOf(
            endpoint("/health", true, true),
            endpoint("/summary", true, true),
            endpoint("/api/proxy", false, false)
        )
        val requiredMissing = optionalMissing + endpoint("/required-new", true, false)
        assertEquals(HlpatchCompatibility.DEGRADED, HlpatchCapabilityClassifier.classify(optionalMissing))
        assertEquals(HlpatchCompatibility.INCOMPATIBLE, HlpatchCapabilityClassifier.classify(requiredMissing))
    }

    @Test fun unreachableHealthIsNotReportedAsFeatureFailure() {
        val endpoints = listOf(endpoint("/health", true, false, status = 0, error = "connection refused"))
        assertEquals(HlpatchCompatibility.UNREACHABLE, HlpatchCapabilityClassifier.classify(endpoints))
    }

    @Test fun presentationRetainsCompleteBodiesErrorsAndEveryEndpoint() {
        val completeBody = "{\"sid\":\"" + "SID-" + "甲".repeat(12_000) + "\",\"token\":\"" + "令".repeat(8_000) + "\"}"
        val completeError = "failure\n" + "stack evidence\n".repeat(1_000)
        val endpoints = (0 until 240).map { index ->
            endpoint("/endpoint/$index", index == 0, index != 239, body = if (index == 17) completeBody else "body-$index", error = if (index == 239) completeError else null)
        }
        val report = HlpatchCapabilityReport(checkedAt = 8410L, compatibility = HlpatchCompatibility.DEGRADED, endpoints = endpoints)
        val text = HlpatchCapabilityClassifier.presentation(report)

        assertEquals(240, report.endpoints.size)
        assertEquals(completeBody, report.endpoints[17].responseBody)
        assertEquals(completeError, report.endpoints.last().error)
        assertTrue(text.contains(completeBody))
        assertTrue(text.contains(completeError))
        assertTrue(text.contains("/endpoint/239"))
    }
}
