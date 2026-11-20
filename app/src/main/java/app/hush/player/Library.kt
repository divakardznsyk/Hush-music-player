package app.hush.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

/**
 * Reads the phone's music from MediaStore and applies your own changes on top:
 * custom order, custom title / artist and custom cover. Your music files are never modified.
 * Shared by the app screen and the service (for resuming from the widget).
 */
object Library {

    private val losslessFormats = setOf("FLAC", "WAV", "AIFF", "AIF", "ALAC", "APE", "WV", "DSF", "DFF")

    fun audioBase(): Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun coverFile(context: Context, id: String): File =
        File(File(context.filesDir, "covers"), "$id.jpg")

    /** The folder you picked, as a path inside storage ("Music/MyTracks"); null until you pick one. */
    fun folderRel(context: Context): String? {
        val u = context.getSharedPreferences("hush", Context.MODE_PRIVATE).getString("folderUri", null) ?: return null
        return try {
            DocumentsContract.getTreeDocumentId(Uri.parse(u)).substringAfter(':', "").trim('/')
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun query(context: Context): List<MediaItem> {
        val rel = folderRel(context) ?: return emptyList() // no folder chosen = no music from other apps
        val base = audioBase()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID
        )
        // Everything audible except ringtones, notification sounds and alarms.
        var sel = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"
        var args: Array<String>? = null
        if (rel.isNotEmpty()) {
            val esc = rel.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            if (Build.VERSION.SDK_INT >= 29) {
                sel += " AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
                args = arrayOf("$esc/%")
            } else {
                sel += " AND ${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'"
                args = arrayOf("%/$esc/%")
            }
        }
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val albumArtBase = Uri.parse("content://media/external/audio/albumart")

        val meta = context.getSharedPreferences("hush_meta", Context.MODE_PRIVATE).all
        val orderStr = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
            .getString("order", "") ?: ""

        val out = ArrayList<MediaItem>()
        context.contentResolver.query(base, proj, sel, args, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val sid = id.toString()
                val name = c.getString(iName) ?: ""
                val tagArtist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val artist = (meta["a_$sid"] as? String)?.takeIf { it.isNotBlank() } ?: tagArtist
                val title = (meta["t_$sid"] as? String)?.takeIf { it.isNotBlank() }
                    ?: c.getString(iTitle) ?: name
                val format = name.substringAfterLast('.', "").uppercase()
                val dur = c.getLong(iDur)
                val size = c.getLong(iSize)
                val kbps = if (dur > 0) (size * 8L / dur).toInt() else 0 // bits per ms == kbps
                val albumId = c.getLong(iAlbum)
                val cover = coverFile(context, sid)

                val extras = Bundle().apply {
                    putString("fmt", format)
                    putString("name", name)
                    putString("artist", artist ?: "")
                    putInt("kbps", kbps)
                    putBoolean("lossless", format in losslessFormats)
                    putBoolean("cover", cover.exists())
                }
                val md = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setDurationMs(dur)
                    .setExtras(extras)
                // our own PlaybackService loader turns this into the real picture (see art())
                md.setArtworkUri(Uri.parse("hushart://$sid?v=${cover.lastModified()}"))

                out.add(
                    MediaItem.Builder()
                        .setMediaId(sid)
                        .setUri(ContentUris.withAppendedId(base, id))
                        .setMediaMetadata(md.build())
                        .build()
                )
            }
        }

        // Your custom order first; songs you haven't placed yet follow in title order.
        if (orderStr.isNotEmpty()) {
            val rank = HashMap<String, Int>()
            orderStr.split(',').forEachIndexed { i, s -> rank[s] = i }
            return out.sortedBy { rank[it.mediaId] ?: Int.MAX_VALUE }
        }
        return out
    }

    /** The song's picture: your own cover, else the one stored in the file, else the phone's thumbnail. */
    fun art(context: Context, id: String): Bitmap? {
        val f = coverFile(context, id)
        var bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        val uri = ContentUris.withAppendedId(audioBase(), id.toLong())
        if (bmp == null) bmp = embeddedArt(context, uri)
        if (bmp == null) bmp = wavArt(context, uri)
        if (bmp == null && Build.VERSION.SDK_INT >= 29) {
            bmp = try {
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (e: Exception) {
                null
            }
        }
        return bmp
    }

    private fun embeddedArt(context: Context, uri: Uri): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bytes = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var n = 1
            while (o.outWidth / n > 1024 || o.outHeight / n > 1024) n *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = n })
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** WAV files keep their cover in an "id3 " chunk (ID3v2 APIC frame), which Android doesn't read. */
    private fun wavArt(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
            val h = java.nio.ByteBuffer.allocate(12)
            ch.read(h, 0)
            if (String(h.array(), 0, 4) != "RIFF") return null
            val c = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var pos = 12L
            while (pos + 8 <= ch.size()) {
                c.clear()
                ch.read(c, pos)
                val size = c.getInt(4).toLong() and 0xFFFFFFFFL
                if (String(c.array(), 0, 4).equals("id3 ", true)) {
                    if (size > 30_000_000) return null // shortcut: huge tag chunk, skip it
                    val b = java.nio.ByteBuffer.allocate(size.toInt())
                    while (b.hasRemaining() && ch.read(b, pos + 8 + b.position()) > 0) { }
                    return id3Art(b.array())
                }
                pos += 8 + size + (size and 1L)
            }
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun id3Art(d: ByteArray): Bitmap? {
        if (d.size < 10 || String(d, 0, 3) != "ID3") return null
        fun safe(o: Int) = (d[o].toInt() shl 21) or (d[o + 1].toInt() shl 14) or (d[o + 2].toInt() shl 7) or d[o + 3].toInt()
        fun be(o: Int) = ((d[o].toInt() and 255) shl 24) or ((d[o + 1].toInt() and 255) shl 16) or ((d[o + 2].toInt() and 255) shl 8) or (d[o + 3].toInt() and 255)
        val v4 = d[3].toInt() == 4
        val end = minOf(d.size, 10 + safe(6))
        var p = 10
        while (p + 10 <= end) {
            val sz = if (v4) safe(p + 4) else be(p + 4)
            val stop = p + 10 + sz
            if (sz <= 0 || stop > d.size) break
            if (String(d, p, 4, Charsets.ISO_8859_1) == "APIC") {
                val enc = d[p + 10].toInt()
                var q = p + 11
                while (q < stop && d[q] != 0.toByte()) q++ // mime type
                q += 2 // its end + picture type
                if (enc == 1 || enc == 2) {
                    while (q + 1 < stop && !(d[q] == 0.toByte() && d[q + 1] == 0.toByte())) q += 2
                    q += 2
                } else {
                    while (q < stop && d[q] != 0.toByte()) q++
                    q++
                }
                if (q >= stop) return null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(d, q, stop - q, o)
                var n = 1
                while (o.outWidth / n > 1024 || o.outHeight / n > 1024) n *= 2
                return BitmapFactory.decodeByteArray(d, q, stop - q, BitmapFactory.Options().apply { inSampleSize = n })
            }
            p = stop
        }
        return null
    }
}
