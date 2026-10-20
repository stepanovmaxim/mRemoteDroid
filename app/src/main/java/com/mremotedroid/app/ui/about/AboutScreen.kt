package com.mremotedroid.app.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mremotedroid.app.R

const val SOURCE_URL = "https://github.com/stepanovmaxim/mRemoteDroid"
const val PRIVACY_URL = "https://github.com/stepanovmaxim/mRemoteDroid/blob/main/PRIVACY.md"

/** A third-party component shipped in the app and the license it is distributed under. */
private data class Component(
    val name: String,
    val copyright: String,
    val license: String,
    val asset: String,
    val note: Int? = null
)

private val components = listOf(
    Component(
        "FreeRDP (libfreerdp, libwinpr, libfreerdp-client)",
        "Copyright © FreeRDP contributors",
        "Apache License 2.0",
        "licenses/apache-2.0.txt"
    ),
    Component(
        "FreeRDP Android client (aFreeRDP)",
        "Copyright © 2013 Thincast Technologies GmbH and FreeRDP contributors",
        "Mozilla Public License 2.0",
        "licenses/mpl-2.0.txt",
        note = R.string.about_mpl_note
    ),
    Component(
        "OpenSSL",
        "Copyright © 1998-2024 The OpenSSL Project Authors",
        "Apache License 2.0",
        "licenses/apache-2.0.txt"
    ),
    Component(
        "cJSON",
        "Copyright © 2009-2017 Dave Gamble and cJSON contributors",
        "MIT License",
        "licenses/cjson-mit.txt"
    ),
    Component(
        "Android Jetpack (AndroidX, Jetpack Compose, Room, Navigation, Biometric, Material 3, Material Icons)",
        "Copyright © The Android Open Source Project",
        "Apache License 2.0",
        "licenses/apache-2.0.txt"
    ),
    Component(
        "Kotlin, kotlinx.coroutines",
        "Copyright © JetBrains s.r.o. and Kotlin Programming Language contributors",
        "Apache License 2.0",
        "licenses/apache-2.0.txt"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                // stacked: translated labels can be too long to share one row
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinkButton(stringResource(R.string.about_source)) { openUrl(context, SOURCE_URL) }
                    LinkButton(stringResource(R.string.about_privacy)) { openUrl(context, PRIVACY_URL) }
                }
            }
            item {
                Text(
                    stringResource(R.string.about_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.about_licenses),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    stringResource(R.string.about_tap_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(components) { LicenseItem(it) }
        }
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun LicenseItem(c: Component) {
    val context = LocalContext.current
    var expanded by rememberSaveable(c.name) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(c.name, style = MaterialTheme.typography.bodyLarge)
        Text(c.copyright, style = MaterialTheme.typography.bodySmall)
        Text(
            c.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        c.note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            val text = remember(c.asset) {
                runCatching { context.assets.open(c.asset).bufferedReader().use { it.readText() } }
                    .getOrDefault("")
            }
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // no browser installed: nothing sensible to do
    }
}
