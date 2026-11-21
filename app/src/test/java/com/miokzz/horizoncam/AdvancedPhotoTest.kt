package com.miokzz.horizoncam

import com.miokzz.horizoncam.photo.*
import org.junit.Assert.*
import org.junit.Test

class AdvancedPhotoTest {
    private val keys = setOf(ManualPhotoKeys.AE_MODE,ManualPhotoKeys.ISO,ManualPhotoKeys.EXPOSURE,
        ManualPhotoKeys.FRAME_DURATION,ManualPhotoKeys.AF_MODE,ManualPhotoKeys.FOCUS,ManualPhotoKeys.AWB_MODE)
    private fun manual() = ManualPhotoCapabilities(isoRange=50..3200,
        exposureTimeNsRange=100_000L..2_000_000_000L,maxFrameDurationNs=2_000_000_000L,
        focusDistanceRange=0f..10f,awbModes=listOf(1,2,5,6),requestKeys=keys,
        manualSensor=true,aeOffAvailable=true,afOffAvailable=true)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Unsupported manual/format settings were accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun defaultsStayFastJpegAutoAndRawHasRealDngMetadata() {
        val defaults = AdvancedPhotoSettings()
        assertEquals(PhotoFormat.JPEG,defaults.format)
        assertFalse(defaults.highResolution)
        assertTrue(defaults.manual.isAuto)
        assertEquals(PhotoOutputSpec("dng","image/x-adobe-dng","Pictures/HorizonCam"),
            PhotoOutputSpec.forFormat(PhotoFormat.RAW_DNG))
        assertEquals("image/jpeg",PhotoOutputSpec.forFormat(PhotoFormat.JPEG).mimeType)
    }

    @Test fun isoAndShutterAreAnAtomicAeOffPairWithinEverySensorLimit() {
        val cap = manual()
        assertTrue(cap.supportsManualExposure)
        ManualPhotoSettings(iso=50,exposureTimeNs=100_000L).validate(cap)
        ManualPhotoSettings(iso=3200,exposureTimeNs=2_000_000_000L).validate(cap)
        reject { ManualPhotoSettings(iso=100).validate(cap) }
        reject { ManualPhotoSettings(exposureTimeNs=1_000_000L).validate(cap) }
        reject { ManualPhotoSettings(iso=49,exposureTimeNs=1_000_000L).validate(cap) }
        reject { ManualPhotoSettings(iso=3201,exposureTimeNs=1_000_000L).validate(cap) }
        reject { ManualPhotoSettings(iso=100,exposureTimeNs=99_999L).validate(cap) }
        reject { ManualPhotoSettings(iso=100,exposureTimeNs=2_000_000_001L).validate(cap) }
        reject { ManualPhotoSettings(iso=100,exposureTimeNs=1_000_000_000L)
            .validate(cap.copy(maxFrameDurationNs=500_000_000L)) }
    }

    @Test fun manualExposureRequiresCapabilityAeOffAndAllActualRequestKeys() {
        val setting = ManualPhotoSettings(iso=100,exposureTimeNs=1_000_000L)
        for (key in listOf(ManualPhotoKeys.AE_MODE,ManualPhotoKeys.ISO,ManualPhotoKeys.EXPOSURE,ManualPhotoKeys.FRAME_DURATION)) {
            reject { setting.validate(manual().copy(requestKeys=keys-key)) }
        }
        reject { setting.validate(manual().copy(manualSensor=false)) }
        reject { setting.validate(manual().copy(aeOffAvailable=false)) }
        reject { setting.validate(manual().copy(maxFrameDurationNs=null)) }
        ManualPhotoSettings().validate(ManualPhotoCapabilities())
    }

    @Test fun focusAndWhiteBalanceNeverOfferUnsupportedOrInvalidModes() {
        val cap = manual()
        ManualPhotoSettings(focusDistanceDiopters=0f,awbMode=1).validate(cap)
        ManualPhotoSettings(focusDistanceDiopters=10f,awbMode=5).validate(cap)
        for (distance in listOf(-0.1f,10.1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            reject { ManualPhotoSettings(focusDistanceDiopters=distance).validate(cap) }
        }
        reject { ManualPhotoSettings(focusDistanceDiopters=1f).validate(cap.copy(afOffAvailable=false)) }
        reject { ManualPhotoSettings(focusDistanceDiopters=1f).validate(cap.copy(focusDistanceRange=0f..0f)) }
        reject { ManualPhotoSettings(awbMode=0).validate(cap.copy(awbModes=listOf(0,1))) }
        reject { ManualPhotoSettings(awbMode=8).validate(cap) }
        reject { ManualPhotoSettings(awbMode=1).validate(cap.copy(requestKeys=keys-ManualPhotoKeys.AWB_MODE)) }
    }

    @Test fun highResolutionMeansAnActualAdditionalLargerStandardSensorSize() {
        val normal = listOf(PhotoSize(4000,3000),PhotoSize(1920,1080))
        val duplicate = listOf(PhotoSize(4000,3000),PhotoSize(1920,1080))
        assertFalse(AdvancedPhotoCapabilities(normalJpegSizes=normal,highResolutionJpegSizes=duplicate).highResolutionAvailable)
        val high = duplicate + PhotoSize(8000,6000)
        val cap = AdvancedPhotoCapabilities(normalJpegSizes=normal,highResolutionJpegSizes=high)
        assertTrue(cap.highResolutionAvailable)
        assertEquals(listOf(PhotoSize(8000,6000)),AdvancedPhotoCapabilities.largerHighResolutionSizes(normal,high))
        cap.validate(AdvancedPhotoSettings(highResolution=true))
        reject { AdvancedPhotoCapabilities().validate(AdvancedPhotoSettings(highResolution=true)) }
        assertTrue(AdvancedPhotoCapabilities.largerHighResolutionSizes(emptyList(),high).isEmpty())
    }

    @Test fun rawRequiresCameraXFormatAndRawStreamSizesWhileHdrRemainsJpegAuto() {
        val caps = AdvancedPhotoCapabilities(formats=setOf(PhotoFormat.JPEG,PhotoFormat.RAW_DNG),
            normalJpegSizes=listOf(PhotoSize(4000,3000)),highResolutionJpegSizes=listOf(PhotoSize(8000,6000)),
            rawSizes=listOf(PhotoSize(4000,3000)),manual=manual())
        caps.validate(AdvancedPhotoSettings(format=PhotoFormat.RAW_DNG))
        reject { caps.copy(rawSizes=emptyList()).validate(AdvancedPhotoSettings(format=PhotoFormat.RAW_DNG)) }
        reject { caps.copy(formats=setOf(PhotoFormat.JPEG)).validate(AdvancedPhotoSettings(format=PhotoFormat.RAW_DNG)) }
        reject { caps.validate(AdvancedPhotoSettings(format=PhotoFormat.RAW_DNG,highResolution=true)) }
        reject { caps.validate(AdvancedPhotoSettings(format=PhotoFormat.RAW_DNG),extensionActive=true) }
        reject { caps.validate(AdvancedPhotoSettings(highResolution=true),extensionActive=true) }
        reject { caps.validate(AdvancedPhotoSettings(manual=ManualPhotoSettings(iso=100,exposureTimeNs=1_000_000L)),extensionActive=true) }
        caps.validate(AdvancedPhotoSettings(),extensionActive=true)
    }
}
