package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UPDATE-CHECK (the only code in Leaflet that uses the internet; see "Removing the update check" in the README).
 *
 * When the user taps "Check for updates", asks GitHub for the latest release, shows its notes if it is newer, and opens
 * the APK link in the browser. It never downloads or installs anything itself, and it never runs on its own.
 */
object UpdateChecker {
    // Update this when the GitHub account is renamed (ideas.md item 15).
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/DanielCohen002/leaflet/releases/latest"

    private class Release(val version: String, val notes: String, val link: String)

    fun check(activity: Activity) {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { fetch() }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result
                    .onSuccess { show(activity, it) }
                    .onFailure {
                        AlertDialog.Builder(activity)
                            .setTitle("Couldn't check for updates")
                            .setMessage("Leaflet could not reach GitHub. Check that you are online and try again.")
                            .setPositiveButton("OK", null)
                            .showLeaf()
                    }
            }
        }.start()
    }

    private fun fetch(): Release {
        val c = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
            }
            return Release(json.getString("tag_name").removePrefix("v"), json.optString("body"), apk ?: json.getString("html_url"))
        } finally {
            c.disconnect()
        }
    }

    private fun show(activity: Activity, release: Release) {
        val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
        if (!isNewer(release.version, current)) {
            AlertDialog.Builder(activity)
                .setTitle("You are up to date")
                .setMessage("Leaflet $current is the newest version.")
                .setPositiveButton("OK", null)
                .showLeaf()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Version ${release.version} is available")
            .setMessage(release.notes.ifBlank { "No release notes." } + "\n\nYou have $current. Download opens the APK in your browser; open it to update. Your library is kept.")
            .setPositiveButton("Download") { _, _ ->
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.link))) }
                catch (_: Exception) { Toast.makeText(activity, "No browser found", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Later", null)
            .showLeaf()
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
