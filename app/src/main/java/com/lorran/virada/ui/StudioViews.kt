package com.lorran.virada.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lorran.virada.DjViewModel
import com.lorran.virada.data.Song
import com.lorran.virada.studio.RemixStyle
import com.lorran.virada.ui.theme.C
import com.lorran.virada.ui.theme.Mono

@Composable
fun StudioDialog(vm: DjViewModel) {
    val st = vm.studio
    if (!st.show) return
    AlertDialog(
        onDismissRequest = { st.show = false },
        confirmButton = { TextButton(onClick = { st.show = false }) { Text("Fechar") } },
        title = { Text("Estúdio IA") },
        containerColor = C.Surface,
        text = {
            Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                Row {
                    listOf("Mashup", "Remix").forEachIndexed { k, t ->
                        FilterChip(
                            selected = st.mode == k, onClick = { if (!st.running) st.mode = k }, label = { Text(t) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.Accent, selectedLabelColor = C.OnColor),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (st.mode == 0) {
                    Text(
                        "A IA separa a voz de uma música e o instrumental de outra, acerta BPM e tom e junta as duas.",
                        color = C.TextDim, fontSize = 12.sp,
                    )
                    SongSlot("Voz de", st.vocal, vm, enabled = !st.running) { st.picking = 0 }
                    SongSlot("Instrumental de", st.inst, vm, enabled = !st.running) { st.picking = 1 }
                    if (st.vocal != null && st.vocal?.key == st.inst?.key) {
                        Text("Escolha duas músicas diferentes.", color = C.Rec, fontSize = 12.sp)
                    }
                } else {
                    Text(
                        "A IA tira a bateria original e coloca uma batida nova no estilo escolhido, sem mudar o tom, " +
                            "com intro, subida, drop e final para DJ.",
                        color = C.TextDim, fontSize = 12.sp,
                    )
                    SongSlot("Música", st.remixSong, vm, enabled = !st.running) { st.picking = 2 }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Text("Estilo:", color = C.TextDim, fontSize = 12.sp)
                        RemixStyle.entries.forEach { s ->
                            FilterChip(
                                selected = st.style == s, onClick = { if (!st.running) st.pickStyle(s) }, label = { Text(s.label) },
                                modifier = Modifier.padding(start = 6.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.Accent, selectedLabelColor = C.OnColor),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BPM: ${st.bpm}", color = C.Text, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.width(80.dp))
                        Slider(
                            value = st.bpm.toFloat(), onValueChange = { st.bpm = it.toInt() },
                            valueRange = 80f..160f, enabled = !st.running, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent),
                        )
                    }
                    st.remixSong?.let { s ->
                        val b = vm.lib.bpmOf(s.key)
                        if (b > 0) Text("Original: %.0f BPM".format(b), color = C.TextDim, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (st.running) {
                    Text(st.step, color = C.Text, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp), color = C.Accent, trackColor = C.Line,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(st.progress * 100).toInt()}% · pode fechar esta janela e continuar tocando",
                            color = C.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { st.cancel() }) { Text("Cancelar", color = C.Rec) }
                    }
                } else {
                    Button(
                        onClick = { st.start() }, enabled = st.canStart(),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (st.mode == 0) "Criar mashup" else "Criar remix", fontWeight = FontWeight.Bold) }
                    Text(
                        "Leva alguns minutos (a separação por IA roda no celular, sem internet). Músicas já separadas vão mais rápido.",
                        color = C.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (st.step.isNotEmpty() && st.result == null) Text(st.step, color = C.Rec, fontSize = 12.sp)
                }
                st.result?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().background(C.Surface2, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("Pronto!", color = C.Accent, fontWeight = FontWeight.Bold)
                        Text(r.title, color = C.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (st.resultInfo.isNotEmpty()) Text(st.resultInfo, color = C.TextDim, fontSize = 11.sp)
                        Text("Salvo em Música/Virada DJ/IA", color = C.TextDim, fontSize = 11.sp)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { st.show = false; vm.requestLoad(0, r) }) { Text("Deck A", color = C.DeckA) }
                            OutlinedButton(onClick = { st.show = false; vm.requestLoad(1, r) }) { Text("Deck B", color = C.DeckB) }
                            OutlinedButton(onClick = { vm.addToQueue(r) }) { Text("Fila", color = C.Accent) }
                        }
                    }
                }
            }
        },
    )
    if (st.picking >= 0) SongPicker(vm, onPick = { st.pick(it) }, onClose = { st.picking = -1 })
}

@Composable
private fun SongSlot(label: String, song: Song?, vm: DjViewModel, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(C.Surface2, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.TextDim, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "Toque para escolher", color = if (song != null) C.Text else C.Accent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song != null) {
                val b = vm.lib.bpmOf(song.key)
                Text(song.artist + if (b > 0) " · %.0f BPM".format(b) else "", color = C.TextDim, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SongPicker(vm: DjViewModel, onPick: (Song) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = C.Text) }
                Text("Escolher música", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Buscar", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
            }
            val list = vm.visibleSongs().filter {
                q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true)
            }
            if (list.isEmpty()) BigMessage("Nenhuma música")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.key }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = C.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val b = vm.lib.bpmOf(s.key)
                        if (b > 0) Text("%.0f BPM".format(b), color = C.Accent, fontSize = 12.sp, fontFamily = Mono)
                    }
                }
            }
        }
    }
}
