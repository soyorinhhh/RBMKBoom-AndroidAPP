package com.example.reactor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

object BackgroundManager {
    private const val PREF = "reactor_bg"
    private const val KEY_URI = "bg_uri"
    private const val KEY_ENABLED = "bg_enabled"

    fun getUri(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)?.let(Uri::parse)

    fun setUri(ctx: Context, uri: Uri?) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (uri == null) p.remove(KEY_URI) else p.putString(KEY_URI, uri.toString())
        p.apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, v).apply()
    }

    fun decode(ctx: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, probe)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcSample(probe.outWidth, probe.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) { null }

    private fun calcSample(w: Int, h: Int, tw: Int, th: Int): Int {
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) return 1
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }
}