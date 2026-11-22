package com.miokzz.horizoncam.ui

import android.hardware.camera2.CaptureRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.miokzz.horizoncam.photo.AdvancedPhotoCapabilities
import com.miokzz.horizoncam.photo.AdvancedPhotoSettings
import com.miokzz.horizoncam.photo.ManualPhotoSettings
import com.miokzz.horizoncam.photo.PhotoFormat
import java.util.Locale

class PhotoControlsDialog(private val host: AppCompatActivity) {
    fun format(caps: AdvancedPhotoCapabilities, current: AdvancedPhotoSettings, select: (AdvancedPhotoSettings) -> Unit) {
        val formats = buildList {
            add("JPEG · rápido" to current.copy(format = PhotoFormat.JPEG, highResolution = false))
            if (caps.highResolutionAvailable) add("JPEG · priorizar resolução" to current.copy(format = PhotoFormat.JPEG, highResolution = true))
            if (caps.rawAvailable) add("RAW · DNG do sensor" to current.copy(format = PhotoFormat.RAW_DNG, highResolution = false))
        }
        choose("Formato e resolução", formats, current, select)
    }

    fun manual(caps: AdvancedPhotoCapabilities, current: ManualPhotoSettings, select: (ManualPhotoSettings) -> Unit) {
        val supported = caps.manual
        val items = buildList<Pair<String, () -> Unit>> {
            if (supported.supportsManualExposure) add("ISO e tempo de exposição" to {
                val iso = supported.isoRange!!
                val shutters = supported.exposureTimeNsRange!!
                val maxNs = minOf(shutters.last, supported.maxFrameDurationNs ?: shutters.last)
                val values = (listOf(iso.first, 50, 100, 200, 400, 800, 1600, 3200, iso.last) +
                    listOfNotNull(current.iso)).filter { it in iso }.distinct().sorted()
                choose("Sensibilidade", listOf("Automática" to null) + values.map { "ISO $it" to it }, current.iso) { chosen ->
                    if (chosen == null) select(current.copy(iso = null, exposureTimeNs = null)) else {
                        val times = (listOf(shutters.first, 125_000L, 500_000L, 1_000_000L, 4_000_000L,
                            8_333_333L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L,
                            250_000_000L, 500_000_000L, 1_000_000_000L, maxNs) + listOfNotNull(current.exposureTimeNs))
                            .filter { it in shutters && it <= maxNs }.distinct().sorted()
                        choose("Tempo de exposição", times.map { time ->
                            String.format(Locale.getDefault(), "%.2f ms", time / 1_000_000.0) to time
                        }, current.exposureTimeNs) { select(current.copy(iso = chosen, exposureTimeNs = it)) }
                    }
                }
            })
            supported.focusDistanceRange?.takeIf { supported.supportsManualFocus }?.let { range -> add("Distância de foco" to {
                val values = (listOf(0f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, range.endInclusive) +
                    listOfNotNull(current.focusDistanceDiopters)).filter { it in range }.distinct().sorted()
                val choices = listOf("Automático" to null) + values.map {
                    (if (it == 0f) "Infinito" else String.format(Locale.getDefault(), "%.2f dioptrias", it)) to it
                }
                choose("Foco", choices, current.focusDistanceDiopters) { select(current.copy(focusDistanceDiopters = it)) }
            }) }
            if (supported.awbModes.isNotEmpty()) add("Balanço de branco" to {
                val choices = listOf("Padrão da câmera" to null) + supported.awbModes.map { awbLabel(it) to it }
                choose("Balanço de branco", choices, current.awbMode) { select(current.copy(awbMode = it)) }
            })
            add("Restaurar automático" to { select(ManualPhotoSettings()) })
        }
        AlertDialog.Builder(host).setTitle("Controles Pro").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Fechar", null).show()
    }

    private fun awbLabel(mode: Int) = when (mode) {
        CaptureRequest.CONTROL_AWB_MODE_AUTO -> "Automático"
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Luz do dia"
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Nublado"
        CaptureRequest.CONTROL_AWB_MODE_SHADE -> "Sombra"
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescente"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescente"
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Fluorescente quente"
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "Crepúsculo"
        else -> "Modo $mode"
    }

    private fun <T> choose(title: String, entries: List<Pair<String, T>>, selected: T?, onSelect: (T) -> Unit) {
        AlertDialog.Builder(host).setTitle(title).setSingleChoiceItems(entries.map { it.first }.toTypedArray(),
            entries.indexOfFirst { it.second == selected }) { dialog, i -> dialog.dismiss(); onSelect(entries[i].second) }
            .setNegativeButton("Cancelar", null).show()
    }
}
