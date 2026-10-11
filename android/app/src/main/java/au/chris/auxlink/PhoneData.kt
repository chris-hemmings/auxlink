package au.chris.auxlink

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contacts, recent calls and texts from the phone, as the Pi sends them
 * (it gets them from the phone over Bluetooth). The Phone and Messages
 * screens read this and listen for changes. Main thread only.
 */
object PhoneData {
    data class Contact(val name: String, val numbers: List<Pair<String, String>>)   // (kind, number)
    data class Recent(val name: String, val number: String, val kind: String, val at: String)
    data class Message(
        val incoming: Boolean, val who: String, val number: String,
        val text: String, val at: String, var status: String = "",
    )

    var contacts: List<Contact> = emptyList(); private set
    var recents: List<Recent> = emptyList(); private set
    var messages: List<Message> = emptyList(); private set
    var messagesEnabled = true; private set
    var contactsLoaded = false; private set
    var messagesLoaded = false; private set
    /** Texts being sent from this app: id -> message (shown until the Pi confirms). */
    val sending = LinkedHashMap<String, Message>()

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val parts = HashMap<String, MutableList<JSONObject>>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** A line from the Pi. Returns true if it was one of ours. */
    fun onLine(j: JSONObject): Boolean {
        val kind = listOf("contacts", "recents", "messages").firstOrNull { j.has(it) }
        if (kind != null) {
            val part = j.optInt("part", 0)
            val total = j.optInt("parts", 1)
            val got = parts.getOrPut(kind) { mutableListOf() }
            if (part == 0) got.clear()
            got += j
            if (got.size >= total) {
                val items = JSONArray()
                for (p in got) {
                    val a = p.optJSONArray(kind) ?: continue
                    for (i in 0 until a.length()) items.put(a.get(i))
                }
                got.clear()
                main.post { take(kind, items, j); changed() }
            }
            return true
        }
        if (j.has("sms_result")) {
            val r = j.optJSONObject("sms_result") ?: return true
            main.post {
                sending[r.optString("id")]?.status =
                    if (r.optBoolean("ok")) "sent" else "failed: " + r.optString("error")
                changed()
            }
            return true
        }
        return false
    }

    private fun take(kind: String, a: JSONArray, last: JSONObject) {
        when (kind) {
            "contacts" -> {
                contacts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { c ->
                    val t = c.optJSONArray("t") ?: JSONArray()
                    Contact(c.optString("n"), (0 until t.length()).mapNotNull { t.optJSONArray(it) }
                        .map { it.optString(0) to it.optString(1) })
                }
                contactsLoaded = true
            }
            "recents" -> recents = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Recent(it.optString("n"), it.optString("num"), it.optString("k"), it.optString("at"))
            }
            "messages" -> {
                messages = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    Message(it.optInt("in") == 1, it.optString("who"), it.optString("num"),
                        it.optString("text"), it.optString("at"), it.optString("st"))
                }.sortedBy { it.at }
                messagesEnabled = last.optBoolean("enabled", true)
                messagesLoaded = true
                // The Pi now has our sent texts on record: stop showing local copies.
                val known = messages.filter { !it.incoming }.map { it.number + "\u0000" + it.text }.toSet()
                sending.entries.removeAll { (_, m) ->
                    m.status == "sent" && (m.number + "\u0000" + m.text) in known
                }
            }
        }
    }

    /** Last 9 digits: the same number written +61 4.. or 04.. matches. */
    fun key(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length > 9) d.takeLast(9) else d
    }

    private var nameIndex: Map<String, String> = emptyMap()
    private var nameIndexFor: List<Contact>? = null

    /** A contact's name for a number, or "". */
    fun nameFor(number: String): String {
        if (nameIndexFor !== contacts) {
            nameIndex = contacts.flatMap { c -> c.numbers.map { key(it.second) to c.name } }.toMap()
            nameIndexFor = contacts
        }
        return nameIndex[key(number)] ?: ""
    }

    /** "20261011T101010" -> "10:10" today, "Yesterday", or "11 Oct". */
    fun shortTime(at: String): String {
        if (at.length < 13) return ""
        return try {
            val f = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            val d = f.parse(at.take(15)) ?: return ""
            val now = java.util.Calendar.getInstance()
            val then = java.util.Calendar.getInstance().apply { time = d }
            val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
            now.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val yesterday = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
            when {
                sameDay -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(d)
                yesterday -> "Yesterday"
                else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(d)
            }
        } catch (e: Exception) { "" }
    }
}
