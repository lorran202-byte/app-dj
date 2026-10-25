package com.lorran.virada.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lorran.virada.DjViewModel
import com.lorran.virada.Sort
import com.lorran.virada.ui.theme.C
import com.lorran.virada.ui.theme.Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryDialog(vm: DjViewModel, onRequestPermission: () -> Unit) {
    val target = vm.libraryFor
    if (target < 0) return
    val color = C.deck(target)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.loadDocument(target, uri)
    }
    Dialog(onDismissRequest = { vm.libraryFor = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.libraryFor = -1 }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = C.Text)
                }
                Text("Deck", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                for (d in 0..1) {
                    FilterChip(
                        selected = target == d,
                        onClick = { vm.libraryFor = d },
                        label = { Text(if (d == 0) "A" else "B", fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = C.deck(d), selectedLabelColor = C.OnColor, labelColor = C.deck(d),
                        ),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = vm.search, onValueChange = { vm.search = it },
                    placeholder = { Text("Buscar música ou artista", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, cursorColor = color),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.showFolders = true }) {
                    Icon(Icons.Filled.Folder, null, tint = if (vm.folders.isEmpty()) C.TextDim else C.Accent)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (vm.folders.isEmpty()) "Pastas: todas" else "Pastas: ${vm.folders.size}",
                        color = if (vm.folders.isEmpty()) C.TextDim else C.Accent,
                    )
                }
                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Filled.FolderOpen, null, tint = C.Accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Abrir arquivo", color = C.Accent)
                }
            }
            Row(
                Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ordenar:", color = C.TextDim, fontSize = 12.sp)
                listOf(Sort.TITLE to "Título", Sort.ARTIST to "Artista", Sort.BPM to "BPM").forEach { (s, t) ->
                    FilterChip(
                        selected = vm.sort == s, onClick = { vm.sort = s }, label = { Text(t) },
                        modifier = Modifier.padding(start = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.Surface2, selectedLabelColor = C.Accent),
                    )
                }
            }
            LocalList(vm, target, color, onRequestPermission)
        }
    }
}

@Composable
private fun SongRow(title: String, artist: String, bpm: Double, durMs: Long, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = C.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (bpm > 0) "%.0f BPM".format(bpm) else "", color = color, fontSize = 12.sp, fontFamily = Mono)
        Spacer(Modifier.width(16.dp))
        Text(fmtTime(durMs / 1000.0), color = C.TextDim, fontSize = 12.sp, fontFamily = Mono)
    }
}

@Composable
private fun LocalList(vm: DjViewModel, target: Int, color: Color, onRequestPermission: () -> Unit) {
    when {
        !vm.hasPermission -> Column(
            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Para listar as músicas do celular, libere o acesso a áudio.", color = C.TextDim)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermission) { Text("Permitir acesso às músicas") }
            Spacer(Modifier.height(8.dp))
            Text("Ou use \"Abrir arquivo\" lá em cima.", color = C.TextDim, fontSize = 12.sp)
        }
        !vm.songsLoaded -> BigMessage("Procurando músicas…")
        else -> {
            val list = vm.visibleSongs()
            if (list.isEmpty()) {
                BigMessage(
                    when {
                        vm.search.isNotBlank() -> "Nada encontrado para \"${vm.search}\""
                        vm.folders.isNotEmpty() -> "Nenhuma música nas pastas escolhidas"
                        else -> "Nenhuma música encontrada no celular"
                    }
                )
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.key }) { s ->
                    SongRow(s.title, s.artist, vm.lib.bpmOf(s.key), s.durationMs, color) { vm.requestLoad(target, s) }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: DjViewModel) {
    if (!vm.showSettings) return
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        confirmButton = { TextButton(onClick = { vm.showSettings = false }) { Text("Fechar") } },
        title = { Text("Ajustes") },
        containerColor = C.Surface,
        text = {
            Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Label("Curva do crossfader")
                Row {
                    Choice("Suave", !vm.sharpCurve) { vm.updateSettings { sharpCurve = false } }
                    Choice("Corte (scratch)", vm.sharpCurve) { vm.updateSettings { sharpCurve = true } }
                }
                Label("Biblioteca")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.folders.isEmpty()) "Usando todas as pastas do celular"
                        else "Usando ${vm.folders.size} pasta(s) escolhida(s)",
                        color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.showFolders = true }) { Text("Escolher pastas") }
                }
                Label("Faixa do pitch")
                Row {
                    listOf(0.08f to "±8%", 0.16f to "±16%", 0.5f to "±50%").forEach { (r, t) ->
                        Choice(t, vm.pitchRange == r) { vm.updateSettings { pitchRange = r } }
                    }
                }
                SwitchRow("Volume automático", "Deixa as músicas com volume parecido", vm.autoGain) {
                    vm.updateSettings { autoGain = it }
                }
                SwitchRow(
                    "Pré-escuta dividida",
                    "Com um cabo divisor: lado esquerdo = fone (deck do ícone de fone), lado direito = caixa de som",
                    vm.splitCue,
                ) { vm.updateSettings { splitCue = it } }
                Label("Volume geral")
                Slider(
                    vm.master, { v -> vm.updateSettings { master = v } },
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent),
                )
                Label("Volume dos pads")
                Slider(
                    vm.padVolume, { v -> vm.updateSettings { padVolume = v } },
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dicas: toque duas vezes num botão giratório ou fader para voltar ao meio. " +
                        "No prato, o centro faz scratch e a borda acelera/freia de leve. " +
                        "KEY aceso (embaixo do pitch) = mudar a velocidade sem mudar o tom.",
                    color = C.TextDim, fontSize = 12.sp,
                )
            }
        },
    )
}

