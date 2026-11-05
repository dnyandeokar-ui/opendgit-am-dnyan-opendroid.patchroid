package com.dnyan.goldy

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.*
import android.speech.*
import android.speech.tts.*
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ListenService : Service() {
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var web: WebView? = null
    private var ready = false
    private var speaking = false
    private var awaitingUntil = 0L
    private var pausedUntil = 0L
    private val h = Handler(Looper.getMainLooper())
    private val wake = Regex("goldy|golde?n|गोल्डी|गोल्डि|गोलडी|गोल्डन|गोल्डे", RegexOption.IGNORE_CASE)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (i?.action == "PAUSE") { pausedUntil = System.currentTimeMillis() + 25000; sr?.cancel(); return START_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("listen", "Goldy ऐकत आहे", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9, Intent(this, ListenService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "listen").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Goldy ऐकत आहे").setContentText("'गोल्डी' म्हणा").setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "थांबवा", stop).build()).build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (web == null) init()
        return START_STICKY
    }

    private fun init() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                for (l in listOf("mr-IN", "hi-IN")) if ((tts?.setLanguage(Locale.forLanguageTag(l)) ?: -1) >= TextToSpeech.LANG_AVAILABLE) break
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { h.post { speaking = false; listen() } }
                    override fun onError(u: String?) { h.post { speaking = false; listen() } }
                })
            }
        }
        val w = WebView(this)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.addJavascriptInterface(SB(), "Native")
        w.webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView?, u: String?) { ready = true } }
        w.loadUrl("file:///android_asset/index.html")
        web = w
        listen()
    }

    private fun listen() {
        if (speaking || System.currentTimeMillis() < pausedUntil) { h.postDelayed({ listen() }, 1500); return }
        sr?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { h.postDelayed({ listen() }, 5000); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                handle(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""); again()
            }
            override fun onError(e: Int) { again() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "mr-IN"))
    }

    private fun again() { h.postDelayed({ if (!speaking) listen() }, 600) }

    private fun handle(t: String) {
        if (t.isBlank() || !ready) return
        val m = wake.find(t)
        val awaiting = System.currentTimeMillis() < awaitingUntil
        if (m == null && !awaiting) return
        val cmd = (if (m != null) t.substring(m.range.last + 1) else t).trim(' ', ',', '.', '।')
        if (cmd.length < 2) { awaitingUntil = System.currentTimeMillis() + 10000; say("बोला"); return }
        awaitingUntil = 0
        web?.evaluateJavascript("ask(" + JSONObject.quote(cmd) + ")", null)
    }

    private fun say(t: String) {
        val e = tts ?: return
        speaking = true; sr?.cancel()
        e.speak(t, TextToSpeech.QUEUE_FLUSH, null, "g")
        h.postDelayed({ if (speaking) { speaking = false; listen() } }, 20000)
    }

    inner class SB {
        @JavascriptInterface fun speak(t: String) { h.post { say(t) } }
        @JavascriptInterface fun listen() {}
        @JavascriptInterface fun loc(): String = lastLoc(this@ListenService)
        @JavascriptInterface fun open(u: String) {}
        @JavascriptInterface fun backup(n: String, j: String): Boolean = false
        @JavascriptInterface fun alarm(id: Int, at: Long, t: String, b: String) {
            scheduleAlarm(this@ListenService, id, at, t, b)
            val p = getSharedPreferences("alarms", 0); val a = JSONArray(p.getString("l", "[]"))
            a.put(JSONObject().put("id", id).put("at", at).put("t", t).put("b", b)); p.edit().putString("l", a.toString()).apply()
        }
        @JavascriptInterface fun cancel(id: Int) {
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(PendingIntent.getBroadcast(this@ListenService, id,
                Intent(this@ListenService, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return)
        }
    }

    override fun onDestroy() { sr?.destroy(); tts?.shutdown(); web?.destroy(); super.onDestroy() }
}
