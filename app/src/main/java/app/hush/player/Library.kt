package app.hush.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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

    @Suppress("DEPRECATION")
    fun query(context: Context): List<MediaItem> {
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
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val albumArtBase = Uri.parse("content://media/external/audio/albumart")

        val meta = context.getSharedPreferences("hush_meta", Context.MODE_PRIVATE).all
        val orderStr = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
            .getString("order", "") ?: ""

        val out = ArrayList<MediaItem>()
        context.contentResolver.query(base, proj, sel, null, order)?.use { c ->
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
                if (cover.exists()) md.setArtworkUri(Uri.fromFile(cover))
                else if (albumId > 0) md.setArtworkUri(ContentUris.withAppendedId(albumArtBase, albumId))

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
}