@Composable
private fun Label(t: String) {
    Text(t, color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Choice(t: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(t) },
        modifier = Modifier.padding(end = 6.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.Accent, selectedLabelColor = C.OnColor),
    )
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 14.sp)
            Text(sub, color = C.TextDim, fontSize = 11.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.OnColor))
    }
}

@Composable
fun RecordingsDialog(vm: DjViewModel) {
    if (!vm.showRecordings) return
    val ctx = LocalContext.current
    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
    AlertDialog(
        onDismissRequest = { vm.showRecordings = false },
        confirmButton = { TextButton(onClick = { vm.showRecordings = false }) { Text("Fechar") } },
        title = { Text("Gravações") },
        containerColor = C.Surface,
        text = {
            Column(Modifier.widthIn(max = 560.dp)) {
                Text("Ficam em Música/Virada DJ no celular.", color = C.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (vm.recList.isEmpty()) {
                    Text("Nenhuma gravação ainda. Toque em GRAVAR lá em cima durante a mixagem.", color = C.TextDim)
                } else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(vm.recList) { r ->
                        Row(
                            Modifier.fillMaxWidth().background(C.Surface2, RoundedCornerShape(6.dp)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${fmtTime(r.seconds.toDouble())} · ${fmt.format(Date(r.date))}", color = C.TextDim, fontSize = 11.sp)
                            }
                            if (r.uri.startsWith("content://")) {
                                IconButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(send, "Compartilhar mixagem"))
                                }) { Icon(Icons.Filled.Share, "Compartilhar", tint = C.Accent) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
    )
}

@Composable
fun ConfirmLoadDialog(vm: DjViewModel) {
    val c = vm.confirmLoad ?: return
    AlertDialog(
        onDismissRequest = { vm.confirmLoad = null },
        title = { Text("O deck ${if (c.first == 0) "A" else "B"} está tocando") },
        text = { Text("Trocar a música agora vai parar o som desse deck.") },
        confirmButton = { TextButton(onClick = { vm.load(c.first, c.second) }) { Text("Trocar") } },
        dismissButton = { TextButton(onClick = { vm.confirmLoad = null }) { Text("Cancelar") } },
        containerColor = C.Surface,
    )
}

@Composable
fun FoldersDialog(vm: DjViewModel, onRequestPermission: () -> Unit) {
    if (!vm.showFolders) return
    val list = vm.folderList()
    val sel = vm.folders
    AlertDialog(
        onDismissRequest = { vm.showFolders = false },
        confirmButton = { TextButton(onClick = { vm.showFolders = false }) { Text("Pronto") } },
        dismissButton = {
            if (sel.isNotEmpty()) TextButton(onClick = { vm.setFolders(emptySet()) }) { Text("Usar todas") }
        },
        title = { Text("Pastas da biblioteca") },
        containerColor = C.Surface,
        text = {
            Column(Modifier.widthIn(max = 560.dp)) {
                Text(
                    "Marque as pastas que o app deve usar. Marcar uma pasta inclui as subpastas. " +
                        "Sem nenhuma marcada, o app usa todas.",
                    color = C.TextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    !vm.hasPermission -> {
                        Text("Libere o acesso às músicas para ver as pastas.", color = C.TextDim)
                        TextButton(onClick = onRequestPermission) { Text("Permitir acesso") }
                    }
                    !vm.songsLoaded -> Text("Procurando músicas…", color = C.TextDim)
                    list.isEmpty() -> Text("Nenhuma pasta com música encontrada.", color = C.TextDim)
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(list, key = { it.first }) { (path, count) ->
                            val parent = sel.firstOrNull { path.startsWith("$it/") }
                            val checked = path in sel || parent != null
                            val depth = if (path.isEmpty()) 0 else path.count { it == '/' }
                            val name = if (path.isEmpty()) "(outras)" else path.substringAfterLast('/')
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = parent == null) { vm.toggleFolder(path) }
                                    .padding(start = (depth * 16).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked, enabled = parent == null,
                                    onCheckedChange = { vm.toggleFolder(path) },
                                    colors = CheckboxDefaults.colors(checkedColor = C.Accent, checkmarkColor = C.OnColor),
                                )
                                Icon(Icons.Filled.Folder, null, tint = if (checked) C.Accent else C.TextDim, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (parent != null) Text("incluída em ${parent.substringAfterLast('/')}", color = C.TextDim, fontSize = 10.sp)
                                }
                                Text("$count", color = C.TextDim, fontSize = 12.sp, fontFamily = Mono)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
        },
    )
}
