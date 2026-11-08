package com.milk.audiobook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var amoled by remember { mutableStateOf(Store.amoled(this@MainActivity)) }
            val scheme = if (amoled) {
                darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceTint = Color.Black,
                    surfaceContainer = Color.Black,
                    surfaceContainerHigh = Color(0xFF121212),
                    surfaceContainerHighest = Color(0xFF1A1A1A)
                )
            } else {
                darkColorScheme()
            }
            SideEffect {
                val bar = if (amoled) Color.Black else Color(0xFF1C1B1F)
                window.statusBarColor = bar.toArgb()
                window.navigationBarColor = bar.toArgb()
            }
            MaterialTheme(colorScheme = scheme) {
                App(amoled = amoled, onAmoled = {
                    amoled = it
                    Store.setAmoled(this@MainActivity, it)
                })
            }
        }
    }
}

private fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun playAudioOnly(holder: PlayerHolder, list: List<Book>, start: Int) {
    holder.controller?.let {
        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
    }
    holder.playQueue(list, start)
}

private sealed interface Dlg {
    class Rename(val b: Book) : Dlg
    class Delete(val b: Book) : Dlg
    class AddTo(val b: Book) : Dlg
    class Pick(val b: Book, val move: Boolean) : Dlg
    class NewPlaylistFor(val b: Book) : Dlg
    class DeletePlaylist(val name: String) : Dlg
    object NewPlaylist : Dlg
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(amoled: Boolean, onAmoled: (Boolean) -> Unit, vm: LibraryViewModel = viewModel()) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasAccess(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var openList by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = hasAccess(ctx)
        vm.refresh()
    }

    val legacyLauncher = rememberLauncherForActivityResult(RequestPermission()) { access = it }
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(access) { if (access) vm.scanAll() }
    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.message = null
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            vm.refresh()
        }
    }

    val holder = remember { PlayerHolder(ctx) }
    DisposableEffect(Unit) { onDispose { holder.release() } }

    Dialogs(dlg, { dlg = it }, vm, holder)

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("AVI") },
                actions = {
                    IconButton(onClick = { if (access) vm.scanAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reescanear")
                    }
                }
            )
        },
        bottomBar = {
            val c = holder.controller
            if (holder.active && c != null) PlayerBar(c)
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!access) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Precisa de acesso a todos os arquivos para varrer o armazenamento.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        } else legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }) { Text("Conceder acesso") }
                }
                return@Column
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Áudios") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Vídeos") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Config") })
            }
            when (tab) {
                0 -> LibraryTab(vm, holder) { dlg = it }
                1 -> PlaylistsTab(vm, holder, openList, { openList = it }) { dlg = it }
                2 -> VideosTab(vm, holder) { dlg = it }
                else -> SettingsTab(amoled, onAmoled)
            }
        }
    }
}

@Composable
private fun SettingsTab(amoled: Boolean, onAmoled: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tema AMOLED", style = MaterialTheme.typography.bodyLarge)
                Text("Fundo preto puro (#000000)", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = amoled, onCheckedChange = onAmoled)
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("AVI - Audiobook + Video Integrated", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Player de vídeo inspirado no One Player (Apache 2.0, Zakir Sheikh).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun VideosTab(vm: LibraryViewModel, holder: PlayerHolder, show: (Dlg) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val tick = vm.tick

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar vídeos") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        if (vm.scanningV) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${vm.videos.size} encontrados...", Modifier.padding(12.dp))
        } else {
            Text("${vm.videos.size} vídeos", Modifier.padding(12.dp))
        }

        val shown = vm.videos.filter {
            query.isBlank() || it.title.contains(query, true) || it.folder.contains(query, true)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, b -> b.path }) { i, b ->
                val open: () -> Unit = {
                    holder.controller?.pause()
                    ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("path", b.path))
                }
                val actions: List<Pair<String, () -> Unit>> = listOf(
                    "Reproduzir" to open,
                    "Renomear" to { show(Dlg.Rename(b)) },
                    "Mover" to { show(Dlg.Pick(b, true)) },
                    "Copiar" to { show(Dlg.Pick(b, false)) },
                    "Excluir" to { show(Dlg.Delete(b)) }
                )
                BookRow(
                    b, tick, actions,
                    extra = {
                        IconButton(onClick = { playAudioOnly(holder, shown, i) }) {
                            Icon(Icons.Filled.Headphones, contentDescription = "Tocar como áudio")
                        }
                    }
                ) { open() }
            }
        }
    }
}

