package com.khvpn.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Penyimpanan sederhana berbasis SharedPreferences (private ke aplikasi, tidak ikut backup). */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("kh", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(Profile.listFromJson(sp.getString("profiles", null)))
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _selectedId = MutableStateFlow(sp.getString("selected", null))
    val selectedId: StateFlow<String?> = _selectedId

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    val selected: Profile? get() = _profiles.value.firstOrNull { it.id == _selectedId.value } ?: _profiles.value.firstOrNull()

    fun save(p: Profile) {
        val l = _profiles.value.toMutableList()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l.add(p)
        _profiles.value = l
        if (_selectedId.value == null) _selectedId.value = p.id
        persist()
    }

    fun delete(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
        if (_selectedId.value == id) _selectedId.value = _profiles.value.firstOrNull()?.id
        persist()
    }

    fun select(id: String) { _selectedId.value = id; persist() }

    fun update(block: (Settings) -> Settings) {
        _settings.value = block(_settings.value)
        val s = _settings.value
        sp.edit()
            .putString("theme", s.themeMode.name).putBoolean("dynamic", s.dynamicColor).putBoolean("speed", s.showSpeed)
            .putString("dns1", s.dns1).putString("dns2", s.dns2)
            .putInt("socks", s.socksPort).putInt("local", s.localPort).putInt("mtu", s.mtu)
            .putBoolean("compress", s.sshCompression).putBoolean("reconnect", s.autoReconnect)
            .putStringSet("bypass", s.bypassApps).apply()
    }

    /** Rute yang terakhir berhasil per profil, dicoba duluan saat mode Otomatis. */
    fun lastRoute(profileId: String): String? = sp.getString("route_$profileId", null)
    fun setLastRoute(profileId: String, route: String) { sp.edit().putString("route_$profileId", route).apply() }

    private fun persist() {
        sp.edit().putString("profiles", Profile.listToJson(_profiles.value)).putString("selected", _selectedId.value).apply()
    }

    private fun loadSettings() = Settings(
        themeMode = ThemeMode.of(sp.getString("theme", null)),
        dynamicColor = sp.getBoolean("dynamic", true), showSpeed = sp.getBoolean("speed", true),
        dns1 = sp.getString("dns1", "8.8.8.8") ?: "8.8.8.8",
        dns2 = sp.getString("dns2", "8.8.4.4") ?: "8.8.4.4",
        socksPort = sp.getInt("socks", 1080), localPort = sp.getInt("local", 2222), mtu = sp.getInt("mtu", 1500),
        sshCompression = sp.getBoolean("compress", false), autoReconnect = sp.getBoolean("reconnect", true),
        bypassApps = sp.getStringSet("bypass", emptySet())?.toSet() ?: emptySet(),
    )
}
