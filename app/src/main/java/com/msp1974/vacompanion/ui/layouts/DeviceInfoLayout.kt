package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun DeviceInfoLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deviceInfo = remember { viewModel.deviceInfo }
    
    // Summary of Audio Capabilities
    val audioSummary = buildString {
        if (deviceInfo.features.audio.autoGainControl) append("AGC ")
        if (deviceInfo.features.audio.noiseSuppression) append("NS ")
        if (deviceInfo.features.audio.acousticEchoCancellation) append("AEC")
    }.trim()

    MenuLayout(
        title = "Device Info",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { SectionHeader("Software") }
            item { 
                InfoListItem(
                    icon = Icons.Default.Smartphone,
                    label = "Device",
                    value = deviceInfo.hardware.deviceName
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Info,
                    label = "App Version",
                    value = deviceInfo.software.appVersion
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Android,
                    label = "Android Version",
                    value = deviceInfo.software.release
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Code,
                    label = "WebView Version",
                    value = deviceInfo.software.webViewVersion
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Hardware Capabilities") }
            
            item { 
                InfoListItem(
                    icon = Icons.Default.BatteryFull,
                    label = "Battery",
                    value = if (deviceInfo.hardware.hasBattery) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasBattery) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.CameraAlt,
                    label = "Front Camera",
                    value = if (deviceInfo.hardware.hasFrontCamera) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasFrontCamera) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.SettingsSuggest,
                    label = "DND Support",
                    value = if (deviceInfo.features.supportsDND) "Yes" else "No",
                    valueColor = if (deviceInfo.features.supportsDND) CustomColours.GREEN else CustomColours.AMBER
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Sensors") }

            item { 
                InfoListItem(
                    icon = Icons.Default.Sensors,
                    label = "Accelerometer",
                    value = if (deviceInfo.hardware.hasAccelerometer) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasAccelerometer) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.LightMode,
                    label = "Light Sensor",
                    value = if (deviceInfo.hardware.hasLightSensor) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasLightSensor) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Radar,
                    label = "Proximity Sensor",
                    value = if (deviceInfo.hardware.hasProximitySensor) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasProximitySensor) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            if (deviceInfo.hardware.hasProximitySensor) {
                item { 
                    InfoListItem(
                        icon = Icons.AutoMirrored.Filled.Label,
                        label = "Proximity Type",
                        value = deviceInfo.hardware.proximitySensorType.replaceFirstChar { it.uppercase() }
                    )
                }
            }

            item { 
                InfoListItem(
                    icon = Icons.Default.SettingsInputComponent,
                    label = "Available Sensors",
                    value = deviceInfo.hardware.sensors.joinToString(separator = ", ") { it.name.lowercase().replaceFirstChar { it.uppercase() } }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Audio") }

            item { 
                InfoListItem(
                    icon = Icons.Default.GraphicEq,
                    label = "Audio Enhancements",
                    value = audioSummary.ifEmpty { "None supported" }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun InfoListItem(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    ListItem(
        headlineContent = { 
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
