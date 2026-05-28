package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.ui.theme.CustomColours
import com.msp1974.vacompanion.satellite.AudioRouteOption


@SuppressLint("DefaultLocale")
@Composable
fun DiagnosticBar(
    diagnosticInfo: DiagnosticInfo,
    onEnrollSpeakerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .zIndex(2f)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                // Prevent propagation of click
            }
    ) {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoGauge(
                indicatorValue = diagnosticInfo.audioLevel,
                maxIndicatorValue = 100,
                smallText = "Mic Level",
                foregroundIndicatorColor = CustomColours.GREEN,
                disabledText = "Muted",
                disabled = diagnosticInfo.muted
            )
            InfoGauge(
                indicatorValue = diagnosticInfo.detectionLevel,
                maxIndicatorValue = 10,
                decimalPlaces = 1,
                smallText = "Detection",
                foregroundIndicatorColor = if (diagnosticInfo.detectionLevel >= diagnosticInfo.detectionThreshold) CustomColours.GREEN else CustomColours.AMBER,
                disabledText = "Disabled",
                disabled = diagnosticInfo.wakeWord == "none"

            )
            Column() {
                if (onEnrollSpeakerClick != null) {
                    AssistChip(
                        onClick = { onEnrollSpeakerClick() },
                        label = { Text("Enroll Speaker") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (diagnosticInfo.engine != "") diagnosticInfo.engine else "Disabled") },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Detecting" ) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (diagnosticInfo.mode == AudioRouteOption.DETECT) CustomColours.GREEN else Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer

                    )
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Streaming") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (diagnosticInfo.mode == AudioRouteOption.STREAM) CustomColours.GREEN else Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer

                    )
                )
            }
        }
    }

}

@Preview(apiLevel = 35)
@Composable
fun DiagnosticBarPreview() {
    DiagnosticBar(
        modifier = Modifier.background(Color.White),
        diagnosticInfo = DiagnosticInfo(
            audioLevel = 50f,
            detectionLevel = 8.1f,
            detectionThreshold = 5f,
            vadDetection = true
        ),
        onEnrollSpeakerClick = {}
    )

}
