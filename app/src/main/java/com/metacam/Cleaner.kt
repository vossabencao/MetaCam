package com.metacam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

class Fake(
    val timeMs: Long,
    val make: String,
    val model: String,
    val lat: Double,
    val lon: Double,
    val author: String
)

object FakeMeta {
    private val DEVICES = listOf(
        "samsung" to "SM-A525F", "samsung" to "SM-G991B", "Google" to "Pixel 6",
        "Xiaomi" to "M2101K6G", "motorola" to "moto g(30)", "OnePlus" to "LE2113"
    )
    private val PLACES = listOf(
        48.8566 to 2.3522, 51.5074 to -0.1278, 40.4168 to -3.7038, 52.5200 to 13.4050,
        41.9028 to 12.4964, 45.4642 to 9.1900, 50.0755 to 14.4378, 59.3293 to 18.0686
    )
    private val AUTHORS = listOf("User", "Admin", "Owner", "Guest", "PC")

    fun random(): Fake {
        val r = SecureRandom()
        val back = (r.nextInt(94_608_000) + 86_400).toLong() * 1000L
        val (make, model) = DEVICES[r.nextInt(DEVICES.size)]
        val (la, lo) = PLACES[r.nextInt(PLACES.size)]
        return Fake(
            System.currentTimeMillis() - back, make, model,
            la + (r.nextDouble() - 0.5) * 0.1,
            lo + (r.nextDouble() - 0.5) * 0.1,
            AUTHORS[r.nextInt(AUTHORS.size)]
        )
    }
}

class Outcome(val file: File?, val note: String, val ok: Boolean)

object Cleaner {
    private val BAD = Regex("[/\\\\:*?\"<>|]")
    private val THUMB_REL = Regex("<Relationship\\b[^>]*thumbnail[^>]*/>")

    private const val CORE_NS =
        "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
    private const val APP_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"/>"
    private const val CUSTOM_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"/>"

    fun dir(c: Context): File = File(c.filesDir, "clean").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun process(
        c: Context,
        uri: Uri,
        name: String,
        poison: Boolean,
        randomName: Boolean
    ): Outcome {
        val ext = name.substringAfterLast('.', "").lowercase()
        val fake = if (poison) FakeMeta.random() else null
        return try {
            when (ext) {
                "jpg", "jpeg", "png", "webp", "heic", "heif" -> image(c, uri, name, ext, fake, randomName)
                "mp4", "m4v", "mov", "3gp", "m4a", "m4b" -> iso(c, uri, name, ext, fake, randomName)
                "docx", "xlsx", "pptx" -> zip(c, uri, name, ext, false, fake, randomName)
                "odt", "ods", "odp" -> zip(c, uri, name, ext, true, fake, randomName)
                "pdf" -> pdf(c, uri, name, randomName, fake != null)
                else -> Outcome(null, "formato não suportado", false)
            }
        } catch (t: Throwable) {
            Outcome(null, "falhou: " + (t.message ?: t.javaClass.simpleName), false)
        }
    }

    // ---------- util ----------

