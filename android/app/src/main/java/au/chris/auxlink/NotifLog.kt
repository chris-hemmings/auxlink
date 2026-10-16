package au.chris.auxlink

import org.json.JSONObject

/**
 * Scrollback of mirrored phone notifications received on the head unit.
 * Newest first. Capped so a noisy app cannot grow forever.
 */
object NotifLog {
    data class Action(
        val id: String,
        val label: String,
        val reply: Boolean,
    )

    data class Item(
        val id: Long,
        val app: String,
        val title: String,
        val text: String,
        val big: String,
        val pkg: String,
        val whenMs: Long,
        val iconB64: String,
        /** Phone-side notification key (for reply / mark-read). */
        val nid: String,
        val actions: List<Action>,
    ) {
        fun preview(): String = text.ifEmpty { big }.replace("\n", " ")
        fun body(): String = big.ifEmpty { text }
    }

    private const val MAX = 100
    private val items = mutableListOf<Item>()
    private val listeners = mutableListOf<() -> Unit>()
    private var nextId = 1L
    private var unread = 0

    fun parseActions(o: JSONObject): List<Action> {
        val arr = o.optJSONArray("actions") ?: return emptyList()
        val out = mutableListOf<Action>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("id")
            val label = a.optString("label")
            if (id.isEmpty() || label.isEmpty()) continue
            out += Action(id, label, a.optBoolean("reply", false))
        }
        return out
    }

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
            nid = o.optString("nid"),
            actions = parseActions(o),
        )
        items.add(0, item)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        unread++
        val cbs = listeners.toList()
        cbs.forEach { it.invoke() }
        return item
    }

    @Synchronized fun all(): List<Item> = items.toList()

    /** Newest item with this phone-side notification key, or null. */
    @Synchronized
    fun findByNid(nid: String): Item? {
        if (nid.isEmpty()) return null
        return items.firstOrNull { it.nid == nid }
    }

    @Synchronized fun unreadCount(): Int = unread

    @Synchronized
    fun markSeen() {
        if (unread == 0) return
        unread = 0
        listeners.toList().forEach { it.invoke() }
    }

    @Synchronized
    fun clear() {
        items.clear()
        unread = 0
        listeners.toList().forEach { it.invoke() }
    }

    @Synchronized fun listen(cb: () -> Unit) { if (cb !in listeners) listeners += cb }
    @Synchronized fun unlisten(cb: () -> Unit) { listeners -= cb }
}
