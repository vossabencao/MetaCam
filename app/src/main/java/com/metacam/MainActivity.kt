package com.metacam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        Vault.clearExports(this)
        takeShared(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)
            ) { Root(incoming) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(i: Intent?) {
        if (i == null) return
        val list: List<Uri> = when (i.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> emptyList()
        }
        if (list.isNotEmpty()) incoming.value = list
    }
}

@Composable
fun Root(incoming: MutableState<List<Uri>>) {
    var tab by remember { mutableIntStateOf(if (incoming.value.isNotEmpty()) 1 else 0) }
    val busy = remember { mutableStateOf(false) }

    LaunchedEffect(incoming.value) { if (incoming.value.isNotEmpty()) tab = 1 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    enabled = !busy.value,
                    onClick = { tab = 0 },
                    icon = { Box(Modifier.size(1.dp)) },
                    label = { Text("Câmera") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Box(Modifier.size(1.dp)) },
                    label = { Text("Limpeza") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    enabled = !busy.value,
                    onClick = { tab = 2 },
                    icon = { Box(Modifier.size(1.dp)) },
                    label = { Text("Config") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CameraTab()
                1 -> CleanScreen(incoming, busy)
                else -> SettingsScreen()
            }
        }
    }
}

// ---------------- Exportar para a galeria ----------------

@Composable
fun rememberGalleryExporter(onDone: (String) -> Unit): (List<File>) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<File>>(emptyList()) }

    fun run(list: List<File>) {
        scope.launch {
            val delAfter = Prefs.deleteAfterExport(ctx)
            val r = withContext(Dispatchers.IO) {
                var ok = 0
                var fail = 0
                var removed = 0
                for (f in list) {
                    if (Gallery.export(ctx, f).isSuccess) {
                        ok++
                        if (delAfter) {
                            Vault.wipe(f)
                            removed++
                        }
                    } else {
                        fail++
                    }
                }
                Triple(ok, fail, removed)
            }
            var msg = if (r.first == 0) "Nada foi exportado" else "${r.first} exportado(s) para a galeria"
            if (r.third > 0) msg += ", ${r.third} apagado(s) do app"
            if (r.second > 0) msg += ", ${r.second} falhou(aram)"
            onDone(msg)
        }
    }

    val perm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val l = pending
        pending = emptyList()
        if (ok) run(l) else onDone("Permissão de armazenamento negada")
    }

    return { list ->
        if (list.isNotEmpty()) {
            if (Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pending = list
                perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                run(list)
            }
        }
    }
}

