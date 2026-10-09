package com.rocketgs.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rocketgs.GroundStationViewModel
import com.rocketgs.source.ReplaySource
import com.rocketgs.source.SimSource
import com.rocketgs.source.UsbSource

@Composable
fun SetupScreen(vm: GroundStationViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pickLog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.startReplay(uri); onBack() }
    }
    val src = vm.source
    @Suppress("UNUSED_VARIABLE") val tick = vm.clock   // redraw 4x/s: replay progress, speed, pause

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { Btn("< BACK", onBack) }

        Panel("DATA SOURCE", right = src?.kind ?: "none") {
            T(vm.status, if (vm.sourceOk) C.fg else C.amber, 14.sp, maxLines = 3)
            ButtonRow {
                Btn("USB RECEIVER", { vm.startUsb(); onBack() }, selected = src is UsbSource)
                Btn("SIMULATOR", { vm.startSim(); onBack() }, selected = src is SimSource)
            }
            ButtonRow {
                Btn("REPLAY LOG...", { pickLog.launch(arrayOf("*/*")) }, selected = src is ReplaySource)
                Btn("STOP", { vm.stop() }, enabled = src != null, color = C.amber)
            }
            if (src is ReplaySource) {
                T("replay speed", C.dim, 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in listOf(1.0, 2.0, 5.0, 10.0, 50.0)) Btn("${x.toInt()}x", { vm.setReplaySpeed(x) }, selected = src.speed == x)
                    Btn(if (src.paused) "RESUME" else "PAUSE", { vm.toggleReplayPause() })
                }
                T("progress ${(src.progress * 100).toInt()} %", C.dim, 13.sp)
            }
            T("USB: plug the receiver Uno into the phone with a USB-OTG adapter and allow access. " +
              "It reconnects by itself after unplugging.", C.dim, 12.sp, maxLines = 4, modifier = Modifier.padding(top = 8.dp))
        }

        Panel("SIMULATOR") {
            T("radio loss %", C.dim, 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(0.0, 3.0, 10.0, 25.0)) Btn("${p.toInt()}", { vm.updateSim(vm.sim.copy(lossPct = p)) }, selected = vm.sim.lossPct == p)
            }
            T("corrupted blocks %", C.dim, 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(0.0, 1.0, 5.0, 15.0)) Btn("${p.toInt()}", { vm.updateSim(vm.sim.copy(garbagePct = p)) }, selected = vm.sim.garbagePct == p)
            }
            T("Applies the next time the simulator starts.", C.dim, 12.sp)
        }

        Panel("ATTITUDE CONVENTIONS") {
            val a = vm.attitude
            T("body axis along the nose", C.dim, 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("+X", "-X", "+Y", "-Y", "+Z", "-Z").forEachIndexed { i, s ->
                    Btn(s, { vm.updateAttitude(a.copy(noseAxis = i)) }, selected = a.noseAxis == i)
                }
            }
            ButtonRow {
                Btn("FLIP ROLL", { vm.updateAttitude(a.copy(flipRoll = !a.flipRoll)) }, selected = a.flipRoll)
                Btn("FLIP PITCH", { vm.updateAttitude(a.copy(flipPitch = !a.flipPitch)) }, selected = a.flipPitch)
            }
            ButtonRow { Btn("FLIP YAW", { vm.updateAttitude(a.copy(flipYaw = !a.flipYaw)) }, selected = a.flipYaw) }
            T("Tilt test: stand the board nose-up, press SET UPRIGHT on the attitude scope, then tilt it " +
              "toward north: the marker must move up. Roll it: the roll triangle must turn the same way. " +
              "If a motion is mirrored, use FLIP. Angles: ZYX Euler in North-East-Down.",
              C.dim, 12.sp, maxLines = 8, modifier = Modifier.padding(top = 8.dp))
        }

        Panel("SCREEN") {
            ButtonRow { Btn(if (vm.keepScreenOn) "KEEP SCREEN ON: YES" else "KEEP SCREEN ON: NO", { vm.updateKeepScreenOn(!vm.keepScreenOn) }) }
        }

        Panel("LOGS") {
            T("Every USB session is saved, before parsing, to:", C.dim, 12.sp)
            T(vm.logDir.absolutePath, C.fg, 12.sp, maxLines = 3)
            T("Logs replay here and in the desktop app.", C.dim, 12.sp)
            ButtonRow {
                Btn("SHARE LATEST LOG", {
                    vm.latestLogFile()?.let { f ->
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share log"))
                    }
                }, enabled = vm.latestLogFile() != null)
                Btn("CLEAR DATA", { vm.clearData() }, color = C.amber)
            }
        }
    }
}