@Composable
private fun LibraryTab(vm: LibraryViewModel, holder: PlayerHolder, show: (Dlg) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val tick = vm.tick
    val last = remember(tick) { Store.last(ctx)?.let { vm.bookOf(it) } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = vm.onlyLong, onCheckedChange = { vm.onlyLong = it; vm.scan() })
            Spacer(Modifier.width(8.dp))
            Text("Ocultar áudios curtos (< 5 MB)", Modifier.weight(1f))
        }
        if (!holder.active && last != null) {
            OutlinedButton(
                onClick = { holder.play(last) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Text("Continuar: ${last.title}")
            }
        }
        if (vm.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${vm.books.size} encontrados...", Modifier.padding(12.dp))
        } else {
            Text("${vm.books.size} áudios", Modifier.padding(12.dp))
        }

        val shown = vm.books.filter {
            query.isBlank() || it.title.contains(query, true) || it.folder.contains(query, true)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.path }) { b ->
                val actions: List<Pair<String, () -> Unit>> = listOf(
                    "Tocar" to { holder.play(b) },
                    "Adicionar à playlist" to { show(Dlg.AddTo(b)) },
                    "Renomear" to { show(Dlg.Rename(b)) },
                    "Mover" to { show(Dlg.Pick(b, true)) },
                    "Copiar" to { show(Dlg.Pick(b, false)) },
                    "Excluir" to { show(Dlg.Delete(b)) }
                )
                BookRow(b, tick, actions) { holder.play(b) }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    vm: LibraryViewModel,
    holder: PlayerHolder,
    open: String?,
    setOpen: (String?) -> Unit,
    show: (Dlg) -> Unit
) {
    val tick = vm.tick
    val pl = vm.playlists.firstOrNull { it.name == open }
    LaunchedEffect(open, pl) { if (open != null && pl == null) setOpen(null) }

    if (pl == null) {
        Column(Modifier.fillMaxSize()) {
            Button(
                onClick = { show(Dlg.NewPlaylist) },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Text("Nova playlist") }
            if (vm.playlists.isEmpty()) {
                Text(
                    "Nenhuma playlist. Crie uma e adicione áudios pelo menu ⋮ da biblioteca.",
                    Modifier.padding(16.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.playlists, key = { it.name }) { p ->
                    Column(
                        Modifier.fillMaxWidth().clickable { setOpen(p.name) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${p.items.size} áudios", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
        return
    }

    val list = remember(pl.items, vm.books.size, tick) { pl.items.mapNotNull { vm.bookOf(it) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { setOpen(null) }) { Text("< Voltar") }
            Text(
                pl.name, Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { holder.playQueue(list, 0) }, enabled = list.isNotEmpty()) {
                Text("Tocar tudo")
            }
            OutlinedButton(onClick = { show(Dlg.DeletePlaylist(pl.name)) }) {
                Text("Excluir playlist")
            }
        }
        if (list.isEmpty()) {
            Text("Vazia. Adicione áudios pelo menu ⋮ da biblioteca.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { _, b -> b.path }) { i, b ->
                val actions: List<Pair<String, () -> Unit>> = listOf(
                    "Remover da playlist" to { vm.removeFromPlaylist(pl.name, b.path) }
                )
                BookRow(b, tick, actions) { holder.playQueue(list, i) }
            }
        }
    }
}

@Composable
private fun BookRow(
    b: Book,
    tick: Int,
    actions: List<Pair<String, () -> Unit>>,
    extra: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val progress = remember(tick, b.path) { Store.progress(ctx, b.path) }
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                b.title,
                style = MaterialTheme.typography.bodyLarge
            )
            val pct = if (progress > 0f) " • ${(progress * 100).toInt()}%" else ""
            Text(
                "${b.folder} • " + "%.1f MB".format(b.sizeMb) + pct,
                style = MaterialTheme.typography.bodySmall
            )
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp)
                )
            }
        }
        extra?.invoke()
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Opções")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { (label, act) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act() })
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun Dialogs(dlg: Dlg?, set: (Dlg?) -> Unit, vm: LibraryViewModel, holder: PlayerHolder) {
    val close: () -> Unit = { set(null) }
    when (dlg) {
        null -> Unit

        is Dlg.Rename -> TextDialog("Renomear", dlg.b.title, "Novo nome", close) { name ->
            holder.stopIfUsing(dlg.b.path)
            vm.rename(dlg.b, name)
            close()
        }

        is Dlg.NewPlaylist -> TextDialog("Nova playlist", "", "Nome", close) { name ->
            vm.createPlaylist(name)
            close()
        }

        is Dlg.NewPlaylistFor -> TextDialog("Nova playlist", "", "Nome", close) { name ->
            vm.createPlaylist(name, dlg.b.path)
            close()
        }

        is Dlg.DeletePlaylist -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Excluir playlist?") },
            text = { Text("\"${dlg.name}\" será removida. Os áudios continuam no aparelho.") },
            confirmButton = {
                TextButton(onClick = { vm.deletePlaylist(dlg.name); close() }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )

        is Dlg.Delete -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Excluir arquivo?") },
            text = { Text("\"${dlg.b.title}\" será apagado do aparelho. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = {
                    holder.stopIfUsing(dlg.b.path)
                    vm.delete(dlg.b)
                    close()
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )

        is Dlg.AddTo -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Adicionar à playlist") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        Text(
                            "+ Nova playlist",
                            Modifier.fillMaxWidth().clickable { set(Dlg.NewPlaylistFor(dlg.b)) }.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    items(vm.playlists, key = { it.name }) { p ->
                        Text(
                            p.name,
                            Modifier.fillMaxWidth().clickable {
                                vm.addToPlaylist(p.name, dlg.b.path)
                                close()
                            }.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )

        is Dlg.Pick -> FolderPicker(
            title = if (dlg.move) "Mover para..." else "Copiar para...",
            start = File(dlg.b.path).parentFile ?: Environment.getExternalStorageDirectory(),
            onDismiss = close
        ) { dir ->
            if (dlg.move) {
                holder.stopIfUsing(dlg.b.path)
                vm.move(dlg.b, dir)
            } else {
                vm.copy(dlg.b, dir)
            }
            close()
        }
    }
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun FolderPicker(title: String, start: File, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    var dir by remember { mutableStateOf(start) }
    var creating by remember { mutableStateOf(false) }
    val subs = remember(dir) { FileOps.subdirs(dir) }

    if (creating) {
        TextDialog("Nova pasta", "", "Nome da pasta", { creating = false }) { n ->
            FileOps.mkdir(dir, n)?.let { dir = it }
            creating = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    val up = dir.parentFile
                    if (up != null && up.canRead()) {
                        item {
                            Text(
                                ".. (subir)",
                                Modifier.fillMaxWidth().clickable { dir = up }.padding(12.dp)
                            )
                        }
                    }
                    items(subs, key = { it.absolutePath }) { f ->
                        Text(
                            f.name + "/",
                            Modifier.fillMaxWidth().clickable { dir = f }.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(dir) }) { Text("Escolher esta pasta") } },
        dismissButton = {
            Row {
                TextButton(onClick = { creating = true }) { Text("Nova pasta") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

@Composable
fun PlayerBar(c: MediaController) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(c.isPlaying) }
    var pos by remember { mutableLongStateOf(c.currentPosition) }
    var dur by remember { mutableLongStateOf(c.duration.coerceAtLeast(0L)) }
    var title by remember { mutableStateOf(c.mediaMetadata.title?.toString() ?: "") }
    var speed by remember { mutableFloatStateOf(c.playbackParameters.speed) }
    var repeat by remember { mutableIntStateOf(c.repeatMode) }
    var count by remember { mutableIntStateOf(c.mediaItemCount) }

    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onEvents(p: Player, e: Player.Events) {
                dur = p.duration.coerceAtLeast(0L)
                title = p.mediaMetadata.title?.toString() ?: ""
                speed = p.playbackParameters.speed
                repeat = p.repeatMode
                count = p.mediaItemCount
            }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }

    LaunchedEffect(c, playing) {
        do {
            pos = c.currentPosition
            if (playing) delay(1000)
        } while (playing)
    }

    Surface(tonalElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title)
            Slider(
                value = pos.toFloat().coerceIn(0f, maxOf(dur, 1L).toFloat()),
                onValueChange = { pos = it.toLong() },
                onValueChangeFinished = { c.seekTo(pos) },
                valueRange = 0f..maxOf(dur, 1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), style = MaterialTheme.typography.bodySmall)
                Text(fmt(dur), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (count > 1) TextButton(onClick = { c.seekToPreviousMediaItem() }) { Text("|<") }
                TextButton(onClick = { c.seekBack() }) { Text("-10s") }
                FilledIconButton(onClick = { if (playing) c.pause() else c.play() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
                TextButton(onClick = { c.seekForward() }) { Text("+10s") }
                if (count > 1) TextButton(onClick = { c.seekToNextMediaItem() }) { Text(">|") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    val next = steps.firstOrNull { it > speed + 0.01f } ?: steps.first()
                    c.setPlaybackSpeed(next)
                }) { Text("${speed}x") }
                TextButton(onClick = {
                    val next = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    c.repeatMode = next
                    Store.setRepeat(ctx, next)
                }) {
                    Text(
                        when (repeat) {
                            Player.REPEAT_MODE_ONE -> "Loop: 1 áudio"
                            Player.REPEAT_MODE_ALL -> "Loop: lista"
                            else -> "Loop: off"
                        }
                    )
                }
            }
        }
    }
}