// ---------------- Configurações ----------------

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var del by remember { mutableStateOf(Prefs.deleteAfterExport(ctx)) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Configurações", style = MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apagar do app após exportar", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Depois de exportar para a galeria com sucesso, o arquivo é sobrescrito " +
                        "e excluído do cofre e da lista de arquivos limpos. Se a exportação " +
                        "falhar, nada é apagado.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = del,
                onCheckedChange = {
                    del = it
                    Prefs.setDeleteAfterExport(ctx, it)
                }
            )
        }

        HorizontalDivider()

        Text("Onde ficam os arquivos exportados", style = MaterialTheme.typography.titleMedium)
        Text("Fotos: Pictures/MetaCam", style = MaterialTheme.typography.bodySmall)
        Text("Vídeos: Movies/MetaCam", style = MaterialTheme.typography.bodySmall)
        Text("Áudios: Music/MetaCam", style = MaterialTheme.typography.bodySmall)
        Text(
            "PDF e documentos: Download/MetaCam (não aparecem na galeria)",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text("Atenção", style = MaterialTheme.typography.titleMedium)
        Text(
            "Na galeria, o arquivo deixa de ser privado. Apps com acesso a fotos e vídeos " +
                "conseguem lê-lo, e a galeria ou o backup em nuvem do aparelho pode enviá-lo. " +
                "A data de exibição será a de hoje, e não a original.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---------------- Câmera ----------------

@Composable
fun CameraTab() {
    val ctx = LocalContext.current
    var cam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cam = it }
    var vault by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!cam) launcher.launch(Manifest.permission.CAMERA) }

    if (!cam) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("O app precisa da câmera.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Conceder") }
        }
    } else if (vault) {
        VaultScreen { vault = false }
    } else {
        CameraScreen { vault = true }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(onVault: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val main = remember { ContextCompat.getMainExecutor(ctx) }
    val worker = remember { Executors.newSingleThreadExecutor() }

    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var audio by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
        )
    }
    val previewView = remember { PreviewView(ctx) }

    val audioPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { audio = it }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            try {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(lens) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    owner,
                    CameraSelector.Builder().requireLensFacing(lens).build(),
                    preview, imageCapture, videoCapture
                )
            } catch (e: Exception) {
                msg = "Falha ao abrir a câmera"
            }
        }, main)
    }

    fun takePhoto() {
        imageCapture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val ok = try {
                    Vault.savePhoto(ctx, image)
                } catch (e: Exception) {
                    false
                } finally {
                    image.close()
                }
                main.execute { msg = if (ok) "Foto salva no cofre" else "Falha ao salvar" }
            }

            override fun onError(exception: ImageCaptureException) {
                main.execute { msg = "Falha ao capturar" }
            }
        })
    }

    fun startRec() {
        val file = Vault.newFile(ctx, "mp4")
        var prep = videoCapture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        if (audio && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            prep = prep.withAudioEnabled()
        }
        recording = prep.start(main) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                if (ev.hasError()) {
                    file.delete()
                    msg = "Gravação falhou"
                } else {
                    msg = "Limpando metadados..."
                    Thread {
                        try {
                            MetaScrub.scrubMp4(file)
                            file.setLastModified(0L)
                            main.execute { msg = "Vídeo salvo no cofre" }
                        } catch (e: Exception) {
                            main.execute { msg = "Vídeo salvo, falha na limpeza" }
                        }
                    }.start()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onVault) { Text("Cofre") }
                Button(onClick = { takePhoto() }, enabled = recording == null) { Text("Foto") }
                Button(onClick = {
                    val r = recording
                    if (r != null) r.stop() else startRec()
                }) { Text(if (recording != null) "Parar" else "Gravar") }
                TextButton(
                    onClick = {
                        lens = if (lens == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    enabled = recording == null
                ) { Text("Virar") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = audio,
                    onCheckedChange = { on ->
                        if (!on) audio = false
                        else if (ContextCompat.checkSelfPermission(
                                ctx, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) audio = true
                        else audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = recording == null
                )
                Spacer(Modifier.width(8.dp))
                Text("Gravar áudio nos vídeos")
            }
        }
    }
}

@Composable
fun VaultScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(Vault.list(ctx)) }
    var del by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val export = rememberGalleryExporter { msg ->
        info = msg
        files = Vault.list(ctx)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Voltar") }
            Text("${files.size} itens no cofre")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { export(files) }, enabled = files.isNotEmpty()) {
                Text("Exportar todos para a galeria")
            }
        }
        info?.let {
            Text(
                it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.name }) { f ->
                val isPhoto = f.extension == "jpg"
                val thumb = remember(f.name) {
                    if (isPhoto) BitmapFactory.decodeFile(
                        f.path, BitmapFactory.Options().apply { inSampleSize = 8 }
                    ) else null
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (thumb != null) {
                        Image(
                            thumb.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.size(64.dp)
                        )
                    } else {
                        Box(
                            Modifier.size(64.dp).background(Color(0xFF222222)),
                            contentAlignment = Alignment.Center
                        ) { Text("MP4") }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(f.name)
                        Text(
                            "%.1f MB".format(f.length() / 1_048_576f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { export(listOf(f)) }) { Text("Galeria") }
                            TextButton(onClick = {
                                val uri = Vault.export(ctx, f)
                                val mime = if (isPhoto) "image/jpeg" else "video/mp4"
                                val i = Intent(Intent.ACTION_SEND)
                                    .setType(mime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(i, null))
                            }) { Text("Enviar") }
                            TextButton(onClick = { del = f }) { Text("Apagar") }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    del?.let { f ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Apagar de vez?") },
            text = { Text("O arquivo será sobrescrito e excluído. Não dá para desfazer.") },
            confirmButton = {
                TextButton(onClick = {
                    Vault.wipe(f)
                    files = Vault.list(ctx)
                    del = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Cancelar") } }
        )
    }
}

// ---------------- Limpeza de metadados ----------------

private class Picked(val uri: Uri, val name: String, val size: Long)
private class Res(val name: String, val note: String, val ok: Boolean)

private fun describe(ctx: Context, uri: Uri): Picked {
    var name = "arquivo"
    var size = -1L
    try {
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { q ->
            if (q.moveToFirst()) {
                val n = q.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0) q.getString(n)?.let { name = it }
                val s = q.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !q.isNull(s)) size = q.getLong(s)
            }
        }
    } catch (e: Exception) {
    }
    if (!name.contains('.')) {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri))
        if (ext != null) name = "$name.$ext"
    }
    return Picked(uri, name, size)
}

private fun mb(bytes: Long): String =
    if (bytes < 0) "tamanho desconhecido" else "%.1f MB".format(bytes / 1_048_576f)

private fun shareFiles(ctx: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { Vault.export(ctx, it, keepName = true) })
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND)
            .setType(Cleaner.mimeOf(files[0]))
            .putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null))
}

