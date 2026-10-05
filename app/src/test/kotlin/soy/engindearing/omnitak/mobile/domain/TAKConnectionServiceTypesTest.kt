package soy.engindearing.omnitak.mobile.domain

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #223 — which foreground service types the connection service asks for.
 * `dataSync` is limited to six hours of background time per day on
 * Android 15+, and the limit also applies when it is combined with
 * `location`, so the two must never be requested together.
 */
class TAKConnectionServiceTypesTest {

    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    @Test
    fun location_granted_asks_for_location_alone_first() {
        val candidates = TAKConnectionService.foregroundTypeCandidates(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            hasLocation = true,
        )
        assertEquals(listOf(location, dataSync), candidates)
    }

    @Test
    fun location_denied_falls_back_to_dataSync() {
        val candidates = TAKConnectionService.foregroundTypeCandidates(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            hasLocation = false,
        )
        assertEquals(listOf(dataSync), candidates)
    }

    @Test
    fun dataSync_is_never_combined_with_another_type() {
        for (sdk in listOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, Build.VERSION_CODES.VANILLA_ICE_CREAM)) {
            for (hasLocation in listOf(true, false)) {
                for (types in TAKConnectionService.foregroundTypeCandidates(sdk, hasLocation)) {
                    assertTrue(
                        "types=$types on sdk $sdk mixes dataSync with another type",
                        types and dataSync == 0 || types == dataSync,
                    )
                }
            }
        }
    }

    @Test
    fun before_android_10_there_are_no_types() {
        val candidates = TAKConnectionService.foregroundTypeCandidates(
            sdkInt = Build.VERSION_CODES.P,
            hasLocation = true,
        )
        assertEquals(listOf(0), candidates)
    }
}
