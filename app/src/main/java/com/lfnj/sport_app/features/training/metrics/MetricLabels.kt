package com.lfnj.sport_app.features.training.metrics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lfnj.sport_app.R

/**
 * Textos visibles del catálogo de HU034, resueltos por clave desde recursos locales (ADR-030,
 * «Catálogo extensible»). Son recursos de texto opcionales: una clave desconocida —p. ej. de un
 * indicador agregado después en el backend— muestra el código técnico y conserva unidad y valor,
 * sin decisiones por indicador en el código.
 */
object MetricLabels {
    private val known: Map<String, Int> = mapOf(
        "training_metric.ftp_estimated" to R.string.metric_label_ftp_estimated,
        "training_metric.vo2max_estimated" to R.string.metric_label_vo2max_estimated,
        "training_metric.method.ftp_20_min_95_v1" to R.string.metric_method_ftp_20_min_95_v1,
        "training_metric.method.cooper_12_min_v1" to R.string.metric_method_cooper_12_min_v1,
        "training_metric.input.mean_power_watts" to R.string.metric_input_mean_power_watts,
        "training_metric.input.distance_meters" to R.string.metric_input_distance_meters,
        "estimated_not_diagnostic" to R.string.metric_disclaimer_estimated_not_diagnostic,
    )

    @StringRes
    fun resource(key: String): Int? = known[key]

    /** Texto localizado de [key], o [fallback] (el código técnico) si la clave no es conocida. */
    @Composable
    fun text(key: String, fallback: String): String = resource(key)?.let { stringResource(it) } ?: fallback
}
