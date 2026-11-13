package app.hush.player

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Two pages for the widget's swipeable stack: deck A and deck B. */
class HushWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val prefs get() = getSharedPreferences("hush", Context.MODE_PRIVATE)
        override fun onCreate() {}
        override fun onDataSetChanged() {}
        override fun onDestroy() {}
        override fun getCount() = 2
        override fun getViewAt(position: Int): RemoteViews {
            val b = position == 1
            val playing = prefs.getBoolean(if (b) "isPlayingB" else "isPlaying", false)
            val accent = if (b) prefs.getInt("accentD2", 0xFF00A859.toInt())
            else prefs.getInt("accentD1", prefs.getInt("accent", 0xFFD71921.toInt()))
            return RemoteViews(packageName, R.layout.widget_item).apply {
                setImageViewBitmap(R.id.wItem, HushWidget.draw(playing, accent, if (b) "B" else "A"))
                setOnClickFillInIntent(R.id.wItem, Intent().putExtra("deck", position + 1))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