@Composable
fun CleanScreen(incoming: MutableState<List<Uri>>, busyState: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var poison by remember { mutableStateOf(false) }
    var randomName by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Res>>(emptyList()) }
    var cleaned by remember { mutableStateOf(Cleaner.list(ctx)) }
    var saving by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val busy = busyState.value
    val export = rememberGalleryExporter { msg ->
        info = msg
        cleaned = Cleaner.list(ctx)
    }

    fun add(l: List<Uri>) {
        val have = picked.map { it.uri }.toSet()
        picked = picked + l.filter { it !in have }.map { describe(ctx, it) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> add(uris) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val f = saving
        saving = null
        if (uri != null && f != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { o ->
                            f.inputStream().use { it.copyTo(o) }
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                info = if (ok) "Arquivo salvo" else "Falha ao salvar"
            }
        }
    }

    LaunchedEffect(incoming.value) {
        if (incoming.value.isNotEmpty()) {
            add(incoming.value)
            incoming.value = emptyList()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Meta Cam", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Limpeza de metadados. Tudo roda no aparelho e os originais não são alterados.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Fotos (JPG, PNG, WebP, HEIC), vídeo e áudio (MP4, MOV, M4A, 3GP), " +
                        "documentos (DOCX, XLSX, PPTX, ODT, ODS, ODP) e PDF.",
                    style = MaterialTheme.typography.bodySmall
                )
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !poison, enabled = !busy,
                        onClick = { poison = false }, label = { Text("Limpar") }
                    )
                    FilterChip(
                        selected = poison, enabled = !busy,
                        onClick = { poison = true }, label = { Text("Envenenar") }
                    )
                }
                Text(
                    if (poison) "Troca os metadados por valores falsos e plausíveis: aparelho, data, " +
                        "local e autor. Só onde o formato permite."
                    else "Remove metadados: EXIF, GPS, datas, autor e propriedades.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = randomName, enabled = !busy, onCheckedChange = { randomName = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Nome de arquivo aleatório")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Selecionar arquivos") }
                Button(
                    onClick = {
                        busyState.value = true
                        results = emptyList()
                        info = null
                        val batch = picked
                        val po = poison
                        val rn = randomName
                        scope.launch {
                            val out = mutableListOf<Res>()
                            for ((idx, p) in batch.withIndex()) {
                                progress = "${idx + 1}/${batch.size}: ${p.name}"
                                val o = withContext(Dispatchers.IO) {
                                    Cleaner.process(ctx, p.uri, p.name, po, rn)
                                }
                                out.add(Res(p.name, o.note, o.ok))
                            }
                            results = out
                            picked = emptyList()
                            cleaned = Cleaner.list(ctx)
                            progress = ""
                            busyState.value = false
                        }
                    },
                    enabled = picked.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (picked.isEmpty()) "Nenhum arquivo selecionado"
                        else (if (poison) "Envenenar " else "Limpar ") + "${picked.size} arquivo(s)"
                    )
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (picked.isNotEmpty()) {
            item { Text("Selecionados", style = MaterialTheme.typography.titleMedium) }
            items(picked, key = { it.uri.toString() }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(mb(p.size), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { picked = picked.filter { it.uri != p.uri } },
                        enabled = !busy
                    ) { Text("Remover") }
                }
            }
        }

        if (results.isNotEmpty()) {
            item { Text("Resultado", style = MaterialTheme.typography.titleMedium) }
            items(results) { r ->
                Column {
                    Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        r.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (cleaned.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    Text(
                        "Arquivos limpos (${cleaned.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        TextButton(onClick = { shareFiles(ctx, cleaned) }) { Text("Enviar todos") }
                        TextButton(onClick = { export(cleaned) }, enabled = !busy) {
                            Text("Galeria (todos)")
                        }
                    }
                    Row {
                        TextButton(
                            onClick = {
                                val all = cleaned
                                scope.launch {
                                    withContext(Dispatchers.IO) { all.forEach { Vault.wipe(it) } }
                                    cleaned = Cleaner.list(ctx)
                                }
                            },
                            enabled = !busy
                        ) { Text("Apagar todos") }
                    }
                }
            }
            items(cleaned, key = { it.name }) { f ->
                Column {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mb(f.length()), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { shareFiles(ctx, listOf(f)) }) { Text("Enviar") }
                        TextButton(onClick = { export(listOf(f)) }, enabled = !busy) { Text("Galeria") }
                        TextButton(onClick = {
                            saving = f
                            saver.launch(f.name)
                        }) { Text("Salvar") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Vault.wipe(f) }
                                    cleaned = Cleaner.list(ctx)
                                }
                            },
                            enabled = !busy
                        ) { Text("Apagar") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
