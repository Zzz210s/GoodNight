package com.goodnight.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RuntimeStateStore(private val ds: DataStore<Preferences>) {
    val flow: Flow<RuntimeSnapshot?> = ds.data.map { prefs ->
        RuntimeStateCodec.fromMap(prefs.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() })
    }

    suspend fun save(s: RuntimeSnapshot?) {
        ds.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("rt_") }.forEach { prefs.remove(it) }
            RuntimeStateCodec.toMap(s).forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
        }
    }
}