    private inline fun File.cleanup(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            delete()
            throw t
        }
    }

    private fun target(c: Context, name: String, ext: String, randomName: Boolean): File {
        val base = if (randomName) {
            ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        } else {
            name.substringBeforeLast('.', name).replace(BAD, "_").trim().ifBlank { "arquivo" }
        }
        var f = File(dir(c), "$base.$ext")
        var n = 1
        while (f.exists()) {
            f = File(dir(c), "$base-$n.$ext")
            n++
        }
        return f
    }

    private fun fmt(p: String, ms: Long): String =
        SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    // ---------- imagens ----------

    private fun image(
        c: Context, uri: Uri, name: String, ext: String, fake: Fake?, rn: Boolean
    ): Outcome {
        var orient = ExifInterface.ORIENTATION_NORMAL
        try {
            c.contentResolver.openInputStream(uri)?.use { s ->
                orient = ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
        }
        val jpeg = ext == "jpg" || ext == "jpeg" || ext == "heic" || ext == "heif"
        val outExt = if (jpeg) "jpg" else ext
        val bmp = applyOrientation(decode(c, uri), orient)
        val out = target(c, name, outExt, rn)
        out.cleanup {
            out.outputStream().use { o ->
                val ok = when {
                    jpeg -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, o)
                    ext == "png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                    else -> bmp.compress(webpFormat(), 100, o)
                }
                if (!ok) error("falha ao codificar a imagem")
            }
            bmp.recycle()
            if (fake != null && jpeg) writeFakeExif(out, fake)
            out.setLastModified(fake?.timeMs ?: 0L)
        }
        val note = when {
            fake != null && jpeg -> "envenenado (EXIF falso)"
            fake != null -> "limpo (veneno só em JPEG)"
            else -> "limpo"
        }
        return Outcome(out, note, true)
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.WEBP

    private fun decode(c: Context, uri: Uri): Bitmap {
        var sample = 1
        while (true) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val s = c.contentResolver.openInputStream(uri) ?: error("não foi possível abrir")
                s.use {
                    return BitmapFactory.decodeStream(it, null, opts)
                        ?: error("não foi possível decodificar a imagem")
                }
            } catch (e: OutOfMemoryError) {
                if (sample >= 8) throw e
                sample *= 2
            }
        }
    }

    private fun applyOrientation(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    private fun writeFakeExif(f: File, k: Fake) {
        val e = ExifInterface(f.absolutePath)
        val d = fmt("yyyy:MM:dd HH:mm:ss", k.timeMs)
        e.setAttribute(ExifInterface.TAG_MAKE, k.make)
        e.setAttribute(ExifInterface.TAG_MODEL, k.model)
        e.setAttribute(ExifInterface.TAG_DATETIME, d)
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, d)
        e.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, d)
        e.setLatLong(k.lat, k.lon)
        e.saveAttributes()
    }

    // ---------- vídeo / áudio (MP4, MOV, M4A, 3GP) ----------

    private fun iso(
        c: Context, uri: Uri, name: String, ext: String, fake: Fake?, rn: Boolean
    ): Outcome {
        val out = target(c, name, ext, rn)
        out.cleanup {
            val input = c.contentResolver.openInputStream(uri) ?: error("não foi possível abrir")
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            val n = MetaScrub.scrubMp4(out, fake?.timeMs)
            if (n == 0) error("estrutura MP4/MOV não reconhecida")
            out.setLastModified(fake?.timeMs ?: 0L)
        }
        return Outcome(out, if (fake != null) "envenenado (datas falsas)" else "limpo", true)
    }

    // ---------- documentos (OOXML e ODF) ----------

    private fun coreXml(f: Fake?): String {
        val body = if (f == null) "" else {
            val d = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'", f.timeMs)
            "<dc:creator>${f.author}</dc:creator>" +
                "<cp:lastModifiedBy>${f.author}</cp:lastModifiedBy>" +
                "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$d</dcterms:created>" +
                "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$d</dcterms:modified>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties $CORE_NS>$body</cp:coreProperties>"
    }

    private fun odfMeta(f: Fake?): String {
        val body = if (f == null) "<office:meta/>" else {
            val d = fmt("yyyy-MM-dd'T'HH:mm:ss", f.timeMs)
            "<office:meta><meta:initial-creator>${f.author}</meta:initial-creator>" +
                "<dc:creator>${f.author}</dc:creator>" +
                "<meta:creation-date>$d</meta:creation-date><dc:date>$d</dc:date></office:meta>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<office:document-meta xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" office:version=\"1.2\">$body" +
            "</office:document-meta>"
    }

    private fun zip(
        c: Context, uri: Uri, name: String, ext: String, odf: Boolean, fake: Fake?, rn: Boolean
    ): Outcome {
        val out = target(c, name, ext, rn)
        val stamp = fake?.timeMs ?: 315_532_800_000L
        out.cleanup {
            var valid = false
            val input = c.contentResolver.openInputStream(uri) ?: error("não foi possível abrir")
            ZipInputStream(input.buffered()).use { zin ->
                ZipOutputStream(out.outputStream().buffered()).use { zout ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val n = e.name
                        val data = zin.readBytes()
                        var bytes: ByteArray? = data
                        if (n == "[Content_Types].xml" || n == "mimetype") valid = true
                        if (odf) {
                            if (n == "meta.xml") bytes = odfMeta(fake).toByteArray()
                        } else {
                            when {
                                n == "docProps/core.xml" -> bytes = coreXml(fake).toByteArray()
                                n == "docProps/app.xml" -> bytes = APP_XML.toByteArray()
                                n == "docProps/custom.xml" -> bytes = CUSTOM_XML.toByteArray()
                                n.startsWith("docProps/thumbnail") -> bytes = null
                                n == "_rels/.rels" ->
                                    bytes = String(data, Charsets.UTF_8).replace(THUMB_REL, "").toByteArray()
                            }
                        }
                        if (bytes == null) continue
                        val ne = ZipEntry(n)
                        ne.time = stamp
                        if (e.method == ZipEntry.STORED) {
                            val crc = CRC32()
                            crc.update(bytes)
                            ne.method = ZipEntry.STORED
                            ne.size = bytes.size.toLong()
                            ne.compressedSize = bytes.size.toLong()
                            ne.crc = crc.value
                        }
                        zout.putNextEntry(ne)
                        zout.write(bytes)
                        zout.closeEntry()
                    }
                }
            }
            if (!valid) error("não parece um documento válido")
            out.setLastModified(stamp)
        }
        return Outcome(out, if (fake != null) "envenenado (autor e datas falsos)" else "limpo", true)
    }

    // ---------- PDF (refeito a partir das páginas) ----------

    private fun pdf(c: Context, uri: Uri, name: String, rn: Boolean, wantPoison: Boolean): Outcome {
        val tmp = File(c.cacheDir, "pdf_" + System.nanoTime() + ".tmp")
        val out = target(c, name, "pdf", rn)
        val doc = PdfDocument()
        try {
            out.cleanup {
                val input = c.contentResolver.openInputStream(uri) ?: error("não foi possível abrir")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val r = PdfRenderer(pfd)
                    try {
                        for (idx in 0 until r.pageCount) {
                            val p = r.openPage(idx)
                            try {
                                val scale = minOf(
                                    2f, sqrt(12_000_000f / (p.width.toFloat() * p.height.toFloat()))
                                )
                                val bmp = Bitmap.createBitmap(
                                    (p.width * scale).toInt().coerceAtLeast(1),
                                    (p.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val info = PdfDocument.PageInfo.Builder(p.width, p.height, idx + 1).create()
                                val page = doc.startPage(info)
                                page.canvas.drawBitmap(bmp, null, Rect(0, 0, p.width, p.height), null)
                                doc.finishPage(page)
                                bmp.recycle()
                            } finally {
                                p.close()
                            }
                        }
                    } finally {
                        r.close()
                    }
                } finally {
                    pfd.close()
                }
                out.outputStream().use { doc.writeTo(it) }
                out.setLastModified(0L)
            }
        } finally {
            doc.close()
            Vault.wipe(tmp)
        }
        val note = if (wantPoison) "limpo (veneno indisponível em PDF; páginas viraram imagem)"
        else "limpo (páginas viraram imagem)"
        return Outcome(out, note, true)
    }
}
