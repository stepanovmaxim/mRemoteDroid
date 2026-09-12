package com.mremotedroid.app.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mremotedroid.app.data.model.Protocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditConnectionScreen(
    vm: EditViewModel,
    onBack: () -> Unit
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isNew) "Новое подключение" else "Изменить подключение") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        if (s.loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = s.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Protocol.entries.forEach { proto ->
                    FilterChip(
                        selected = s.protocol == proto,
                        onClick = {
                            vm.update {
                                val newPort = if (it.port == it.protocol.defaultPort.toString())
                                    proto.defaultPort.toString() else it.port
                                it.copy(protocol = proto, port = newPort)
                            }
                        },
                        label = { Text(proto.displayName) }
                    )
                }
            }
            if (s.protocol != Protocol.RDP) {
                Text(
                    "Запуск сейчас поддерживается только для RDP; настройки VNC/SSH сохраняются, но сеанс не открывается.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = s.hostname,
                onValueChange = { v -> vm.update { it.copy(hostname = v) } },
                label = { Text("Хост / IP") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.port,
                onValueChange = { v -> vm.update { it.copy(port = v.filter { c -> c.isDigit() }) } },
                label = { Text("Порт") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.username,
                onValueChange = { v -> vm.update { it.copy(username = v) } },
                label = { Text("Пользователь") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.domain,
                onValueChange = { v -> vm.update { it.copy(domain = v) } },
                label = { Text("Домен") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.password ?: "",
                onValueChange = { v -> vm.update { it.copy(password = v) } },
                label = {
                    Text(
                        if (s.hasStoredPassword && s.password == null)
                            "Пароль (сохранён — оставьте пустым)" else "Пароль"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            if (s.protocol == Protocol.RDP) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Полноэкранный режим")
                    Switch(
                        checked = s.fullscreen,
                        onCheckedChange = { v -> vm.update { it.copy(fullscreen = v) } }
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Консольная сессия (/admin)")
                    Switch(
                        checked = s.consoleSession,
                        onCheckedChange = { v -> vm.update { it.copy(consoleSession = v) } }
                    )
                }
                OutlinedTextField(
                    value = s.gatewayHostname,
                    onValueChange = { v -> vm.update { it.copy(gatewayHostname = v) } },
                    label = { Text("RD Gateway (необязательно)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = s.description,
                onValueChange = { v -> vm.update { it.copy(description = v) } },
                label = { Text("Описание") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.save(onBack) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }
}
