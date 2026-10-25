package com.lorran.virada.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lorran.virada.DjViewModel
import com.lorran.virada.ui.theme.C

@Composable
fun DjScreen(vm: DjViewModel, onRequestPermission: () -> Unit) {
    val tick = rememberFrameTick()
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
        vm.message = null
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        TopBar(vm, tick)
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Waveform(0, vm, tick, Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(2.dp))
            Waveform(1, vm, tick, Modifier.fillMaxWidth().height(40.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            DeckPanel(0, vm, tick, showStrip = !vm.showPads, modifier = Modifier.weight(1f).fillMaxHeight())
            Mixer(vm, tick, Modifier.width(176.dp).fillMaxHeight().padding(vertical = 4.dp))
            DeckPanel(1, vm, tick, showStrip = !vm.showPads, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        if (vm.showPads) PadsRow(vm, Modifier.fillMaxWidth().height(70.dp))
    }
    LibraryDialog(vm, onRequestPermission)
    SettingsDialog(vm)
    RecordingsDialog(vm)
    ConfirmLoadDialog(vm)
}
