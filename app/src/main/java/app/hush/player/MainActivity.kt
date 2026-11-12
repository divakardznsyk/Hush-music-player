package app.hush.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    // Deck A = the main player. Deck B = a second player, connected only when first used.
    private var futureA: ListenableFuture<MediaController>? = null
    private var ctrlA: MediaController? = null
    private var futureB: ListenableFuture<MediaController>? = null
    private var ctrlB: MediaController? = null
    private val pendingB = ArrayList<(MediaController) -> Unit>()

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var favs: MutableSet<String> = mutableSetOf()
    private var tab = 0
    private var query = ""
    private var lastLoad = 0L

    private var started = false
    private var playerOpen = false
    private var deckOpen = false
    private var sheetOpen = false
    private var sheetPage = 0
    private var pickingForB = false

    private var currentIdA: String? = null
    private var artIdA: String? = null
    private var artIdB: String? = null
    private var fmtA = ""
    private var fmtB = ""

    private var accent = 0xFFD71921.toInt()
    private var onAccent = Color.WHITE

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = TrackAdapter()

    // library
    private lateinit var libCount: TextView
    private lateinit var offlineChip: TextView
    private lateinit var searchBtn: ImageButton
    private lateinit var volBarLib: LineSlider
    private lateinit var volLabelLib: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabLiked: TextView
    private lateinit var shuffleAll: TextView
    private lateinit var pickBanner: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var mini: View
    private lateinit var miniDisc: DiscView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton

    // deck A
    private lateinit var playerRoot: View
    private lateinit var backBtn: ImageButton
    private lateinit var bulletA: TextView
    private lateinit var infoText: TextView
    private lateinit var menuBtn: ImageButton
    private lateinit var disc: DiscView
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var heartBtn: ImageButton
    private lateinit var fmtLine: TextView
    private lateinit var seek: LineSlider
    private lateinit var timeNow: TextView
    private lateinit var timeLeft: TextView
    private lateinit var volBarPl: LineSlider
    private lateinit var volLabelPl: TextView
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var nextBtn: ImageButton

    // deck B
    private lateinit var deckRoot: View
    private lateinit var deckBack: ImageButton
    private lateinit var bulletB: TextView
    private lateinit var deckDisc: DiscView
    private lateinit var deckTitle: TextView
    private lateinit var deckSub: TextView
    private lateinit var deckFmt: TextView
    private lateinit var deckSeek: LineSlider
    private lateinit var deckTimeNow: TextView
    private lateinit var deckTimeLeft: TextView
    private lateinit var deckVol: LineSlider
    private lateinit var deckVolLabel: TextView
    private lateinit var deckPick: TextView
    private lateinit var deckPlay: ImageButton
    private lateinit var deckEject: TextView

    // sheet
    private lateinit var sheetRoot: View
    private lateinit var sheet: View
    private lateinit var scrim: View
    private lateinit var pageMenu: LinearLayout
    private lateinit var pageSpeed: View
    private lateinit var pageSleep: View
    private lateinit var pageAccent: View
    private lateinit var speedBig: TextView
    private lateinit var speedBar: LineSlider
    private lateinit var presets: LinearLayout
    private lateinit var sleepBig: TextView
    private lateinit var sleepRow: LinearLayout
    private lateinit var accentRow: LinearLayout
    private lateinit var menuSpeedVal: TextView
    private lateinit var menuSleepVal: TextView
    private lateinit var menuDeckVal: TextView
    private lateinit var menuAccentDot: View

    private val white get() = getColor(R.color.text)
    private val gray get() = getColor(R.color.sub)
    private val dim get() = getColor(R.color.dim)

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val accents = intArrayOf(
        0xFFD71921.toInt(), // red
        0xFFFF6319.toInt(), // orange
        0xFFFCCC0A.toInt(), // yellow
        0xFF00A859.toInt(), // green
        0xFFF2F2F2.toInt()  // white
    )

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    private val artRunA = Runnable {
        val id = currentIdA
        if (id != artIdA) {
            artIdA = id
            disc.art = null
            miniDisc.art = null
            if (id != null) loadArt(id) { bmp ->
                if (artIdA == id) {
                    disc.art = bmp
                    miniDisc.art = bmp
                }
            }
        }
    }

    // Each listener reacts only to the events that matter, so nothing is rebuilt needlessly.
    private val listenerA = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) onTrackA()
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) updateFormatA()
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )
            ) onPlayStateA()
            if (events.contains(Player.EVENT_VOLUME_CHANGED)) showVolumeA(player.volume)
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) updateSpeedUi()
            if (events.containsAny(
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED
                )
            ) updateModes()
        }
    }

    private val listenerB = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) onTrackB()
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) updateFormatB()
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )
            ) onPlayStateB()
            if (events.contains(Player.EVENT_VOLUME_CHANGED)) showVolumeB(player.volume)
        }
    }

    private fun <T : View> v(id: Int): T = findViewById(id)
    private fun dp(x: Int) = (x * density).toInt()

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadAccent()
        favs = (prefs.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()

        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pickingForB) pickForB(pos) else playFrom(pos)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in shown.indices) loadDeckB(shown[pos])
            true
        }

        // app volume (both sliders control Deck A's volume; ticks every 5%)
        volBarLib.hapticSteps = 20
        volBarPl.hapticSteps = 20
        deckVol.hapticSteps = 20
        showVolumeA(prefs.getFloat("volume", 1f))
        showVolumeB(prefs.getFloat("volumeB", 1f))
        val onVolA: (Float, Boolean) -> Unit = { f, _ ->
            ctrlA?.volume = f
            setVolLabelsA(f)
        }
        volBarLib.onChange = onVolA
        volBarPl.onChange = onVolA
        deckVol.onChange = { f, _ ->
            ctrlB?.volume = f
            deckVolLabel.text = pct(f)
        }

        seek.onChange = { f, final -> seekTo(ctrlA, f, final, timeNow, timeLeft) }
        deckSeek.onChange = { f, final -> seekTo(ctrlB, f, final, deckTimeNow, deckTimeLeft) }

        // deck A controls
        val toggleA = View.OnClickListener {
            val c = ctrlA ?: return@OnClickListener
            when {
                c.isPlaying -> c.pause()
                c.mediaItemCount == 0 -> if (shown.isNotEmpty()) playFrom(0)
                else -> c.play()
            }
        }
        playBtn.setOnClickListener(toggleA)
        miniPlay.setOnClickListener(toggleA)
        nextBtn.setOnClickListener { ctrlA?.seekToNext() }
        prevBtn.setOnClickListener { ctrlA?.seekToPrevious() }
        shuffleBtn.setOnClickListener { ctrlA?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeatBtn.setOnClickListener {
            ctrlA?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        heartBtn.setOnClickListener { ctrlA?.currentMediaItem?.let { toggleLike(it.mediaId) } }
        nowTitle.isSelected = true // start marquee for long titles

        // deck B controls
        deckPlay.setOnClickListener {
            val c = ctrlB
            when {
                c == null || c.mediaItemCount == 0 -> startPick()
                c.isPlaying -> c.pause()
                else -> c.play()
            }
        }
        deckPick.setOnClickListener { startPick() }
        deckEject.setOnClickListener {
            ctrlB?.let {
                it.stop()
                it.clearMediaItems()
            }
        }

        // navigation
        mini.setOnClickListener { openPlayer() }
        backBtn.setOnClickListener { closePlayer() }
        deckBack.setOnClickListener { closeDeck() }
        menuBtn.setOnClickListener { openSheet() }
        pickBanner.setOnClickListener { cancelPick() }

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
        v<View>(R.id.speedBack).setOnClickListener { showPage(0) }
        v<View>(R.id.sleepBack).setOnClickListener { showPage(0) }
        v<View>(R.id.accentBack).setOnClickListener { showPage(0) }
        speedBar.hapticSteps = 30
        speedBar.ticks = floatArrayOf((1f - 0.5f) / 1.5f)
        speedBar.onChange = { f, _ ->
            val snapped = Math.round((0.5f + 1.5f * f) / 0.05f) * 0.05f
            applySpeed(snapped)
            speedBar.fraction = (snapped.coerceIn(0.5f, 2f) - 0.5f) / 1.5f
        }
        buildMenu()
        buildSheetChips()

        applyAccent()
        connectA()
        requestAccess()
    }

    private fun bindViews() {
        libCount = v(R.id.libCount)
        offlineChip = v(R.id.offlineChip)
        searchBtn = v(R.id.searchBtn)
        volBarLib = v(R.id.volBarLib)
        volLabelLib = v(R.id.volLabelLib)
        tabAll = v(R.id.tabAll)
        tabLiked = v(R.id.tabLiked)
        shuffleAll = v(R.id.shuffleAll)
        pickBanner = v(R.id.pickBanner)
        search = v(R.id.search)
        list = v(R.id.list)
        empty = v(R.id.empty)
        mini = v(R.id.mini)
        miniDisc = v(R.id.miniDisc)
        miniTitle = v(R.id.miniTitle)
        miniSub = v(R.id.miniSub)
        miniPlay = v(R.id.miniPlay)

        playerRoot = v(R.id.player)
        backBtn = v(R.id.backBtn)
        bulletA = v(R.id.bulletA)
        infoText = v(R.id.infoText)
        menuBtn = v(R.id.menuBtn)
        disc = v(R.id.disc)
        nowTitle = v(R.id.nowTitle)
        nowSub = v(R.id.nowSub)
        heartBtn = v(R.id.heartBtn)
        fmtLine = v(R.id.fmtLine)
        seek = v(R.id.seek)
        timeNow = v(R.id.timeNow)
        timeLeft = v(R.id.timeLeft)
        volBarPl = v(R.id.volBarPl)
        volLabelPl = v(R.id.volLabelPl)
        shuffleBtn = v(R.id.shuffleBtn)
        repeatBtn = v(R.id.repeatBtn)
        prevBtn = v(R.id.prevBtn)
        playBtn = v(R.id.playBtn)
        nextBtn = v(R.id.nextBtn)

        deckRoot = v(R.id.deck)
        deckBack = v(R.id.deckBack)
        bulletB = v(R.id.bulletB)
        deckDisc = v(R.id.deckDisc)
        deckTitle = v(R.id.deckTitle)
        deckSub = v(R.id.deckSub)
        deckFmt = v(R.id.deckFmt)
        deckSeek = v(R.id.deckSeek)
        deckTimeNow = v(R.id.deckTimeNow)
        deckTimeLeft = v(R.id.deckTimeLeft)
        deckVol = v(R.id.deckVol)
        deckVolLabel = v(R.id.deckVolLabel)
        deckPick = v(R.id.deckPick)
        deckPlay = v(R.id.deckPlay)
        deckEject = v(R.id.deckEject)

        sheetRoot = v(R.id.sheetRoot)
        sheet = v(R.id.sheet)
        scrim = v(R.id.scrim)
        pageMenu = v(R.id.pageMenu)
        pageSpeed = v(R.id.pageSpeed)
        pageSleep = v(R.id.pageSleep)
        pageAccent = v(R.id.pageAccent)
        speedBig = v(R.id.speedBig)
        speedBar = v(R.id.speedBar)
        presets = v(R.id.presets)
        sleepBig = v(R.id.sleepBig)
        sleepRow = v(R.id.sleepRow)
        accentRow = v(R.id.accentRow)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED &&
            System.currentTimeMillis() - lastLoad > 30_000
        ) loadTracks()
        updateTicker()
    }

    override fun onStop() {
        started = false
        ui.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        ctrlA?.removeListener(listenerA)
        ctrlB?.removeListener(listenerB)
        futureA?.let { MediaController.releaseFuture(it) }
        futureB?.let { MediaController.releaseFuture(it) }
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheetOpen -> if (sheetPage != 0) showPage(0) else closeSheet()
            deckOpen -> closeDeck()
            playerOpen -> closePlayer()
            pickingForB -> cancelPick()
            search.visibility == View.VISIBLE -> toggleSearch()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------------------------------
    // theme / helpers
    // ------------------------------------------------------------------------------------------

    private fun loadAccent() {
        accent = prefs.getInt("accent", accents[0])
        onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
    }

    private fun rect(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun oval(color: Int, strokeColor: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeDp > 0f) setStroke((strokeDp * density).toInt(), strokeColor)
    }

    /** Repaints everything that uses the accent colour. */
    private fun applyAccent() {
        for (s in listOf(volBarLib, volBarPl, deckVol, seek, deckSeek, speedBar)) s.setAccent(accent)
        disc.labelColor = accent
        miniDisc.labelColor = accent
        deckDisc.labelColor = accent

        playBtn.background = rect(accent, 24f)
        playBtn.setColorFilter(onAccent)
        deckPlay.background = rect(accent, 24f)
        deckPlay.setColorFilter(onAccent)
        miniPlay.background = oval(accent)
        miniPlay.setColorFilter(onAccent)
        pickBanner.background = rect(accent, 16f)
        pickBanner.setTextColor(onAccent)

        bulletA.background = oval(accent)
        bulletA.setTextColor(onAccent)
        bulletB.background = oval(white)
        bulletB.setTextColor(Color.BLACK)

        styleTabs()
        updateModes()
        updateHeart()
        updateSpeedUi()
        updateSleepUi()
        refreshAccentRow()
        menuAccentDot.background = oval(accent)
        adapter.notifyDataSetChanged()
        HushWidget.refresh(this)
    }

    private fun styleTabs() {
        styleChip(tabAll, tab == 0)
        styleChip(tabLiked, tab == 1)
    }

    private fun styleChip(t: TextView, on: Boolean) {
        if (on) {
            t.background = rect(accent, 100f)
            t.setTextColor(onAccent)
        } else {
            t.setBackgroundResource(R.drawable.bg_chip)
            t.setTextColor(white)
        }
    }

    private fun pct(v: Float) = "${Math.round(v * 100)}%"

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun seekTo(c: MediaController?, f: Float, final: Boolean, now: TextView, left: TextView) {
        val dur = durationOf(c)
        if (c != null && dur > 0) {
            val pos = (f * dur).toLong()
            now.text = mmss(pos)
            left.text = "-" + mmss(dur - pos)
            if (final) c.seekTo(pos)
        }
    }

    private fun durationOf(c: MediaController?): Long {
        if (c == null) return 0L
        val d = c.duration
        if (d != C.TIME_UNSET && d > 0) return d
        return c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
    }

    private fun titleOf(item: MediaItem?) = item?.mediaMetadata?.title?.toString() ?: ""

    private fun artistOf(item: MediaItem?): String =
        item?.mediaMetadata?.extras?.getString("artist")
            ?: item?.mediaMetadata?.artist?.toString()?.removePrefix("DECK B · ")
            ?: ""

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
            empty.text = "Allow music access in Settings to see your songs"
        }
    }

    private fun loadTracks() {
        lastLoad = System.currentTimeMillis()
        io.execute {
            val found = try { Library.query(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                all = found
                libCount.text = "${all.size} songs"
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        shown = all.filter {
            (tab == 0 || favs.contains(it.mediaId)) &&
                (q.isEmpty() ||
                    titleOf(it).lowercase().contains(q) ||
                    artistOf(it).lowercase().contains(q))
        }
        adapter.notifyDataSetChanged()
        empty.text = when {
            all.isEmpty() -> if (lastLoad > 0) "No music found on this phone" else "Looking for music…"
            tab == 1 && q.isEmpty() -> "No liked songs yet.\nTap the heart while playing."
            else -> "Nothing found"
        }
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
        updateHeart()
        if (tab == 1) applyFilter()
    }

    // ------------------------------------------------------------------------------------------
    // connecting + playing
    // ------------------------------------------------------------------------------------------

    private fun connectA() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        futureA = future
        future.addListener({
            ctrlA = future.get().also { it.addListener(listenerA) }
            onTrackA()
            onPlayStateA()
            updateModes()
            updateSpeedUi()
            showVolumeA(ctrlA?.volume ?: 1f)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Deck B's player only exists once you ask for it. */
    private fun withDeckB(action: (MediaController) -> Unit) {
        ctrlB?.let { action(it); return }
        pendingB.add(action)
        if (futureB != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val hints = Bundle().apply { putString("deck", "B") }
        val future = MediaController.Builder(this, token).setConnectionHints(hints).buildAsync()
        futureB = future
        future.addListener({
            val c = future.get().also { it.addListener(listenerB) }
            ctrlB = c
            onTrackB()
            onPlayStateB()
            showVolumeB(c.volume)
            val todo = ArrayList(pendingB)
            pendingB.clear()
            todo.forEach { it(c) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playFrom(index: Int, shuffle: Boolean = false) {
        val c = ctrlA ?: return
        if (shown.isEmpty() || index !in shown.indices) return
        if (shuffle) c.shuffleModeEnabled = true
        c.setMediaItems(shown, index, 0L)
        c.prepare()
        c.play()
        openPlayer()
    }

    private fun loadDeckB(item: MediaItem) {
        val tagged = item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon().setArtist("DECK B · " + artistOf(item)).build()
            )
            .build()
        withDeckB { c ->
            c.repeatMode = Player.REPEAT_MODE_ONE // loops until you pause or change it
            c.setMediaItem(tagged)
            c.prepare()
            c.play()
        }
        if (playerOpen) closePlayer()
        openDeck()
    }

    private fun startPick() {
        pickingForB = true
        pickBanner.visibility = View.VISIBLE
        if (deckOpen) closeDeck()
        if (playerOpen) closePlayer()
    }

    private fun cancelPick() {
        pickingForB = false
        pickBanner.visibility = View.GONE
    }

    private fun pickForB(pos: Int) {
        if (pos !in shown.indices) return
        cancelPick()
        loadDeckB(shown[pos])
    }

    private fun applySpeed(speed: Float) {
        val c = ctrlA ?: return
        val s = speed.coerceIn(0.5f, 2f)
        c.playbackParameters = PlaybackParameters(s, 1f)
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
        updateSleepUi()
    }

    private fun sleepRemainingMs(): Long {
        val end = prefs.getLong("sleepEnd", 0L)
        return if (end > 0) maxOf(0L, end - System.currentTimeMillis()) else 0L
    }

    // ------------------------------------------------------------------------------------------
    // screens (quick slides, nothing else animates)
    // ------------------------------------------------------------------------------------------

    private fun slideIn(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.translationY = resources.displayMetrics.heightPixels.toFloat()
        view.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun slideOut(view: View) {
        view.animate().cancel()
        view.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(180).withEndAction { view.visibility = View.GONE }.start()
    }

    private fun openPlayer() {
        if (playerOpen) return
        playerOpen = true
        slideIn(playerRoot)
        updateProgress()
        updateTicker()
    }

    private fun closePlayer() {
        if (!playerOpen) return
        playerOpen = false
        slideOut(playerRoot)
        updateTicker()
    }

    private fun openDeck() {
        if (deckOpen) return
        deckOpen = true
        slideIn(deckRoot)
        updateProgress()
        updateTicker()
    }

    private fun closeDeck() {
        if (!deckOpen) return
        deckOpen = false
        slideOut(deckRoot)
        updateTicker()
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        showPage(0)
        sheetRoot.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        sheet.translationY = 520f * density
        sheet.animate().translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        updateTicker()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        scrim.animate().alpha(0f).setDuration(140).start()
        sheet.animate().translationY(520f * density).setDuration(170)
            .withEndAction { sheetRoot.visibility = View.GONE }.start()
        updateTicker()
    }

    private fun showPage(page: Int) {
        sheetPage = page
        pageMenu.visibility = if (page == 0) View.VISIBLE else View.GONE
        pageSpeed.visibility = if (page == 1) View.VISIBLE else View.GONE
        pageSleep.visibility = if (page == 2) View.VISIBLE else View.GONE
        pageAccent.visibility = if (page == 3) View.VISIBLE else View.GONE
        updateSpeedUi()
        updateSleepUi()
    }

    // ------------------------------------------------------------------------------------------
    // sheet content
    // ------------------------------------------------------------------------------------------

    private fun menuRow(num: String, title: String, value: View, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
        }
        val bullet = TextView(this).apply {
            text = num
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 16f
            setTextColor(Color.BLACK)
            background = oval(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val label = TextView(this).apply {
            text = title
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 22f
            setTextColor(white)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        }
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(dim)
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(bullet)
        row.addView(label)
        row.addView(value)
        row.addView(chevron)
        return row
    }

    private fun valueText() = TextView(this).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f
        setTextColor(gray)
    }

    private fun buildMenu() {
        menuSpeedVal = valueText()
        menuSleepVal = valueText()
        menuDeckVal = valueText()
        menuAccentDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)) }
        pageMenu.addView(menuRow("1", "Speed", menuSpeedVal) { showPage(1) })
        pageMenu.addView(menuRow("2", "Sleep timer", menuSleepVal) { showPage(2) })
        pageMenu.addView(menuRow("3", "Deck B", menuDeckVal) {
            closeSheet()
            if ((ctrlB?.mediaItemCount ?: 0) > 0) openDeck() else startPick()
        })
        pageMenu.addView(menuRow("4", "Accent colour", menuAccentDot) { showPage(3) })
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        t.textSize = 14f
        t.setPadding(dp(16), dp(11), dp(16), dp(11))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(8)
        t.layoutParams = lp
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildSheetChips() {
        for (s in floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
            presets.addView(chip(String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.') + "x") { applySpeed(s) }.also { it.tag = s })
        }
        for (m in intArrayOf(0, 15, 30, 45, 60)) {
            sleepRow.addView(chip(if (m == 0) "OFF" else "${m}m") { setSleep(m) }.also { it.tag = m })
        }
        for (color in accents) {
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) }
            dot.tag = color
            dot.setOnClickListener {
                prefs.edit().putInt("accent", color).apply()
                loadAccent()
                applyAccent()
            }
            accentRow.addView(dot)
        }
    }

    private fun refreshAccentRow() {
        for (i in 0 until accentRow.childCount) {
            val dot = accentRow.getChildAt(i)
            val color = dot.tag as Int
            dot.background =
                if (color == accent) oval(color, Color.WHITE, 3f) else oval(color)
        }
    }

    // ------------------------------------------------------------------------------------------
    // UI sync, Deck A
    // ------------------------------------------------------------------------------------------

    private fun onTrackA() {
        val c = ctrlA ?: return
        val item = c.currentMediaItem
        val title = if (item == null) "Nothing playing" else titleOf(item)
        val artist = artistOf(item)
        nowTitle.text = title
        nowSub.text = artist
        miniTitle.text = title
        miniSub.text = artist
        mini.visibility = if (item != null) View.VISIBLE else View.GONE

        val id = item?.mediaId
        if (id != currentIdA) {
            currentIdA = id
            adapter.notifyDataSetChanged()
            // debounce so quick skipping never decodes artwork for songs you flew past
            ui.removeCallbacks(artRunA)
            ui.postDelayed(artRunA, 140)
        }
        updateHeart()
        updateFormatA()
        updateProgress()
    }

    private fun formatText(item: MediaItem?, c: MediaController?): String {
        if (item == null) return ""
        val ex = item.mediaMetadata.extras
        val parts = ArrayList<String>()
        val fmt = ex?.getString("fmt").orEmpty()
        if (fmt.isNotEmpty()) parts.add(fmt)
        if (ex?.getBoolean("lossless") == true) parts.add("LOSSLESS")
        val af = c?.currentTracks?.groups
            ?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.getTrackFormat(0)
        if (af != null && af.sampleRate > 0) {
            parts.add(
                if (af.sampleRate % 1000 == 0) "${af.sampleRate / 1000} kHz"
                else String.format(Locale.US, "%.1f kHz", af.sampleRate / 1000f)
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
        return parts.joinToString("  ·  ")
    }

    private fun updateFormatA() {
        val c = ctrlA ?: return
        val text = formatText(c.currentMediaItem, c)
        if (text == fmtA) return // nothing changed, so no layout work at all
        fmtA = text
        fmtLine.text = text
        miniSub.text = artistOf(c.currentMediaItem)
    }

    private fun onPlayStateA() {
        val c = ctrlA ?: return
        val icon = if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playBtn.setImageResource(icon)
        miniPlay.setImageResource(icon)
        updateTicker()
    }

    private fun updateModes() {
        val c = ctrlA
        val shuffle = c?.shuffleModeEnabled ?: false
        val repeat = c?.repeatMode ?: Player.REPEAT_MODE_OFF
        shuffleBtn.setColorFilter(if (shuffle) accent else dim)
        repeatBtn.setImageResource(
            if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        repeatBtn.setColorFilter(if (repeat == Player.REPEAT_MODE_OFF) dim else accent)
    }

    private fun updateHeart() {
        val id = ctrlA?.currentMediaItem?.mediaId
        val liked = id != null && favs.contains(id)
        heartBtn.setImageResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        heartBtn.setColorFilter(if (liked) accent else white)
    }

    private fun showVolumeA(v: Float) {
        if (!volBarLib.isDragging) volBarLib.fraction = v
        if (!volBarPl.isDragging) volBarPl.fraction = v
        setVolLabelsA(v)
    }

    private fun setVolLabelsA(v: Float) {
        volLabelLib.text = pct(v)
        volLabelPl.text = pct(v)
    }

    private fun updateSpeedUi() {
        val speed = ctrlA?.playbackParameters?.speed ?: prefs.getFloat("speed", 1f)
        val text = String.format(Locale.US, "%.2fx", speed)
        menuSpeedVal.text = text
        if (sheetOpen && sheetPage == 1) {
            speedBig.text = text
            if (!speedBar.isDragging) speedBar.fraction = (speed.coerceIn(0.5f, 2f) - 0.5f) / 1.5f
            for (i in 0 until presets.childCount) {
                val t = presets.getChildAt(i) as TextView
                styleChip(t, Math.abs((t.tag as Float) - speed) < 0.011f)
            }
        }
        updateInfoText()
    }

    private fun updateSleepUi() {
        val remain = sleepRemainingMs()
        val text = if (remain > 0) mmss(remain) else "OFF"
        menuSleepVal.text = text
        menuDeckVal.text = if ((ctrlB?.mediaItemCount ?: 0) > 0) "ON" else ""
        if (sheetOpen && sheetPage == 2) {
            sleepBig.text = text
            for (i in 0 until sleepRow.childCount) {
                val t = sleepRow.getChildAt(i) as TextView
                styleChip(t, remain <= 0 && (t.tag as Int) == 0)
            }
        }
        updateInfoText()
    }

    private fun updateInfoText() {
        val speed = ctrlA?.playbackParameters?.speed ?: 1f
        val remain = sleepRemainingMs()
        val parts = ArrayList<String>()
        if (Math.abs(speed - 1f) > 0.01f) parts.add(String.format(Locale.US, "%.2fx", speed))
        if (remain > 0) parts.add("SLEEP " + mmss(remain))
        val s = parts.joinToString("   ")
        if (infoText.text.toString() != s) infoText.text = s
    }

    // ------------------------------------------------------------------------------------------
    // UI sync, Deck B
    // ------------------------------------------------------------------------------------------

    private fun onTrackB() {
        val c = ctrlB ?: return
        val item = c.currentMediaItem
        deckTitle.text = if (item == null) "Empty deck" else titleOf(item)
        deckSub.text = if (item == null) "Pick a song to loop" else artistOf(item)
        val id = item?.mediaId
        if (id != artIdB) {
            artIdB = id
            deckDisc.art = null
            if (id != null) loadArt(id) { bmp -> if (artIdB == id) deckDisc.art = bmp }
        }
        updateFormatB()
        updateProgress()
        menuDeckVal.text = if (item != null) "ON" else ""
    }

    private fun updateFormatB() {
        val c = ctrlB ?: return
        val text = formatText(c.currentMediaItem, c)
        if (text == fmtB) return
        fmtB = text
        deckFmt.text = text
    }

    private fun onPlayStateB() {
        val c = ctrlB ?: return
        deckPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateTicker()
    }

    private fun showVolumeB(v: Float) {
        if (!deckVol.isDragging) deckVol.fraction = v
        deckVolLabel.text = pct(v)
    }

    // ------------------------------------------------------------------------------------------
    // progress ticker: runs only while something visible actually needs it
    // ------------------------------------------------------------------------------------------

    private fun updateTicker() {
        val playing = (ctrlA?.isPlaying ?: false) || (ctrlB?.isPlaying ?: false) || sleepRemainingMs() > 0
        val visible = playerOpen || deckOpen || sheetOpen
        ui.removeCallbacks(ticker)
        if (started && visible && playing) ui.post(ticker)
    }

    private fun updateProgress() {
        if (playerOpen) {
            val c = ctrlA
            if (c != null && !seek.isDragging) {
                val dur = durationOf(c)
                val pos = c.currentPosition.coerceAtLeast(0L)
                seek.fraction = if (dur > 0) pos.toFloat() / dur else 0f
                timeNow.text = mmss(pos)
                timeLeft.text = "-" + mmss((dur - pos).coerceAtLeast(0L))
            }
        }
        if (deckOpen) {
            val c = ctrlB
            if (c != null && !deckSeek.isDragging) {
                val dur = durationOf(c)
                val pos = c.currentPosition.coerceAtLeast(0L)
                deckSeek.fraction = if (dur > 0) pos.toFloat() / dur else 0f
                deckTimeNow.text = mmss(pos)
                deckTimeLeft.text = "-" + mmss((dur - pos).coerceAtLeast(0L))
            }
        }
        if (playerOpen || sheetOpen) updateSleepUi()
    }

    // ------------------------------------------------------------------------------------------
    // artwork (off the main thread)
    // ------------------------------------------------------------------------------------------

    private fun loadArt(id: String, done: (Bitmap?) -> Unit) {
        val uri = ContentUris.withAppendedId(Library.audioBase(), id.toLong())
        io.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(512, 512), null) else null
            } catch (e: Exception) {
                null
            }
            runOnUiThread { done(bmp) }
        }
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

            val current = item.mediaId == currentIdA
            val lossless = md.extras?.getBoolean("lossless") ?: false
            idx.text = String.format(Locale.US, "%02d", position + 1)
            when {
                current -> {
                    idx.background = oval(accent)
                    idx.setTextColor(onAccent)
                }
                lossless -> {
                    idx.background = oval(Color.WHITE)
                    idx.setTextColor(Color.BLACK)
                }
                else -> {
                    idx.background = oval(Color.TRANSPARENT, 0xFF4A4A4A.toInt(), 1.5f)
                    idx.setTextColor(gray)
                }
            }
            title.text = titleOf(item)
            title.setTextColor(if (current) accent else white)

            val fmt = md.extras?.getString("fmt").orEmpty()
            val artist = artistOf(item)
            sub.text = listOf(fmt, artist).filter { it.isNotEmpty() }.joinToString("  ·  ")
            dur.text = mmss(md.durationMs ?: 0L)
            return row
        }
    }
}
