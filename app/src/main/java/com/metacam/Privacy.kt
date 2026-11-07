package com.metacam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

object MetaScrub {
    private val DESCEND = setOf("moov", "trak", "mdia")
    private val TIMES = setOf("mvhd", "tkhd", "mdhd")
    private val DROP = setOf("udta", "meta", "uuid")
    private const val MAC_EPOCH = 2_082_844_800L

    fun scrubMp4(f: File, fakeMs: Long? = null): Int {
        val t = if (fakeMs == null) 0L else fakeMs / 1000L + MAC_EPOCH
        var n = 0
        RandomAccessFile(f, "rw").use { raf -> n = walk(raf, 0L, raf.length(), t) }
        return n
    }

    private fun walk(raf: RandomAccessFile, start: Long, end: Long, t: Long): Int {
        var count = 0
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val tb = ByteArray(4)
            raf.readFully(tb)
            val type = String(tb, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                size = raf.readLong()
                header = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || pos + size > end) return count
            when (type) {
                in DESCEND -> count += walk(raf, pos + header, pos + size, t)
                in TIMES -> {
                    setTimes(raf, pos + header, t)
                    count++
                }
                in DROP -> {
                    wipeBox(raf, pos, header, size)
                    count++
                }
            }
            pos += size
        }
        return count
    }

    private fun setTimes(raf: RandomAccessFile, p: Long, t: Long) {
        raf.seek(p)
        val version = raf.readUnsignedByte()
        raf.seek(p + 4)
        if (version == 1) {
            raf.writeLong(t)
            raf.writeLong(t)
        } else {
            val v = (t and 0xFFFFFFFFL).toInt()
            raf.writeInt(v)
            raf.writeInt(v)
        }
    }

    private fun wipeBox(raf: RandomAccessFile, pos: Long, header: Long, size: Long) {
        raf.seek(pos + 4)
        raf.write("free".toByteArray(Charsets.ISO_8859_1))
        raf.seek(pos + header)
        val z = ByteArray(8192)
        var left = size - header
        while (left > 0) {
            val n = minOf(left, z.size.toLong()).toInt()
            raf.write(z, 0, n)
            left -= n
        }
    }
}

object Vault {
    private fun sp(c: Context) = c.getSharedPreferences("v", Context.MODE_PRIVATE)

    fun dir(c: Context): File = File(c.filesDir, "vault").apply { mkdirs() }

    @Synchronized
    fun newFile(c: Context, ext: String): File {
        val n = sp(c).getInt("n", 0) + 1
        sp(c).edit().putInt("n", n).apply()
        return File(dir(c), "%06d.%s".format(n, ext))
    }

    fun list(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

    fun savePhoto(c: Context, img: ImageProxy): Boolean {
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        bytes.fill(0)
        val rot = img.imageInfo.rotationDegrees
        val bmp = if (rot != 0) {
            Bitmap.createBitmap(
                src, 0, 0, src.width, src.height,
                Matrix().apply { postRotate(rot.toFloat()) }, true
            )
        } else src
        val f = newFile(c, "jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        f.setLastModified(0L)
        return true
    }

    fun wipe(f: File) {
        try {
            RandomAccessFile(f, "rw").use { r ->
                val z = ByteArray(65536)
                var left = r.length()
                r.seek(0L)
                while (left > 0) {
                    val n = minOf(left, z.size.toLong()).toInt()
                    r.write(z, 0, n)
                    left -= n
                }
                r.fd.sync()
            }
        } catch (e: Exception) {
        }
        f.delete()
    }

    fun export(c: Context, f: File, keepName: Boolean = false): Uri {
        val d = File(c.cacheDir, "export").apply { mkdirs() }
        val name = if (keepName) {
            f.name
        } else {
            val rnd = ByteArray(8).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            rnd + "." + f.extension
        }
        val out = File(d, name)
        f.copyTo(out, true)
        out.setLastModified(f.lastModified())
        return FileProvider.getUriForFile(c, c.packageName + ".fp", out)
    }

    fun clearExports(c: Context) {
        File(c.cacheDir, "export").listFiles()?.forEach { wipe(it) }
    }
}
