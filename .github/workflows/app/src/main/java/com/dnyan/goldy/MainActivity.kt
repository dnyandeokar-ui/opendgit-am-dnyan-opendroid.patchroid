package com.dnyan.goldy

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun scheduleAlarm(c: Context, id: Int, at: Long, title: String, body: String) {
    val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("t", title).putExtra("b", body)
    val pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("goldy", "Goldy", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(i.getIntExtra("id", 0), Notification.Builder(c, "goldy").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(i.getStringExtra("t")).setContentText(i.getStringExtra("b")).setContentIntent(open).setAutoCancel(true).build())
        val p = c.getSharedPreferences("alarms", 0); val a = JSONArray(p.getString("l", "[]")); val n = JSONArray()
        for (k in 0 until a.length()) if (a.getJSONObject(k).getInt("id") != i.getIntExtra("id", -1)) n.put(a.getJSONObject(k))
        p.edit().putString("l", n.toString()).apply()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = JSONArray(c.getSharedPreferences("alarms", 0).getString("l", "[]"))
        for (k in 0 until a.length()) a.getJSONObject(k).let {
            if (it.getLong("at") > System.currentTimeMillis()) scheduleAlarm(c, it.getInt("id"), it.getLong("at"), it.getString("t"), it.getString("b"))
        }
    }
}

fun lastLoc(c: Context): String {
    if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ""
    val lm = c.getSystemService(LocationManager::class.java)
    val l = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time } ?: return ""
    return JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("t", l.time).toString()
}

class MainActivity : Activity() {
    lateinit var web: WebView
    var tts: TextToSpeech? = null
    var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) for (l in listOf("mr-IN", "hi-IN")) {
                if ((tts?.setLanguage(Locale.forLanguageTag(l)) ?: -1) >= TextToSpeech.LANG_AVAILABLE) break
            }
        }
        web = WebView(this); setContentView(web)
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Native")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb = cb; startActivityForResult(p.createIntent(), 2); return true
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    fun startListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { startForegroundService(Intent(this, ListenService::class.java)) } catch (e: Exception) {}
    }
    override fun onResume() { super.onResume(); startListen(); refreshLoc() }
    fun refreshLoc() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 30) return
        val lm = getSystemService(LocationManager::class.java)
        val p = listOf("fused", "network", "gps").firstOrNull { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } } ?: return
        lm.getCurrentLocation(p, null, mainExecutor) { l ->
            if (l != null) web.evaluateJavascript("setLoc(" + JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("t", l.time) + ")", null)
        }
    }
    override fun onRequestPermissionsResult(rq: Int, p: Array<String>, r: IntArray) { startListen(); refreshLoc() }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        if (rq == 3 && rs == RESULT_OK) {
            val t = d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            web.evaluateJavascript("onVoice(" + JSONObject.quote(t) + ")", null)
        } else if (rq == 3) web.evaluateJavascript("onVoice('')", null)
        if (rq == 2) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d)); fileCb = null }
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class Bridge {
        @JavascriptInterface fun listen() = runOnUiThread {
            try { startService(Intent(this@MainActivity, ListenService::class.java).setAction("PAUSE")) } catch (e: Exception) {}
            try {
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "mr-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "बोला..."), 3)
            } catch (e: Exception) { web.evaluateJavascript("onVoice('')", null) }
        }
        @JavascriptInterface fun loc(): String = lastLoc(this@MainActivity)
        @JavascriptInterface fun open(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
        @JavascriptInterface fun speak(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "g") }
        @JavascriptInterface fun alarm(id: Int, at: Long, t: String, b: String) {
            scheduleAlarm(this@MainActivity, id, at, t, b)
            val p = getSharedPreferences("alarms", 0); val a = JSONArray(p.getString("l", "[]"))
            a.put(JSONObject().put("id", id).put("at", at).put("t", t).put("b", b)); p.edit().putString("l", a.toString()).apply()
        }
        @JavascriptInterface fun cancel(id: Int) {
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(PendingIntent.getBroadcast(this@MainActivity, id,
                Intent(this@MainActivity, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return)
        }
        @JavascriptInterface fun backup(name: String, json: String): Boolean = try {
            val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/GoldyBackup") }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(u)!!.use { it.write(json.toByteArray()) }; true
        } catch (e: Exception) { false }
    }
}
