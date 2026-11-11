package com.kingzcheung.xime.clipboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's clipboard groups, kept in the board preferences next to the image pins. Like the
 * clipboard records themselves they stay on this device: not in backups, not sent by clipboard sync
 * (which only ever carries the current copy's text).
 */
class ClipboardGroupStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ClipboardGroupState> = _state.asStateFlow()

    /** Group chosen when 新增固定内容 was pressed on a group's tab; the new record goes into it. */
    @Volatile private var addTarget: Pair<String, Long>? = null
    /** Tab the board was on when it closed for the add / edit form; the board reopens on it. */
    @Volatile private var returnTab: Pair<String, Long>? = null

    private fun read(): ClipboardGroupState {
        val groups = ClipboardGroupCodec.decodeGroups(prefs.getString(KEY_GROUPS, null))
        return ClipboardGroupState(groups,
            ClipboardGroupCodec.decodeMembers(prefs.getStringSet(KEY_MEMBERS, null), groups),
            ClipboardGroupCodec.decodeHidden(prefs.getStringSet(KEY_HIDDEN, null)))
    }

    @Synchronized private fun update(change: (ClipboardGroupState) -> ClipboardGroupState) {
        val previous = _state.value
        val next = change(previous)
        if (next == previous) return
        prefs.edit()
            .putString(KEY_GROUPS, ClipboardGroupCodec.encodeGroups(next.groups))
            .putStringSet(KEY_MEMBERS, ClipboardGroupCodec.encodeMembers(next.members))
            .putStringSet(KEY_HIDDEN, next.hidden.mapTo(mutableSetOf()) { it.name })
            .apply()
        _state.value = next
    }

    /** Null when the group was added; otherwise why the name cannot be used. */
    @Synchronized fun add(name: String): String? {
        val groups = _state.value.groups
        clipboardGroupNameError(name, groups)?.let { return it }
        var id = "g" + System.currentTimeMillis().toString(36)
        while (groups.any { it.id == id }) id += "x"
        update { it.withGroup(ClipboardGroup(id, cleanClipboardGroupName(name))) }
        return null
    }

    /** Null when renamed; otherwise why the name cannot be used. */
    @Synchronized fun rename(id: String, name: String): String? {
        clipboardGroupNameError(name, _state.value.groups, renaming = id)?.let { return it }
        update { it.renamed(id, cleanClipboardGroupName(name)) }
        return null
    }

    fun delete(id: String) = update { it.withoutGroup(id) }

    /** The group called [name], made (last in the tab row) when there is none; null when the name cannot be used. */
    @Synchronized fun idForName(name: String): String? {
        val clean = cleanClipboardGroupName(name)
        _state.value.groups.firstOrNull { it.name == clean }?.let { return it.id }
        if (add(clean) != null) return null
        return _state.value.groups.firstOrNull { it.name == clean }?.id
    }

    /** Null [groupId] takes the records out of their group. */
    fun assign(keys: Collection<String>, groupId: String?) {
        if (keys.isNotEmpty()) update { it.assigned(keys, groupId) }
    }

    fun setHidden(filter: ClipboardFilter, hide: Boolean) = update { it.withHidden(filter, hide) }

    fun setAddTarget(groupId: String?) {
        addTarget = groupId?.let { it to System.currentTimeMillis() }
    }

    /** The group for the record just added, once; a target left by an abandoned form expires. */
    fun takeAddTarget(): String? {
        val target = addTarget
        addTarget = null
        return target?.takeIf { (id, at) ->
            System.currentTimeMillis() - at < FORM_LIFETIME_MS && _state.value.groups.any { it.id == id }
        }?.first
    }

    fun setReturnTab(key: String) {
        returnTab = key to System.currentTimeMillis()
    }

    fun takeReturnTab(): String? {
        val tab = returnTab
        returnTab = null
        return tab?.takeIf { System.currentTimeMillis() - it.second < FORM_LIFETIME_MS }?.first
    }

    companion object {
        private const val KEY_GROUPS = "groups"
        private const val KEY_MEMBERS = "group_members"
        private const val KEY_HIDDEN = "hidden_categories"
        private const val FORM_LIFETIME_MS = 10 * 60_000L

        @Volatile private var instance: ClipboardGroupStore? = null

        fun getInstance(context: Context): ClipboardGroupStore = instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }

        /** Independent group metadata bound to the supplied preference context. */
        fun create(context: Context): ClipboardGroupStore = ClipboardGroupStore(context.applicationContext)
    }
}
