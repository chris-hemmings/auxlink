package au.chris.auxlink

import org.json.JSONObject

/**
 * Scrollback of mirrored phone notifications received on the head unit.
 * Newest first. Capped so a noisy app cannot grow forever.
 */
object NotifLog {
    data class Item(
        val id: Long,
        val app: String,
        val title: String,
        val text: String,
        val big: String,
        val pkg: String,
        val whenMs: Long,
        val iconB64: String,
    ) {
        fun preview(): String = text.ifEmpty { big }.replace("\n", " ")
        fun body(): String = big.ifEmpty { text }
    }

    private const val MAX = 100
    private val items = mutableListOf<Item>()
    private val listeners = mutableListOf<() -> Unit>()
    private var nextId = 1L

    @Synchronized
    fun add(o: JSONObject): Item {
        val item = Item(
            id = nextId++,
            app = o.optString("app").ifEmpty { "Notification" },
            title = o.optString("title"),
            text = o.optString("text"),
            big = o.optString("big"),
            pkg = o.optString("pkg"),
            whenMs = o.optLong("when", System.currentTimeMillis()),
            iconB64 = o.optString("icon"),
        )
        items.add(0, item)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        val cbs = listeners.toList()
        cbs.forEach { it.invoke() }
        return item
    }

    @Synchronized fun all(): List<Item> = items.toList()

    @Synchronized
    fun clear() {
        items.clear()
        listeners.toList().forEach { it.invoke() }
    }

    @Synchronized fun listen(cb: () -> Unit) { if (cb !in listeners) listeners += cb }
    @Synchronized fun unlisten(cb: () -> Unit) { listeners -= cb }
}
