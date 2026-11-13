package app.hush.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("hush", MODE_PRIVATE) }
    private val metaPrefs by lazy { getSharedPreferences("hush_meta", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }

    /** One player. Deck 1 = internally A, Deck 2 = internally B (connected only when first used). */
    private inner class Deck(val idx: Int, val defAccent: Int) {
        var ctrl: MediaController? = null
        var future: ListenableFuture<MediaController>? = null
        val pending = ArrayList<(MediaController) -> Unit>()
        var currentId: String? = null
        var artId: String? = null
        var art: Bitmap? = null
        var fmt = ""
        var accent = defAccent
        var onAccent = Color.WHITE
        val playing get() = ctrl?.isPlaying == true
        val hasItem get() = (ctrl?.mediaItemCount ?: 0) > 0

        val artRun = Runnable {
            val id = currentId
            if (id != artId) {
                artId = id
                art = null
                syncDiscs()
                if (id != null) loadArt(id) { bmp ->
                    if (artId == id) {
                        art = bmp
                        syncDiscs()
                    }
                }
            }
        }

        // Reacts only to the events that matter, so nothing is rebuilt needlessly.
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED
                    )
                ) onTrack(this@Deck)
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) updateFormat(this@Deck)
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED
                    )
                ) onPlayState()
                if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) && this@Deck === cur) updateSpeedUi()
                if (events.containsAny(
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED
                    ) && this@Deck === cur
                ) syncModes()
            }
        }
    }

    private val d1 = Deck(1, 0xFFD71921.toInt())
    private val d2 = Deck(2, 0xFF00A859.toInt())
    private var cur: Deck = d1
    private fun decks() = listOf(d1, d2)

    private var all: List<MediaItem> = emptyList()
    private var shown = ArrayList<MediaItem>()
    private var favs: MutableSet<String> = mutableSetOf()
    private var tab = 0
    private var query = ""
    private var lastLoad = 0L

    private var started = false
    private var playerOpen = false
    private var sheetOpen = false
    private var sheetPage = 0
    private var pickDeck: Deck? = null

    // drag / edit state
    private var dragMoved = false
    private var dragItem: MediaItem? = null
    private var editId: String? = null
    private var trackItem: MediaItem? = null

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = TrackAdapter()
    private val reorderAllowed get() = tab == 0 && query.isBlank()

    // library
    private lateinit var libCount: TextView
    private lateinit var libBullet1: TextView
    private lateinit var libBullet2: TextView
    private lateinit var searchBtn: ImageButton
    private lateinit var volBarLib: LineSlider
    private lateinit var volLabelLib: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabLiked: TextView
    private lateinit var reorderHint: TextView
    private lateinit var pickBanner: TextView
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var mini: View
    private lateinit var miniDisc: DiscView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton

    // player
    private lateinit var playerRoot: View
    private lateinit var backBtn: ImageButton
    private lateinit var bullet1: TextView
    private lateinit var bullet2: TextView
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

    // sheet
    private lateinit var sheetRoot: View
    private lateinit var sheet: View
    private lateinit var scrim: View
    private lateinit var pageMenu: LinearLayout
    private lateinit var pageSpeed: View
    private lateinit var pageSleep: View
    private lateinit var pageAccent: View
    private lateinit var pageTrack: View
    private lateinit var pageEdit: View
    private lateinit var speedBig: TextView
    private lateinit var speedBar: LineSlider
    private lateinit var presets: LinearLayout
    private lateinit var sleepBig: TextView
    private lateinit var sleepRow: LinearLayout
    private lateinit var accentRows: LinearLayout
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackRows: LinearLayout
    private lateinit var editCover: DiscView
    private lateinit var editTitle: EditText
    private lateinit var editArtist: EditText
    private lateinit var btnSave: TextView
    private val menuSpeedVal by lazy { valueText() }
    private val menuSleepVal by lazy { valueText() }
    private val accentDots = ArrayList<Triple<View, Deck, Int>>()

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
        0xFF2D7DFF.toInt(), // blue
        0xFFF2F2F2.toInt()  // white
    )

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
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
        loadAccents()
        favs = (prefs.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()
        val deck2WasAlive = prefs.getBoolean("deck2Alive", false)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        touch.attachToRecyclerView(list)

        // volumes: APP VOL (master, library) x deck volume (player). Ticks every 5%.
        volBarLib.hapticSteps = 20
        volBarPl.hapticSteps = 20
        showMaster(master())
        volBarLib.onChange = { f, _ -> setMaster(f) }
        volBarPl.onChange = { f, _ -> setGain(cur, f) }

        seek.onChange = { f, final -> seekTo(cur.ctrl, f, final) }

        val toggle = View.OnClickListener { toggleDeck(cur) }
        playBtn.setOnClickListener(toggle)
        miniPlay.setOnClickListener { miniDeck()?.let { toggleDeck(it) } }
        nextBtn.setOnClickListener { cur.ctrl?.seekToNext() }
        prevBtn.setOnClickListener { cur.ctrl?.seekToPrevious() }
        shuffleBtn.setOnClickListener { cur.ctrl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeatBtn.setOnClickListener {
            cur.ctrl?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        heartBtn.setOnClickListener { cur.ctrl?.currentMediaItem?.let { toggleLike(it.mediaId) } }
        nowTitle.isSelected = true // start marquee for long titles

        // deck switch (subway bullets)
        bullet1.setOnClickListener { selectDeck(d1) }
        bullet2.setOnClickListener { selectDeck(d2) }
        libBullet1.setOnClickListener { openDeck(d1) }
        libBullet2.setOnClickListener { openDeck(d2) }

        // navigation
        mini.setOnClickListener { miniDeck()?.let { openDeck(it) } }
        backBtn.setOnClickListener { closePlayer() }
        menuBtn.setOnClickListener { openSheet(0) }
        pickBanner.setOnClickListener { cancelPick() }

        // library controls
        tabAll.setOnClickListener { tab = 0; styleTabs(); applyFilter() }
        tabLiked.setOnClickListener { tab = 1; styleTabs(); applyFilter() }
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
        v<View>(R.id.editBack).setOnClickListener { showPage(4) }
        speedBar.hapticSteps = 30
        speedBar.ticks = floatArrayOf((1f - 0.5f) / 1.5f)
        speedBar.onChange = { f, _ ->
            val snapped = Math.round((0.5f + 1.5f * f) / 0.05f) * 0.05f
            applySpeed(snapped)
            speedBar.fraction = (snapped.coerceIn(0.5f, 2f) - 0.5f) / 1.5f
        }
        v<View>(R.id.btnCover).setOnClickListener { pickCover() }
        v<View>(R.id.btnReset).setOnClickListener { resetEdit() }
        btnSave.setOnClickListener { saveEdit() }
        buildSheetChips()
        buildAccentRows()

        applyTheme()
        selectDeck(d1)
        connect(d1)
        if (deck2WasAlive) connect(d2)
        requestAccess()
    }

    private fun bindViews() {
        libCount = v(R.id.libCount)
        libBullet1 = v(R.id.libBullet1)
        libBullet2 = v(R.id.libBullet2)
        searchBtn = v(R.id.searchBtn)
        volBarLib = v(R.id.volBarLib)
        volLabelLib = v(R.id.volLabelLib)
        tabAll = v(R.id.tabAll)
        tabLiked = v(R.id.tabLiked)
        reorderHint = v(R.id.reorderHint)
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
        bullet1 = v(R.id.bullet1)
        bullet2 = v(R.id.bullet2)
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

        sheetRoot = v(R.id.sheetRoot)
        sheet = v(R.id.sheet)
        scrim = v(R.id.scrim)
        pageMenu = v(R.id.pageMenu)
        pageSpeed = v(R.id.pageSpeed)
        pageSleep = v(R.id.pageSleep)
        pageAccent = v(R.id.pageAccent)
        pageTrack = v(R.id.pageTrack)
        pageEdit = v(R.id.pageEdit)
        speedBig = v(R.id.speedBig)
        speedBar = v(R.id.speedBar)
        presets = v(R.id.presets)
        sleepBig = v(R.id.sleepBig)
        sleepRow = v(R.id.sleepRow)
        accentRows = v(R.id.accentRows)
        trackTitle = v(R.id.trackTitle)
        trackArtist = v(R.id.trackArtist)
        trackRows = v(R.id.trackRows)
        editCover = v(R.id.editCover)
        editTitle = v(R.id.editTitle)
        editArtist = v(R.id.editArtist)
        btnSave = v(R.id.btnSave)
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
        for (d in decks()) {
            d.ctrl?.removeListener(d.listener)
            d.future?.let { MediaController.releaseFuture(it) }
        }
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheetOpen -> when (sheetPage) {
                0, 4 -> closeSheet()
                5 -> showPage(4)
                else -> showPage(0)
            }
            playerOpen -> closePlayer()
            pickDeck != null -> cancelPick()
            search.visibility == View.VISIBLE -> toggleSearch()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------------------------------
    // theme / helpers
    // ------------------------------------------------------------------------------------------

    private fun loadAccents() {
        for (d in decks()) {
            val fallback = if (d.idx == 1) prefs.getInt("accent", d.defAccent) else d.defAccent
            d.accent = prefs.getInt("accentD${d.idx}", fallback)
            d.onAccent = onColor(d.accent)
        }
    }

    private fun onColor(c: Int) = if (ColorUtils.calculateLuminance(c) > 0.5) Color.BLACK else Color.WHITE

    private fun setDeckAccent(d: Deck, color: Int) {
        prefs.edit().putInt("accentD${d.idx}", color).apply()
        d.accent = color
        d.onAccent = onColor(color)
        applyTheme()
        HushWidget.refresh(this)
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

    private fun styleBullet(t: TextView, d: Deck, filled: Boolean) {
        if (filled) {
            t.background = oval(d.accent)
            t.setTextColor(d.onAccent)
        } else {
            t.background = oval(Color.TRANSPARENT, d.accent, 2f)
            t.setTextColor(d.accent)
        }
    }

    private fun styleBullets() {
        styleBullet(bullet1, d1, cur === d1)
        styleBullet(bullet2, d2, cur === d2)
        styleBullet(libBullet1, d1, d1.playing)
        styleBullet(libBullet2, d2, d2.playing)
    }

    /** Repaints everything that uses a deck colour. */
    private fun applyTheme() {
        val c = cur
        for (s in listOf(volBarLib, volBarPl, seek, speedBar)) s.setAccent(c.accent)
        playBtn.background = rect(c.accent, 24f)
        playBtn.setColorFilter(c.onAccent)
        btnSave.background = rect(c.accent, 16f)
        btnSave.setTextColor(c.onAccent)
        pickDeck?.let {
            pickBanner.background = rect(it.accent, 16f)
            pickBanner.setTextColor(it.onAccent)
        }
        styleBullets()
        styleTabs()
        syncModes()
        updateHeart()
        updateSpeedUi()
        updateSleepUi()
        refreshAccentDots()
        syncMini()
        syncDiscs()
        adapter.notifyDataSetChanged()
    }

    private fun styleTabs() {
        styleChip(tabAll, tab == 0)
        styleChip(tabLiked, tab == 1)
    }

    private fun styleChip(t: TextView, on: Boolean) {
        if (on) {
            t.background = rect(cur.accent, 100f)
            t.setTextColor(cur.onAccent)
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

    private fun seekTo(c: MediaController?, f: Float, final: Boolean) {
        val dur = durationOf(c)
        if (c != null && dur > 0) {
            val pos = (f * dur).toLong()
            timeNow.text = mmss(pos)
            timeLeft.text = "-" + mmss(dur - pos)
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
            ?: item?.mediaMetadata?.artist?.toString()?.removePrefix("DECK 2 · ")
            ?: ""

    // ------------------------------------------------------------------------------------------
    // volume: APP VOL (master) x deck volume
    // ------------------------------------------------------------------------------------------

    private fun master() = prefs.getFloat("master", prefs.getFloat("volume", 1f))
    private fun gain(d: Deck) = prefs.getFloat("gain${d.idx}", 1f)
    private fun applyVolume(d: Deck) {
        d.ctrl?.volume = (master() * gain(d)).coerceIn(0f, 1f)
    }

    private fun setMaster(f: Float) {
        prefs.edit().putFloat("master", f).apply()
        decks().forEach { applyVolume(it) }
        volLabelLib.text = pct(f)
    }

    private fun setGain(d: Deck, f: Float) {
        prefs.edit().putFloat("gain${d.idx}", f).apply()
        applyVolume(d)
        volLabelPl.text = pct(f)
    }

    private fun showMaster(f: Float) {
        if (!volBarLib.isDragging) volBarLib.fraction = f
        volLabelLib.text = pct(f)
    }

    private fun showGain() {
        val g = gain(cur)
        if (!volBarPl.isDragging) volBarPl.fraction = g
        volLabelPl.text = pct(g)
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
            empty.text = "Allow music access in Settings to see your songs"
        }
    }

    private fun loadTracks(after: (() -> Unit)? = null) {
        lastLoad = System.currentTimeMillis()
        io.execute {
            val found = try { Library.query(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                all = found
                libCount.text = "${all.size} songs"
                applyFilter()
                after?.invoke()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        shown = ArrayList(all.filter {
            (tab == 0 || favs.contains(it.mediaId)) &&
                (q.isEmpty() ||
                    titleOf(it).lowercase().contains(q) ||
                    artistOf(it).lowercase().contains(q))
        })
        adapter.notifyDataSetChanged()
        empty.text = when {
            all.isEmpty() -> if (lastLoad > 0) "No music found on this phone" else "Looking for music…"
            tab == 1 && q.isEmpty() -> "No liked songs yet.\nTap the heart while playing."
            else -> "Nothing found"
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        reorderHint.visibility = if (reorderAllowed && shown.isNotEmpty()) View.VISIBLE else View.GONE
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

    private fun connect(d: Deck, then: ((MediaController) -> Unit)? = null) {
        d.ctrl?.let { then?.invoke(it); return }
        if (then != null) d.pending.add(then)
        if (d.future != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val builder = MediaController.Builder(this, token)
        if (d.idx == 2) builder.setConnectionHints(Bundle().apply { putString("deck", "B") })
        val future = builder.buildAsync()
        d.future = future
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                d.future = null
                d.pending.clear()
                return@addListener
            }
            d.ctrl = c
            c.addListener(d.listener)
            applyVolume(d)
            onTrack(d)
            onPlayState()
            if (d === cur) {
                syncModes()
                updateSpeedUi()
            }
            val todo = ArrayList(d.pending)
            d.pending.clear()
            todo.forEach { it(c) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleDeck(d: Deck) {
        val c = d.ctrl
        when {
            c == null || c.mediaItemCount == 0 -> {
                if (d === d1 && c != null && shown.isNotEmpty()) playFrom(0) else startPick(d)
            }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    private fun playFrom(index: Int) {
        val c = d1.ctrl ?: return
        if (shown.isEmpty() || index !in shown.indices) return
        c.setMediaItems(shown, index, 0L)
        c.prepare()
        c.play()
        openDeck(d1)
    }

    private fun loadDeck2(item: MediaItem) {
        val tagged = item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon().setArtist("DECK 2 · " + artistOf(item)).build()
            )
            .build()
        connect(d2) { c ->
            c.repeatMode = Player.REPEAT_MODE_ONE // loops until you pause or change it
            c.setMediaItem(tagged)
            c.prepare()
            c.play()
        }
        openDeck(d2)
    }

    private fun playOn(d: Deck, pos: Int) {
        if (pos !in shown.indices) return
        if (d === d1) playFrom(pos) else loadDeck2(shown[pos])
    }

    private fun startPick(d: Deck) {
        pickDeck = d
        pickBanner.text = "PICK A SONG FOR DECK ${d.idx}   ✕"
        pickBanner.background = rect(d.accent, 16f)
        pickBanner.setTextColor(d.onAccent)
        pickBanner.visibility = View.VISIBLE
        if (sheetOpen) closeSheet()
        if (playerOpen) closePlayer()
    }

    private fun cancelPick() {
        pickDeck = null
        pickBanner.visibility = View.GONE
    }

    private fun onRowClick(pos: Int) {
        if (pos !in shown.indices) return
        val d = pickDeck
        if (d != null) {
            cancelPick()
            playOn(d, pos)
        } else playFrom(pos)
    }

    /** Bullet tap in the library: open that deck, or ask for a song if it is empty. */
    private fun openDeck(d: Deck) {
        if (d.hasItem || d === d1 && d.ctrl != null && d.currentId != null) {
            selectDeck(d)
            openPlayer()
        } else if (d === d2 && d.ctrl == null && prefs.getBoolean("deck2Alive", false)) {
            connect(d2) { selectDeck(d2); openPlayer() }
        } else {
            selectDeck(d)
            startPick(d)
        }
    }

    private fun selectDeck(d: Deck) {
        cur = d
        if (d === d2) connect(d2) // lets deck 2's controls work right away
        applyTheme()
        syncTrack()
        syncPlay()
        showGain()
        syncDiscs()
        updateProgress()
        updateTicker()
    }

    private fun miniDeck(): Deck? = when {
        d2.playing && !d1.playing -> d2
        d1.hasItem -> d1
        d2.hasItem -> d2
        else -> null
    }

    private fun applySpeed(speed: Float) {
        val c = cur.ctrl ?: return
        c.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 2f), 1f)
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

    private fun openSheet(page: Int) {
        if (sheetOpen) {
            showPage(page)
            return
        }
        sheetOpen = true
        showPage(page)
        sheetRoot.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        val h = resources.displayMetrics.heightPixels.toFloat()
        sheet.translationY = h
        sheet.animate().translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        updateTicker()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(sheet.windowToken, 0)
        scrim.animate().alpha(0f).setDuration(140).start()
        sheet.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(170)
            .withEndAction { sheetRoot.visibility = View.GONE }.start()
        updateTicker()
    }

    private fun showPage(page: Int) {
        sheetPage = page
        if (page == 0) buildMenu()
        pageMenu.visibility = if (page == 0) View.VISIBLE else View.GONE
        pageSpeed.visibility = if (page == 1) View.VISIBLE else View.GONE
        pageSleep.visibility = if (page == 2) View.VISIBLE else View.GONE
        pageAccent.visibility = if (page == 3) View.VISIBLE else View.GONE
        pageTrack.visibility = if (page == 4) View.VISIBLE else View.GONE
        pageEdit.visibility = if (page == 5) View.VISIBLE else View.GONE
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
        pageMenu.removeAllViews()
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (d in decks()) {
                addView(View(context).apply {
                    background = oval(d.accent)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) }
                })
            }
        }
        pageMenu.addView(menuRow("1", "Speed", menuSpeedVal) { showPage(1) })
        pageMenu.addView(menuRow("2", "Sleep timer", menuSleepVal) { showPage(2) })
        pageMenu.addView(menuRow("3", "Deck colours", dots) { showPage(3) })
        if (cur.hasItem) {
            pageMenu.addView(menuRow("4", "Eject deck ${cur.idx}", View(this)) {
                cur.ctrl?.let {
                    it.stop()
                    it.clearMediaItems()
                }
                closeSheet()
            })
        }
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
    }

    private fun buildAccentRows() {
        for (d in decks()) {
            accentRows.addView(TextView(this).apply {
                text = "DECK ${d.idx}"
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                letterSpacing = 0.08f
                textSize = 11f
                setTextColor(gray)
                setPadding(0, dp(if (d.idx == 1) 0 else 14), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (color in accents) {
                val dot = View(this)
                dot.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
                dot.setOnClickListener { setDeckAccent(d, color) }
                accentDots.add(Triple(dot, d, color))
                row.addView(dot)
            }
            accentRows.addView(row)
        }
    }

    private fun refreshAccentDots() {
        for ((dot, d, color) in accentDots) {
            dot.background = if (color == d.accent) oval(color, Color.WHITE, 3f) else oval(color)
        }
    }

    // ------------------------------------------------------------------------------------------
    // song sheet (long-press) + edit
    // ------------------------------------------------------------------------------------------

    private fun openTrackSheet(item: MediaItem) {
        trackItem = item
        trackTitle.text = titleOf(item)
        trackArtist.text = artistOf(item)
        trackRows.removeAllViews()
        val pos = { shown.indexOfFirst { it.mediaId == item.mediaId } }
        trackRows.addView(menuRow("1", "Play on deck 1", View(this)) {
            closeSheet(); playOn(d1, pos())
        })
        trackRows.addView(menuRow("2", "Play on deck 2", View(this)) {
            closeSheet(); playOn(d2, pos())
        })
        trackRows.addView(menuRow("3", "Edit info", View(this)) { openEdit(item) })
        openSheet(4)
    }

    private fun openEdit(item: MediaItem) {
        editId = item.mediaId
        editTitle.setText(titleOf(item))
        editArtist.setText(artistOf(item))
        editCover.labelColor = cur.accent
        editCover.art = null
        loadArt(item.mediaId) { editCover.art = it }
        showPage(5)
    }

    private fun pickCover() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(i, 77)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val id = editId
        if (requestCode != 77 || resultCode != RESULT_OK || uri == null || id == null) return
        io.execute {
            val bmp = try { decodeCover(uri) } catch (e: Exception) { null }
            if (bmp != null) {
                try {
                    val f = Library.coverFile(this, id)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                if (bmp != null && editId == id) editCover.art = bmp
                forceArt(id)
                loadTracks { refreshQueues(id) }
            }
        }
    }

    private fun decodeCover(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / s > 1200 || bounds.outHeight / s > 1200) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, 600, 600, true)
    }

    private fun saveEdit() {
        val id = editId ?: return
        val t = editTitle.text.toString().trim()
        val a = editArtist.text.toString().trim()
        val e = metaPrefs.edit()
        if (t.isEmpty()) e.remove("t_$id") else e.putString("t_$id", t)
        if (a.isEmpty()) e.remove("a_$id") else e.putString("a_$id", a)
        e.apply()
        closeSheet()
        loadTracks { refreshQueues(id) }
    }

    private fun resetEdit() {
        val id = editId ?: return
        metaPrefs.edit().remove("t_$id").remove("a_$id").apply()
        Library.coverFile(this, id).delete()
        closeSheet()
        forceArt(id)
        loadTracks { refreshQueues(id) }
    }

    private fun forceArt(id: String) {
        for (d in decks()) if (d.currentId == id) {
            d.artId = null
            ui.removeCallbacks(d.artRun)
            ui.post(d.artRun)
        }
    }

    /** Pushes the edited song info into any queue that holds the song. */
    private fun refreshQueues(id: String) {
        val fresh = all.firstOrNull { it.mediaId == id } ?: return
        for (d in decks()) {
            val c = d.ctrl ?: continue
            val item = if (d === d2) fresh.buildUpon().setMediaMetadata(
                fresh.mediaMetadata.buildUpon().setArtist("DECK 2 · " + artistOf(fresh)).build()
            ).build() else fresh
            for (i in 0 until c.mediaItemCount) {
                if (c.getMediaItemAt(i).mediaId != id) continue
                if (i == c.currentMediaItemIndex) {
                    val pos = c.currentPosition
                    val wasPlaying = c.playWhenReady
                    c.replaceMediaItem(i, item)
                    c.seekTo(i, pos)
                    c.playWhenReady = wasPlaying
                } else c.replaceMediaItem(i, item)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // UI sync
    // ------------------------------------------------------------------------------------------

    private fun onTrack(d: Deck) {
        val c = d.ctrl ?: return
        val id = c.currentMediaItem?.mediaId
        if (id != d.currentId) {
            d.currentId = id
            adapter.notifyDataSetChanged()
            // debounce so quick skipping never decodes artwork for songs you flew past
            ui.removeCallbacks(d.artRun)
            ui.postDelayed(d.artRun, 140)
        }
        updateFormat(d)
        if (d === cur) syncTrack()
        syncMini()
        updateProgress()
    }

    private fun syncTrack() {
        val c = cur.ctrl
        val item = c?.currentMediaItem
        if (item == null) {
            nowTitle.text = if (cur === d2) "Empty deck" else "Nothing playing"
            nowSub.text = if (cur === d2) "Tap play to pick a song" else ""
            fmtLine.text = ""
        } else {
            nowTitle.text = titleOf(item)
            nowSub.text = artistOf(item)
            fmtLine.text = cur.fmt
        }
        updateHeart()
        updateInfoText()
    }

    private fun syncMini() {
        val d = miniDeck()
        if (d == null) {
            mini.visibility = View.GONE
            return
        }
        val item = d.ctrl?.currentMediaItem
        mini.visibility = View.VISIBLE
        miniTitle.text = titleOf(item)
        miniSub.text = (if (d === d2) "DECK 2 · " else "") + artistOf(item)
        miniPlay.background = oval(d.accent)
        miniPlay.setColorFilter(d.onAccent)
        miniPlay.setImageResource(if (d.playing) R.drawable.ic_pause else R.drawable.ic_play)
        miniDisc.labelColor = d.accent
        miniDisc.art = d.art
    }

    private fun syncDiscs() {
        disc.labelColor = cur.accent
        disc.art = cur.art
        miniDeck()?.let {
            miniDisc.labelColor = it.accent
            miniDisc.art = it.art
        }
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

    private fun updateFormat(d: Deck) {
        val c = d.ctrl ?: return
        val text = formatText(c.currentMediaItem, c)
        if (text == d.fmt) return // nothing changed, so no layout work at all
        d.fmt = text
        if (d === cur) fmtLine.text = text
    }

    private fun onPlayState() {
        syncPlay()
        syncMini()
        styleBullets()
        updateTicker()
    }

    private fun syncPlay() {
        playBtn.setImageResource(if (cur.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun syncModes() {
        val c = cur.ctrl
        val shuffle = c?.shuffleModeEnabled ?: false
        val repeat = c?.repeatMode ?: Player.REPEAT_MODE_OFF
        shuffleBtn.setColorFilter(if (shuffle) cur.accent else dim)
        repeatBtn.setImageResource(
            if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        repeatBtn.setColorFilter(if (repeat == Player.REPEAT_MODE_OFF) dim else cur.accent)
    }

    private fun updateHeart() {
        val id = cur.ctrl?.currentMediaItem?.mediaId
        val liked = id != null && favs.contains(id)
        heartBtn.setImageResource(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        heartBtn.setColorFilter(if (liked) cur.accent else white)
    }

    private fun updateSpeedUi() {
        val speed = cur.ctrl?.playbackParameters?.speed ?: 1f
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
        val speed = cur.ctrl?.playbackParameters?.speed ?: 1f
        val remain = sleepRemainingMs()
        val parts = ArrayList<String>()
        if (Math.abs(speed - 1f) > 0.01f) parts.add(String.format(Locale.US, "%.2fx", speed))
        if (remain > 0) parts.add("SLEEP " + mmss(remain))
        val s = parts.joinToString("   ")
        if (infoText.text.toString() != s) infoText.text = s
    }

    // ------------------------------------------------------------------------------------------
    // progress ticker: runs only while something visible actually needs it
    // ------------------------------------------------------------------------------------------

    private fun updateTicker() {
        val playing = d1.playing || d2.playing || sleepRemainingMs() > 0
        val visible = playerOpen || sheetOpen
        ui.removeCallbacks(ticker)
        if (started && visible && playing) ui.post(ticker)
    }

    private fun updateProgress() {
        if (playerOpen) {
            val c = cur.ctrl
            if (c != null && !seek.isDragging) {
                val dur = durationOf(c)
                val pos = c.currentPosition.coerceAtLeast(0L)
                seek.fraction = if (dur > 0) pos.toFloat() / dur else 0f
                timeNow.text = mmss(pos)
                timeLeft.text = "-" + mmss((dur - pos).coerceAtLeast(0L))
            } else if (c == null) {
                seek.fraction = 0f
                timeNow.text = mmss(0)
                timeLeft.text = "-" + mmss(0)
            }
        }
        if (playerOpen || sheetOpen) updateSleepUi()
    }

    // ------------------------------------------------------------------------------------------
    // artwork (off the main thread): your custom cover first, then the song's own art
    // ------------------------------------------------------------------------------------------

    private fun loadArt(id: String, done: (Bitmap?) -> Unit) {
        val uri = ContentUris.withAppendedId(Library.audioBase(), id.toLong())
        io.execute {
            var bmp: Bitmap? = null
            val f: File = Library.coverFile(this, id)
            if (f.exists()) bmp = BitmapFactory.decodeFile(f.path)
            if (bmp == null && Build.VERSION.SDK_INT >= 29) {
                bmp = try {
                    contentResolver.loadThumbnail(uri, Size(512, 512), null)
                } catch (e: Exception) {
                    null
                }
            }
            runOnUiThread { done(bmp) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // list: tap = play, long-press = drag to reorder (or open the song sheet if you don't move)
    // ------------------------------------------------------------------------------------------

    private val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || !reorderAllowed) return false
            val it = shown.removeAt(from)
            shown.add(to, it)
            adapter.notifyItemMoved(from, to)
            dragMoved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.setBackgroundColor(0xFF1A1A1A.toInt())
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.setBackgroundColor(Color.TRANSPARENT)
            val item = dragItem ?: return
            dragItem = null
            if (dragMoved) {
                dragMoved = false
                all = ArrayList(shown)
                prefs.edit().putString("order", all.joinToString(",") { it.mediaId }).apply()
                adapter.notifyDataSetChanged() // refresh the 01, 02, 03 numbers
            } else {
                openTrackSheet(item)
            }
        }
    })

    private inner class TrackAdapter : RecyclerView.Adapter<TrackAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val idx: TextView = view.findViewById(R.id.rowIdx)
            val title: TextView = view.findViewById(R.id.rowTitle)
            val sub: TextView = view.findViewById(R.id.rowSub)
            val dur: TextView = view.findViewById(R.id.rowDur)
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.row_track, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = shown[position]
            val md = item.mediaMetadata
            val onDeck = when (item.mediaId) {
                d1.currentId -> d1
                d2.currentId -> d2
                else -> null
            }
            val lossless = md.extras?.getBoolean("lossless") ?: false
            h.itemView.setBackgroundColor(Color.TRANSPARENT)
            h.idx.text = String.format(Locale.US, "%02d", position + 1)
            when {
                onDeck != null -> {
                    h.idx.background = oval(onDeck.accent)
                    h.idx.setTextColor(onDeck.onAccent)
                }
                lossless -> {
                    h.idx.background = oval(Color.WHITE)
                    h.idx.setTextColor(Color.BLACK)
                }
                else -> {
                    h.idx.background = oval(Color.TRANSPARENT, 0xFF4A4A4A.toInt(), 1.5f)
                    h.idx.setTextColor(gray)
                }
            }
            h.title.text = titleOf(item)
            h.title.setTextColor(onDeck?.accent ?: white)
            val fmt = md.extras?.getString("fmt").orEmpty()
            h.sub.text = listOf(fmt, artistOf(item)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            h.dur.text = mmss(md.durationMs ?: 0L)

            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onRowClick(p)
            }
            h.itemView.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION || p !in shown.indices) return@setOnLongClickListener true
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (reorderAllowed) {
                    dragMoved = false
                    dragItem = shown[p]
                    touch.startDrag(h)
                } else openTrackSheet(shown[p])
                true
            }
        }
    }
}
