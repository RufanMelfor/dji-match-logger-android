package com.djimatchlogger.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * The phone's last known position, if it happens to have one.
 *
 * Deliberately passive: it reads what the system already has rather than
 * asking for a fix. A camera clock is worth setting even without a position,
 * and no location is a better answer than an invented one — a made-up
 * coordinate ends up written into the footage.
 */
class LastKnownLocation(private val context: Context) {

    @SuppressLint("MissingPermission") // permission is checked below
    fun get(): Location? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }
}
