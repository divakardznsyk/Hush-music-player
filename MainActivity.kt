package app.hush.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var items: List<MediaItem> = emptyList()
    private val adapter = TrackAdapter()
    private val ui = Handler(Looper.getMainLooper())
    private var seeking = false

    private lateinit var volBar: SeekBar
    private lateinit var volLabel: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playBtn: ImageButton

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onVolumeChanged(volume: Float) = showVolume(volume)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        volBar = findViewById(R.id.volBar)
        volLabel = findViewById(R.id.volLabel)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        nowTitle = findViewById(R.id.nowTitle)
        nowSub = findViewById(R.id.nowSub)
        seekBar = findViewById(R.id.seekBar)
        playBtn = findViewById(R.id.playBtn)

        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, pos, _ -> playIndex(pos) }

        // Volume starts at whatever was saved last time.
        val saved = getSharedPreferences("hush", MODE_PRIVATE).getFloat("volume", 1f)
        showVolume(saved)
        volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                volLabel.text = "$progress%"
                controller?.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                controller?.seekTo(sb.progress.toLong())
            }
        })

        playBtn.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            when {
                c.isPlaying -> c.pause()
                c.mediaItemCount == 0 -> if (items.isNotEmpty()) playIndex(0)
                else -> c.play()
            }
        }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener { controller?.seekToNext() }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener { controller?.seekToPrevious() }

        connectController()
        requestAccess()
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    // ---- permissions & library ------------------------------------------------------------

    private fun requestAccess() {
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
            return
        }
        val perms = mutableListOf(audioPerm)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
        } else {
            empty.text = "Allow audio access in Settings to see your music."
        }
    }

    private fun loadTracks() {
        Thread {
            val found = queryAudio()
            runOnUiThread {
                items = found
                adapter.notifyDataSetChanged()
                if (found.isEmpty()) empty.text = "No music found on this phone."
                refresh()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun queryAudio(): List<MediaItem> {
        val base = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        // Everything audible except ringtones, notification sounds and alarms.
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val out = ArrayList<MediaItem>()
        contentResolver.query(base, proj, sel, null, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: ""
                val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val format = name.substringAfterLast('.', "").uppercase()
                val sub = listOfNotNull(format.ifEmpty { null }, artist).joinToString(" · ")
                out.add(
                    MediaItem.Builder()
                        .setMediaId(id.toString())
                        .setUri(ContentUris.withAppendedId(base, id))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(c.getString(iTitle) ?: name)
                                .setArtist(artist)
                                .setSubtitle(sub)
                                .build()
                        )
                        .build()
                )
            }
        }
        return out
    }

    // ---- playback --------------------------------------------------------------------------

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also {
                it.addListener(playerListener)
                showVolume(it.volume)
            }
            refresh()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playIndex(index: Int) {
        val c = controller ?: return
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    // ---- UI sync ---------------------------------------------------------------------------

    private fun showVolume(v: Float) {
        val pct = Math.round(v * 100)
        volBar.progress = pct
        volLabel.text = "$pct%"
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        nowTitle.text = item?.mediaMetadata?.title ?: "Nothing playing"
        nowSub.text = item?.mediaMetadata?.subtitle ?: ""
        playBtn.setImageResource(
            if (c.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        if (adapter.current != item?.mediaId) {
            adapter.current = item?.mediaId
            adapter.notifyDataSetChanged()
        }
        updateProgress()
    }

    private fun updateProgress() {
        val c = controller ?: return
        if (seeking) return
        val dur = c.duration
        if (dur == C.TIME_UNSET || dur <= 0) {
            seekBar.max = 1
            seekBar.progress = 0
        } else {
            seekBar.max = dur.toInt()
            seekBar.progress = c.currentPosition.toInt()
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        var current: String? = null

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = items[position]
            val t1 = row.findViewById<TextView>(android.R.id.text1)
            val t2 = row.findViewById<TextView>(android.R.id.text2)
            t1.text = item.mediaMetadata.title
            t2.text = item.mediaMetadata.subtitle
            val on = item.mediaId == current
            t1.setTextColor(if (on) 0xFFE8C26A.toInt() else 0xFFEEEEEE.toInt())
            t2.setTextColor(0xFF8A8A8A.toInt())
            return row
        }
    }
}
