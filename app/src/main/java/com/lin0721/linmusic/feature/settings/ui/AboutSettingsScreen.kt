package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.BuildConfig
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.update.UpdateManager
import org.koin.compose.koinInject

@Composable
fun AboutSettingsView(viewModel: SettingsViewModel) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(MimikyuSpacing.md),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayInset.current + 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize()
                )
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize()
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(appVersionLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            val updateManager: UpdateManager = koinInject()
            val autoCheckUpdateEnabled by viewModel.autoCheckUpdateEnabled.collectAsStateWithLifecycle()

            SettingsGroupCard(SettingsSubMenu.ABOUT.sectionTitles[0]) {
                SettingsRow(
                    title = "Check for Updates",
                    subtitle = "Get the latest release from GitHub (szzsuke/Mimikyu)",
                    onClick = { updateManager.checkForUpdate(manual = true) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsRow(
                    title = "Announcement Example",
                    subtitle = "Preview how Firestore announcements look to users",
                    onClick = { updateManager.showAnnouncementPreview() }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Check Updates Automatically",
                    subtitle = "Check for updates in the background on startup",
                    checked = autoCheckUpdateEnabled,
                    onCheckedChange = { viewModel.updateAutoCheckUpdateEnabled(it) }
                )
            }
        }

        item {
            SettingsGroupCard(SettingsSubMenu.ABOUT.sectionTitles[1]) {
                Text(
                    text = "Mimikyu is a modern music player built with Jetpack Compose.\n\nReleased under open source license.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text("Open Source License (MIT LICENSE)", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "MIT License\n\nCopyright (c) 2026 Mimikyu Contributors\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Start,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

private fun appVersionLabel(): String {
    val name = BuildConfig.VERSION_NAME
    val isPrerelease = name.contains("beta", ignoreCase = true) || name.contains("rc", ignoreCase = true)
    return if (isPrerelease) "Version $name (Beta)" else "Version $name"
}
