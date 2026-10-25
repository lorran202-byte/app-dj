package com.lorran.virada.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorran.virada.DjViewModel
import com.lorran.virada.audio.Engine
import com.lorran.virada.ui.theme.C
import com.lorran.virada.ui.theme.Mono

@Composable
fun TopBar(vm: DjViewModel, tick: State<Long>) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("VIRADA", color = C.Text, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 3.sp)
        Spacer(Modifier.width(4.dp))
        ColorDot(C.Accent, 6.dp)
        Spacer(Modifier.width(16.dp))
        LevelMeter({ Engine.masterLevel }, tick, Modifier.width(120.dp).height(8.dp), vertical = false)
        Spacer(Modifier.weight(1f))
        val ad = vm.autoDj
        Row(
            Modifier
                .background(if (vm.studio.running) C.Accent else C.Surface2, RoundedCornerShape(16.dp))
                .clickable { vm.studio.show = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = if (vm.studio.running) C.OnColor else C.Accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (vm.studio.running) "IA ${(vm.studio.progress * 100).toInt()}%" else "IA",
                color = if (vm.studio.running) C.OnColor else C.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier
                .background(if (ad.enabled) C.Accent else C.Surface2, RoundedCornerShape(16.dp))
                .clickable { ad.toggle() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !ad.enabled -> "AUTO DJ"
                    ad.progress >= 0f -> "MIXANDO ${(ad.progress * 100).toInt()}%"
                    else -> "AUTO DJ ON"
                },
                color = if (ad.enabled) C.OnColor else C.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = { vm.showQueue = true }) {
            Box {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Fila", tint = if (vm.queue.isEmpty()) C.TextDim else C.Accent)
                if (vm.queue.isNotEmpty()) Text(
                    "${vm.queue.size}", color = C.OnColor, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).background(C.Accent, RoundedCornerShape(50)).padding(horizontal = 3.dp),
                )
            }
        }
        IconButton(onClick = { vm.showPads = !vm.showPads }) {
            Icon(Icons.Filled.Apps, "Pads", tint = if (vm.showPads) C.Accent else C.TextDim)
        }
        Row(
            Modifier
                .background(if (vm.recording) C.Rec else C.Surface2, RoundedCornerShape(16.dp))
                .clickable { vm.toggleRecord() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (vm.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null,
                tint = if (vm.recording) C.Text else C.Rec, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (vm.recording) fmtTime(vm.recSeconds.toDouble()) else "GRAVAR",
                color = C.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = if (vm.recording) Mono else null,
            )
        }
        IconButton(onClick = { vm.showRecordings = true }) {
            Icon(Icons.Filled.LibraryMusic, "Gravações", tint = C.TextDim)
        }
        IconButton(onClick = { vm.showSettings = true }) {
            Icon(Icons.Filled.Settings, "Ajustes", tint = C.TextDim)
        }
    }
}

@Composable
fun Mixer(vm: DjViewModel, tick: State<Long>, modifier: Modifier = Modifier) {
    Column(
        modifier.background(C.Surface, RoundedCornerShape(8.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val knob = ((maxHeight - 40.dp) / 4 - 10.dp).coerceIn(22.dp, 42.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChannelStrip(0, vm, tick, knob, mirrored = false)
                ChannelStrip(1, vm, tick, knob, mirrored = true)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("A", color = C.DeckA, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            HFader(
                (vm.crossfader + 1f) / 2f, { vm.moveCrossfader(it * 2f - 1f) },
                Modifier.weight(1f).height(34.dp),
            )
            Text("B", color = C.DeckB, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelStrip(i: Int, vm: DjViewModel, tick: State<Long>, knob: androidx.compose.ui.unit.Dp, mirrored: Boolean) {
    val ui = vm.decks[i]
    val color = C.deck(i)
    val knobs: @Composable () -> Unit = {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Knob(ui.eqHigh, { vm.setEq(i, 0, it) }, "AGUDO", color, knob)
            Knob(ui.eqMid, { vm.setEq(i, 1, it) }, "MÉDIO", color, knob)
            Knob(ui.eqLow, { vm.setEq(i, 2, it) }, "GRAVE", color, knob)
            Knob(ui.filter, { vm.setFilter(i, it) }, "FILTRO", C.Accent, knob)
        }
    }
    val fader: @Composable () -> Unit = {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(28.dp, 22.dp)
                    .background(
                        if (vm.splitCue && vm.cueDeck == i) color else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { vm.chooseCueDeck(i) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Headphones, "Pré-escuta",
                    tint = if (vm.splitCue && vm.cueDeck == i) C.OnColor else C.TextDim,
                    modifier = Modifier.size(14.dp),
                )
            }
            Row(Modifier.weight(1f)) {
                if (!mirrored) LevelMeter({ Engine.decks[i].level }, tick, Modifier.width(4.dp).fillMaxHeight().padding(vertical = 12.dp))
                VFader(ui.volume, { vm.setVolume(i, it) }, color, Modifier.width(30.dp).fillMaxHeight(), default = 0.85f)
                if (mirrored) LevelMeter({ Engine.decks[i].level }, tick, Modifier.width(4.dp).fillMaxHeight().padding(vertical = 12.dp))
            }
        }
    }
    Row(Modifier.fillMaxHeight()) {
        if (!mirrored) { knobs(); Spacer(Modifier.width(4.dp)); fader() }
        else { fader(); Spacer(Modifier.width(4.dp)); knobs() }
    }
}

@Composable
fun PadsRow(vm: DjViewModel, modifier: Modifier = Modifier) {
    val colors = listOf(C.Accent, C.DeckA, C.DeckB, Color(0xFF9D7BFF))
    Row(modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PADS", color = C.TextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Knob(vm.padVolume, { v -> vm.updateSettings { padVolume = v } }, "VOLUME", C.Accent, 30.dp, default = 0.8f, bipolar = false)
        }
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Engine.sampler.names.forEachIndexed { k, name ->
                PadButton(
                    name.uppercase(), colors[k % colors.size], Modifier.weight(1f).fillMaxHeight(),
                    fontSize = 10.sp,
                    onPress = { if (it) vm.pad(k) },
                )
            }
        }
    }
}
