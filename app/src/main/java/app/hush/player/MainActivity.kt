package app.hush.player

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("hush", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var favs: MutableSet<String> = mutableSetOf()
    private var tab = 0
    private var query = ""
    private var lastLoad = 0L

    private var started = false
    private var playerOpen = false
    private var sheetOpen = false
    private var artFor: String? = null
    private var chipKey = ""
    private var spin: ObjectAnimator? = null

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = TrackAdapter()

    private lateinit var root: View
    private lateinit var logo: DotTextView
    private lateinit var statusLine: TextView
    private lateinit var volBarLib: DotSlider
    private lateinit var volLabelLib: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabLiked: TextView
    private lateinit var shuffleAll: TextView
    private lateinit var searchBtn: ImageButton
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var mini: View
    private lateinit var miniDisc: DiscView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton

    private lateinit var playerRoot: View
    private lateinit var disc: DiscView
    private lateinit var backBtn: ImageButton
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var heartBtn: ImageButton
    private lateinit var chips: LinearLayout
    private lateinit var seek: DotSlider
    private lateinit var timeNow: TextView
    private lateinit var timeLeft: TextView
    private lateinit var shuffleBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var volBarPl: DotSlider
    private lateinit var volLabelPl: TextView
    private lateinit var speedChip: TextView
    private lateinit var sleepChip: TextView

    private lateinit var sheetRoot: View
    private lateinit var sheet: View
    private lateinit var scrim: View
    private lateinit var speedDots: DotTextView
    private lateinit var speedBar: DotSlider
    private lateinit var presets: LinearLayout
    private lateinit var pitchRow: LinearLayout
    private lateinit var pitchHint: TextView
    private lateinit var sleepInfo: TextView
    private lateinit var sleepRow: LinearLayout

    private val white get() = getColor(R.color.text)
    private val gray get() = getColor(R.color.sub)
    private val dim get() = getColor(R.color.dim)
    private val red get() = getColor(R.color.red)

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val audioBase: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private fun <T : View> v(id: Int): T = findViewById(id)

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        favs = (prefs.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()

        logo.text = "HUSH"
        logo.configure(3.6f, white)
        speedDots.configure(3.2f, white)
        updateStatus()
        styleTabs()

        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, pos, _ -> playFrom(pos) }

        // volume (both sliders control the same thing)
        showVolume(prefs.getFloat("volume", 1f))
        val onVol: (Float, Boolean) -> Unit = { f, _ ->
            controller?.volume = f
            setVolumeLabels(f)
        }
        volBarLib.onChange = onVol
        volBarPl.onChange = onVol

        // seek
        seek.onChange = { f, final ->
            val c = controller
            val dur = durationOf(c)
            if (c != null && dur > 0) {
                val pos = (f * dur).toLong()
                timeNow.text = mmss(pos)
                timeLeft.text = "-" + mmss(dur - pos)
                if (final) c.seekTo(pos)
            }
        }

        // transport
        val toggle = View.OnClickListener {
            val c = controller ?: return@OnClickListener
            when {
                c.isPlaying -> c.pause()
                c.mediaItemCount == 0 -> if (shown.isNotEmpty()) playFrom(0)
                else -> c.play()
            }
        }
        playBtn.setOnClickListener(toggle)
        miniPlay.setOnClickListener(toggle)
        nextBtn.setOnClickListener { controller?.seekToNext() }
        prevBtn.setOnClickListener { controller?.seekToPrevious() }
        shuffleBtn.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeatBtn.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        heartBtn.setOnClickListener {
            controller?.currentMediaItem?.let { toggleLike(it.mediaId) }
        }

        // navigation
        mini.setOnClickListener { openPlayer() }
        backBtn.setOnClickListener { closePlayer() }
        nowTitle.isSelected = true // start marquee

        // library controls
        tabAll.setOnClickListener { tab = 0; styleTabs(); applyFilter() }
        tabLiked.setOnClickListener { tab = 1; styleTabs(); applyFilter() }
        shuffleAll.setOnClickListener {
            if (shown.isNotEmpty()) playFrom((Math.random() * shown.size).toInt(), shuffle = true)
        }
        searchBtn.setOnClickListener { toggleSearch() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
        })

        // sheet
        scrim.setOnClickListener { closeSheet() }
        speedChip.setOnClickListener { openSheet() }
        sleepChip.setOnClickListener { openSheet() }
        buildSheetChips()
        speedBar.onChange = { f, _ ->
            val snapped = Math.round((0.5f + 1.5f * f) / 0.05f) * 0.05f
            applySpeed(snapped)
            speedBar.fraction = (snapped.coerceIn(0.5f, 2f) - 0.5f) / 1.5f
        }

        disc.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        connectController()
        requestAccess()
    }

    private fun bindViews() {
        root = v(R.id.root)
        logo = v(R.id.logo)
        statusLine = v(R.id.statusLine)
        volBarLib = v(R.id.volBarLib)
        volLabelLib = v(R.id.volLabelLib)
        tabAll = v(R.id.tabAll)
        tabLiked = v(R.id.tabLiked)
        shuffleAll = v(R.id.shuffleAll)
        searchBtn = v(R.id.searchBtn)
        search = v(R.id.search)
        list = v(R.id.list)
        empty = v(R.id.empty)
        mini = v(R.id.mini)
        miniDisc = v(R.id.miniDisc)
        miniTitle = v(R.id.miniTitle)
        miniSub = v(R.id.miniSub)
        miniPlay = v(R.id.miniPlay)
        playerRoot = v(R.id.player)
        disc = v(R.id.disc)
        backBtn = v(R.id.backBtn)
        nowTitle = v(R.id.nowTitle)
        nowSub = v(R.id.nowSub)
        heartBtn = v(R.id.heartBtn)
        chips = v(R.id.chips)
        seek = v(R.id.seek)
        timeNow = v(R.id.timeNow)
        timeLeft = v(R.id.timeLeft)
        shuffleBtn = v(R.id.shuffleBtn)
        prevBtn = v(R.id.prevBtn)
        playBtn = v(R.id.playBtn)
        nextBtn = v(R.id.nextBtn)
        repeatBtn = v(R.id.repeatBtn)
        volBarPl = v(R.id.volBarPl)
        volLabelPl = v(R.id.volLabelPl)
        speedChip = v(R.id.speedChip)
        sleepChip = v(R.id.sleepChip)
        sheetRoot = v(R.id.sheetRoot)
        sheet = v(R.id.sheet)
        scrim = v(R.id.scrim)
        speedDots = v(R.id.speedDots)
        speedBar = v(R.id.speedBar)
        presets = v(R.id.presets)
        pitchRow = v(R.id.pitchRow)
        pitchHint = v(R.id.pitchHint)
        sleepInfo = v(R.id.sleepInfo)
        sleepRow = v(R.id.sleepRow)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED &&
            System.currentTimeMillis() - lastLoad > 30_000
        ) loadTracks()
        refresh()
    }

    override fun onStop() {
        started = false
        stopTicker()
        updateSpin(false)
        super.onStop()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheetOpen -> closeSheet()
            playerOpen -> closePlayer()
            search.visibility == View.VISIBLE -> toggleSearch()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------------------------------
    // permissions + library
    // ------------------------------------------------------------------------------------------

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
            empty.text = "ALLOW MUSIC ACCESS IN SETTINGS\nTO SEE YOUR SONGS"
        }
    }

    private fun loadTracks() {
        lastLoad = System.currentTimeMillis()
        io.execute {
            val found = try { queryAudio() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                all = found
                updateStatus()
                applyFilter()
                refresh()
            }
        }
    }

    private val losslessFormats = setOf("FLAC", "WAV", "AIFF", "AIF", "ALAC", "APE", "WV", "DSF", "DFF")

    @Suppress("DEPRECATION")
    private fun queryAudio(): List<MediaItem> {
        val base = audioBase
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

        val out = ArrayList<MediaItem>()
        contentResolver.query(base, proj, sel, null, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: ""
                val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val format = name.substringAfterLast('.', "").uppercase()
                val dur = c.getLong(iDur)
                val size = c.getLong(iSize)
                val kbps = if (dur > 0) (size * 8L / dur).toInt() else 0 // bits per ms == kbps
                val albumId = c.getLong(iAlbum)

                val extras = Bundle().apply {
                    putString("fmt", format)
                    putInt("kbps", kbps)
                    putBoolean("lossless", format in losslessFormats)
                }
                val meta = MediaMetadata.Builder()
                    .setTitle(c.getString(iTitle) ?: name)
                    .setArtist(artist)
                    .setDurationMs(dur)
                    .setExtras(extras)
                if (albumId > 0) meta.setArtworkUri(ContentUris.withAppendedId(albumArtBase, albumId))

                out.add(
                    MediaItem.Builder()
                        .setMediaId(id.toString())
                        .setUri(ContentUris.withAppendedId(base, id))
                        .setMediaMetadata(meta.build())
                        .build()
                )
            }
        }
        return out
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        shown = all.filter {
            (tab == 0 || favs.contains(it.mediaId)) &&
                (q.isEmpty() ||
                    (it.mediaMetadata.title?.toString() ?: "").lowercase().contains(q) ||
                    (it.mediaMetadata.artist?.toString() ?: "").lowercase().contains(q))
        }
        adapter.notifyDataSetChanged()
        if (all.isNotEmpty()) {
            empty.text = if (tab == 1 && q.isEmpty()) "NO LIKED SONGS YET\nTAP THE HEART WHILE PLAYING" else "NOTHING FOUND"
        } else if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED && lastLoad > 0) {
            empty.text = "NO MUSIC FOUND ON THIS PHONE"
        }
    }

    private fun updateStatus() {
        val s = SpannableStringBuilder("●")
        s.setSpan(ForegroundColorSpan(red), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append("  OFFLINE  ·  ${all.size} TRACKS")
        statusLine.text = s
    }

    private fun styleTabs() {
        tabAll.setTextColor(if (tab == 0) white else dim)
        tabLiked.setTextColor(if (tab == 1) white else dim)
    }

    private fun toggleSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
            imm.hideSoftInputFromWindow(search.windowToken, 0)
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
            imm.showSoftInput(search, 0)
        }
    }

    private fun toggleLike(id: String) {
        if (!favs.add(id)) favs.remove(id)
        prefs.edit().putStringSet("fav", HashSet(favs)).apply()
        if (tab == 1) applyFilter() else adapter.notifyDataSetChanged()
        refresh()
    }

    // ------------------------------------------------------------------------------------------
    // playback
    // ------------------------------------------------------------------------------------------

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            refresh()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playFrom(index: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (shown.isEmpty() || index !in shown.indices) return
        if (shuffle) c.shuffleModeEnabled = true
        c.setMediaItems(shown, index, 0L)
        c.prepare()
        c.play()
        openPlayer()
    }

    private fun applySpeed(speed: Float) {
        val c = controller ?: return
        val s = speed.coerceIn(0.5f, 2f)
        val pitch = if (prefs.getBoolean("pitchUp", false)) s else 1f
        c.playbackParameters = PlaybackParameters(s, pitch)
    }

    private fun setSleep(minutes: Int) {
        prefs.edit().putLong(
            "sleepEnd",
            if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        ).apply()
        startService(
            Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_SLEEP)
                .putExtra(PlaybackService.EXTRA_MINUTES, minutes)
        )
        refreshSheet()
        refresh()
    }

    // ------------------------------------------------------------------------------------------
    // screens
    // ------------------------------------------------------------------------------------------

    private fun openPlayer() {
        if (playerOpen) return
        playerOpen = true
        playerRoot.animate().cancel()
        playerRoot.visibility = View.VISIBLE
        playerRoot.translationY = resources.displayMetrics.heightPixels.toFloat()
        playerRoot.animate().translationY(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
        refresh()
    }

    private fun closePlayer() {
        if (!playerOpen) return
        playerOpen = false
        playerRoot.animate().cancel()
        playerRoot.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(200).withEndAction { playerRoot.visibility = View.GONE }.start()
        updateSpin(false)
        refresh()
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        refreshSheet()
        sheetRoot.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        sheet.translationY = 520f * density
        sheet.animate().translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        scrim.animate().alpha(0f).setDuration(160).start()
        sheet.animate().translationY(520f * density).setDuration(180)
            .withEndAction { sheetRoot.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------------------------------
    // sheet content
    // ------------------------------------------------------------------------------------------

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.typeface = android.graphics.Typeface.MONOSPACE
        t.textSize = 12f
        t.letterSpacing = 0.06f
        t.setPadding((14 * density).toInt(), (9 * density).toInt(), (14 * density).toInt(), (9 * density).toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = (8 * density).toInt()
        t.layoutParams = lp
        styleChip(t, on)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleChip(t: TextView, on: Boolean) {
        t.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        t.setTextColor(if (on) Color.BLACK else white)
    }

    private fun buildSheetChips() {
        for (s in floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
            presets.addView(chip(speedText(s), false) { applySpeed(s) }.also { it.tag = s })
        }
        pitchRow.addView(chip("KEEP PITCH", false) { setPitchUp(false) }.also { it.tag = "keep" })
        pitchRow.addView(chip("SPED-UP", false) { setPitchUp(true) }.also { it.tag = "up" })
        for (m in intArrayOf(0, 15, 30, 45, 60)) {
            sleepRow.addView(chip(if (m == 0) "OFF" else "${m}m", false) { setSleep(m) }.also { it.tag = m })
        }
    }

    private fun setPitchUp(up: Boolean) {
        prefs.edit().putBoolean("pitchUp", up).apply()
        controller?.let { applySpeed(it.playbackParameters.speed) }
        refreshSheet()
    }

    private fun refreshSheet() {
        val c = controller
        val speed = c?.playbackParameters?.speed ?: prefs.getFloat("speed", 1f)
        speedDots.text = String.format(Locale.US, "%.2fx", speed)
        if (!speedBar.isDragging) speedBar.fraction = (speed.coerceIn(0.5f, 2f) - 0.5f) / 1.5f
        for (i in 0 until presets.childCount) {
            val t = presets.getChildAt(i) as TextView
            styleChip(t, Math.abs((t.tag as Float) - speed) < 0.011f)
        }
        val up = prefs.getBoolean("pitchUp", false)
        for (i in 0 until pitchRow.childCount) {
            val t = pitchRow.getChildAt(i) as TextView
            styleChip(t, (t.tag == "up") == up)
        }
        pitchHint.text = if (up) "FASTER = HIGHER PITCH, LIKE A SPED-UP EDIT" else "FASTER, SAME VOICE & KEY"

        val remain = sleepRemainingMs()
        sleepInfo.text = if (remain > 0) "SLEEP TIMER  ·  ${mmss(remain)} LEFT" else "SLEEP TIMER"
        for (i in 0 until sleepRow.childCount) {
            val t = sleepRow.getChildAt(i) as TextView
            styleChip(t, remain <= 0 && (t.tag as Int) == 0)
        }
    }

    private fun sleepRemainingMs(): Long {
        val end = prefs.getLong("sleepEnd", 0L)
        return if (end > 0) maxOf(0L, end - System.currentTimeMillis()) else 0L
    }

    // ------------------------------------------------------------------------------------------
    // UI sync
    // ------------------------------------------------------------------------------------------

    private fun speedText(s: Float) = String.format(Locale.US, "%.2fx", s)

    private fun showVolume(v: Float) {
        if (!volBarLib.isDragging) volBarLib.fraction = v
        if (!volBarPl.isDragging) volBarPl.fraction = v
        setVolumeLabels(v)
    }

    private fun setVolumeLabels(v: Float) {
        val txt = "${Math.round(v * 100)}%"
        volLabelLib.text = txt
        volLabelPl.text = txt
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val md = item?.mediaMetadata
        val playing = c.isPlaying

        val title = md?.title?.toString() ?: "Nothing playing"
        val artist = md?.artist?.toString() ?: ""
        val fmt = md?.extras?.getString("fmt").orEmpty()
        nowTitle.text = title
        nowSub.text = artist.uppercase()
        miniTitle.text = title
        miniSub.text = listOf(fmt, artist.uppercase()).filter { it.isNotEmpty() }.joinToString(" · ")
        mini.visibility = if (item != null) View.VISIBLE else View.GONE

        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        playBtn.setImageResource(icon)
        playBtn.setColorFilter(Color.BLACK)
        miniPlay.setImageResource(icon)

        val liked = item != null && favs.contains(item.mediaId)
        heartBtn.setImageResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        heartBtn.setColorFilter(if (liked) red else white)

        shuffleBtn.setColorFilter(if (c.shuffleModeEnabled) red else dim)
        repeatBtn.setImageResource(
            if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        repeatBtn.setColorFilter(if (c.repeatMode == Player.REPEAT_MODE_OFF) dim else red)

        showVolume(c.volume)

        val speed = c.playbackParameters.speed
        speedChip.text = "SPEED " + speedText(speed)
        val remain = sleepRemainingMs()
        sleepChip.text = if (remain > 0) "SLEEP " + mmss(remain) else "SLEEP OFF"
        styleChip(speedChip, Math.abs(speed - 1f) > 0.01f)
        styleChip(sleepChip, remain > 0)
        if (sheetOpen) refreshSheet()

        buildChips(item, c)

        // album art for the disc label
        val id = item?.mediaId
        if (id != artFor) {
            artFor = id
            disc.art = null
            miniDisc.art = null
            if (id != null) loadArt(id)
        }

        updateSpin(playing)
        if (started && playing) startTicker() else stopTicker()
        updateProgress()
    }

    private fun buildChips(item: MediaItem?, c: MediaController) {
        val ex = item?.mediaMetadata?.extras
        val parts = ArrayList<String>()
        val fmt = ex?.getString("fmt").orEmpty()
        if (fmt.isNotEmpty()) parts.add(fmt)

        val af = c.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.getTrackFormat(0)
        if (af != null && af.sampleRate > 0) {
            val khz = af.sampleRate / 1000f
            parts.add(
                if (af.sampleRate % 1000 == 0) "${af.sampleRate / 1000} kHz"
                else String.format(Locale.US, "%.1f kHz", khz)
            )
        }
        when (af?.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> parts.add("16-bit")
            C.ENCODING_PCM_24BIT -> parts.add("24-bit")
            C.ENCODING_PCM_32BIT -> parts.add("32-bit")
            C.ENCODING_PCM_FLOAT -> parts.add("32-bit float")
            else -> {}
        }
        val kbps = ex?.getInt("kbps") ?: 0
        if (kbps > 0) parts.add("$kbps kbps")

        val lossless = ex?.getBoolean("lossless") ?: false
        val key = parts.joinToString("|") + lossless
        if (key == chipKey) return
        chipKey = key

        chips.removeAllViews()
        parts.forEachIndexed { i, p ->
            val t = TextView(this)
            t.text = p.uppercase()
            t.typeface = android.graphics.Typeface.MONOSPACE
            t.textSize = 10f
            t.letterSpacing = 0.05f
            t.maxLines = 1
            t.setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * density).toInt()
            t.layoutParams = lp
            styleChip(t, i == 0 && lossless)
            t.setTextColor(if (i == 0 && lossless) Color.BLACK else gray)
            chips.addView(t)
        }
    }

    private fun loadArt(id: String) {
        val uri = ContentUris.withAppendedId(audioBase, id.toLong())
        io.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(640, 640), null) else null
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (artFor == id) {
                    disc.art = bmp
                    miniDisc.art = bmp
                }
            }
        }
    }

    private fun updateSpin(playing: Boolean) {
        val want = playing && started && playerOpen
        val a = spin ?: ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
            duration = 14000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            spin = this
        }
        if (want) {
            if (!a.isStarted) a.start() else if (a.isPaused) a.resume()
        } else if (a.isStarted && !a.isPaused) {
            a.pause()
        }
    }

    private fun startTicker() {
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun stopTicker() {
        ui.removeCallbacks(ticker)
    }

    private fun durationOf(c: MediaController?): Long {
        if (c == null) return 0L
        val d = c.duration
        if (d != C.TIME_UNSET && d > 0) return d
        return c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
    }

    private fun updateProgress() {
        if (sheetOpen) refreshSheetSleepOnly()
        if (!playerOpen) return
        val c = controller ?: return
        val dur = durationOf(c)
        val pos = c.currentPosition.coerceAtLeast(0L)
        if (!seek.isDragging) {
            seek.fraction = if (dur > 0) pos.toFloat() / dur else 0f
            timeNow.text = mmss(pos)
            timeLeft.text = "-" + mmss((dur - pos).coerceAtLeast(0L))
        }
        val remain = sleepRemainingMs()
        sleepChip.text = if (remain > 0) "SLEEP " + mmss(remain) else "SLEEP OFF"
    }

    private fun refreshSheetSleepOnly() {
        val remain = sleepRemainingMs()
        sleepInfo.text = if (remain > 0) "SLEEP TIMER  ·  ${mmss(remain)} LEFT" else "SLEEP TIMER"
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------------------------------------
    // list adapter
    // ------------------------------------------------------------------------------------------

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row_track, parent, false)
            val item = shown[position]
            val md = item.mediaMetadata
            val idx = row.findViewById<TextView>(R.id.rowIdx)
            val title = row.findViewById<TextView>(R.id.rowTitle)
            val sub = row.findViewById<TextView>(R.id.rowSub)
            val dur = row.findViewById<TextView>(R.id.rowDur)

            val current = controller?.currentMediaItem?.mediaId == item.mediaId
            idx.text = if (current) "●" else String.format(Locale.US, "%02d", position + 1)
            idx.setTextColor(if (current) red else dim)
            title.text = md.title
            title.setTextColor(if (current) red else white)

            val fmt = md.extras?.getString("fmt").orEmpty()
            val lossless = md.extras?.getBoolean("lossless") ?: false
            val artist = md.artist?.toString()?.uppercase().orEmpty()
            val s = SpannableStringBuilder()
            if (fmt.isNotEmpty()) {
                s.append(fmt)
                s.setSpan(
                    ForegroundColorSpan(if (lossless) white else gray),
                    0, fmt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (artist.isNotEmpty()) {
                if (s.isNotEmpty()) s.append(" · ")
                s.append(artist)
            }
            sub.text = s
            dur.text = mmss(md.durationMs ?: 0L)
            return row
        }
    }
}
