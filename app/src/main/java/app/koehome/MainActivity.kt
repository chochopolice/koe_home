package app.koehome

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * こえホーム: 音声で操作するホーム画面 (ランチャー)。
 * 画面はこの1枚だけ。「話す」ボタン → 聞き取り → 復唱して確認 → 実行。
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener, RecognitionListener {

    private lateinit var message: TextView
    private lateinit var heard: TextView
    private lateinit var talk: Button
    private lateinit var confirmRow: View
    private lateinit var homePanel: View
    private lateinit var listHeader: TextView
    private lateinit var contactList: LinearLayout
    private lateinit var webPanel: View
    private lateinit var webView: WebView
    private lateinit var clockArea: View
    private lateinit var exitButton: View
    private lateinit var lineButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // 読み上げ
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingSay: Pair<String, (() -> Unit)?>? = null
    private var seq = 0
    private var latestId = ""
    private var afterSpeech: (() -> Unit)? = null
    private var keepSpeakingOnPause = false   // 別のアプリを開いたあとも案内を最後まで読む

    // 聞き取り
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var session = 0
    private var ignoreClientErrorUntil = 0L
    private var misses = 0

    // 状態
    private var stage = Stage.IDLE
    private var contacts: List<Contact> = emptyList()
    private var choices: List<Contact> = emptyList()
    private var pendingContact: Contact? = null
    private var webOpen = false
    private var lastGreeting = 0L

    // ---------------------------------------------------------------- ライフサイクル

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarPadding()
        registerBackHandler()
        volumeControlStream = AudioManager.STREAM_MUSIC

        message = findViewById(R.id.message)
        heard = findViewById(R.id.heard)
        talk = findViewById(R.id.talk)
        confirmRow = findViewById(R.id.confirmRow)
        homePanel = findViewById(R.id.homePanel)
        listHeader = findViewById(R.id.listHeader)
        contactList = findViewById(R.id.contactList)

        webPanel = findViewById(R.id.webPanel)
        webView = findViewById(R.id.web)
        clockArea = findViewById(R.id.clockArea)
        exitButton = findViewById(R.id.exit)
        setUpWebView()

        talk.setOnClickListener { onTalkPressed() }
        findViewById<Button>(R.id.openPhotos).apply {
            text = iconLabel(R.drawable.ic_photo, "写真を見る", 28f)
            setOnClickListener { run(Command.Photos) }
        }
        lineButton = findViewById(R.id.openLine)
        lineButton.setOnClickListener { run(Command.Line) }
        findViewById<Button>(R.id.exit).text = iconLabel(R.drawable.ic_home, "✕ とじて、いつもの画面へ", 22f)
        findViewById<Button>(R.id.webDown).setOnClickListener { run(Command.ScrollDown) }
        findViewById<Button>(R.id.webUp).setOnClickListener { run(Command.ScrollUp) }
        findViewById<Button>(R.id.webBack).setOnClickListener { run(Command.Back) }
        findViewById<Button>(R.id.webClose).setOnClickListener { run(Command.Stop) }
        findViewById<Button>(R.id.yes).setOnClickListener { run(Command.Yes) }
        findViewById<Button>(R.id.no).setOnClickListener { run(Command.No) }
        findViewById<Button>(R.id.exit).setOnClickListener { exitToUsualHome() }
        findViewById<View>(R.id.clockArea).setOnLongClickListener { showFamilyMenu(); true }

        tts = TextToSpeech(this, this)
        message.text = MSG_IDLE
        showFavorites()
        requestMissingPermissions()
        offerAutoStartSetup()
    }

    override fun onResume() {
        super.onResume()
        showSub("")
        reloadContacts()
        WatchService.sync(this)   // 許可の画面から戻ってきたときにも反映する
        refreshLineButton()
        webView.onResume()
        // しばらくぶりに画面を見たときだけ、使い方をひとこと案内する
        val now = SystemClock.elapsedRealtime()
        if (stage == Stage.IDLE && !webOpen && (lastGreeting == 0L || now - lastGreeting > GREETING_INTERVAL_MS)) {
            lastGreeting = now
            say("ご用のときは、下の大きなボタンを押して、お話しください。")
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        cancelListening()
        afterSpeech = null
        pendingSay = null
        if (ttsReady && !keepSpeakingOnPause) tts.stop()
        keepSpeakingOnPause = false
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        io.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    /** ホームボタンを押されたら、必ず最初の画面に戻す。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        goHome()
        showSub("")
        message.text = MSG_IDLE
    }

    /**
     * Android 13 以降の新しい「戻る」の受け取り方。
     * 対象 API 36 では onBackPressed が呼ばれなくなるため、こちらで受ける。
     */
    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { handleBack() },
            )
        }
    }

    /**
     * 対象 API 35 以降は画面が端まで広がるため、ステータスバーや
     * ナビゲーションバーに重ならないよう、その分だけ内側に余白を足す。
     */
    private fun applySystemBarPadding() {
        val base = dp(16)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft, base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight, base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (webOpen && webView.canGoBack()) {
            webView.goBack()
            return
        }
        // ホーム画面なので閉じない。最初の状態に戻すだけ。
        goHome()
        showSub("")
        message.text = MSG_IDLE
    }

    // ---------------------------------------------------------------- 権限

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        ).filterNot { granted(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reloadContacts()
    }

    // ---------------------------------------------------------------- 読み上げ

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.JAPAN)
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (!ttsReady) {
            ttsFailed = true
            showSub("※ 日本語の読み上げ音声が入っていません（設定 → テキスト読み上げ）", HINT_KEEP_MS)
        } else {
            tts.setSpeechRate(SPEECH_RATE)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { ui.post { onSpeechFinished(utteranceId) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { ui.post { onSpeechFinished(utteranceId) } }
            })
        }
        pendingSay?.let { (text, after) ->
            pendingSay = null
            say(text, after)
        }
    }

    /** 画面に表示しつつ読み上げる。after は読み終わったあとに実行する (聞き取り開始・発信など)。 */
    private fun say(text: String, after: (() -> Unit)? = null) {
        message.text = text
        cancelListening()
        when {
            ttsFailed -> {
                // 声が出せない端末でも、表示だけで先へ進める
                afterSpeech = null
                if (after != null) ui.postDelayed(after, 600)
            }
            !ttsReady -> pendingSay = text to after
            else -> {
                latestId = "u${++seq}"
                afterSpeech = after
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, latestId)
            }
        }
    }

    private fun onSpeechFinished(id: String?) {
        if (id != latestId) return
        val next = afterSpeech
        afterSpeech = null
        next?.invoke()
    }

    // ---------------------------------------------------------------- 聞き取り

    private fun onTalkPressed() {
        if (listening) {
            // 話し終わりの合図として扱う
            recognizer?.stopListening()
        } else {
            misses = 0
            startListening()
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            message.text = "マイクの許可が必要です。"
            requestMissingPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            message.text = "この電話では、声の聞き取りが使えません。"
            return
        }
        afterSpeech = null
        if (ttsReady) tts.stop()
        cancelListening()

        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        val mySession = ++session
        updateTalkButton()
        showSub("聞いています…", LISTEN_WATCHDOG_MS)
        r.startListening(intent)

        // 認識エンジンが黙り込んだときの保険
        ui.postDelayed({
            if (listening && session == mySession) {
                cancelListening()
                message.text = "うまく聞き取れませんでした。もう一度ボタンを押してください。"
            }
        }, LISTEN_WATCHDOG_MS)
    }

    private fun cancelListening() {
        if (listening) {
            ignoreClientErrorUntil = SystemClock.elapsedRealtime() + 800
            recognizer?.cancel()
            listening = false
            session++
            updateTalkButton()
            showSub("")
        }
    }

    private fun updateTalkButton() {
        talk.text = if (listening) "聞いています…" else "🎤 話す"
        talk.backgroundTintList =
            ColorStateList.valueOf(getColor(if (listening) R.color.talk_active else R.color.talk))
    }

    override fun onResults(results: Bundle?) {
        listening = false
        session++
        updateTalkButton()
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            .filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            onMiss()
            return
        }
        showSub("聞こえた言葉：「${texts[0]}」")
        run(CommandParser.parse(texts, contacts, stage, choices, false, webOpen))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (listening && !t.isNullOrBlank()) showSub(t)
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_CLIENT && SystemClock.elapsedRealtime() < ignoreClientErrorUntil) return
        val wasListening = listening
        listening = false
        session++
        updateTalkButton()
        showSub("")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onMiss()
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                say("インターネットにつながっていないため、聞き取りができません。")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                message.text = "マイクの許可が必要です。"
                requestMissingPermissions()
            }
            else -> {
                // 認識エンジンの一時的な不調。作り直して次回に備える。
                recognizer?.destroy()
                recognizer = null
                if (wasListening) message.text = "もう一度ボタンを押して、お話しください。"
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { showSub("どうぞ、お話しください", LISTEN_WATCHDOG_MS) }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    /** 聞き取れなかった・意味が取れなかったとき。2回までは自動で聞き直す。 */
    private fun onMiss(prompt: String = "聞き取れませんでした。もう一度、お話しください。") {
        misses++
        if (misses <= MAX_AUTO_RETRY) {
            say(prompt) { startListening() }
        } else {
            misses = 0
            val wasAsking = stage != Stage.IDLE
            goHome()
            say(
                if (wasAsking) "うまく聞き取れなかったので、やめました。もう一度ボタンを押してお試しください。"
                else "うまく聞き取れませんでした。画面のボタンを押すか、もう一度お試しください。"
            )
        }
    }

    // ---------------------------------------------------------------- 操作の実行

    private fun run(cmd: Command) {
        if (cmd != Command.Unknown && cmd != Command.CallUnknown) misses = 0
        when (cmd) {
            is Command.Call -> {
                if (cmd.candidates.size == 1) askConfirm(cmd.candidates[0]) else askChoose(cmd.candidates, cmd.spoken)
            }
            Command.CallUnknown -> {
                // 次のひとことは「名前だけ」でも電話の相手として受け取る
                goHome()
                stage = Stage.ASK_NAME
                onMiss("どなたにかけるか、わかりませんでした。お名前を、もう一度お願いします。")
            }
            Command.Yes -> {
                val c = pendingContact
                if (stage == Stage.CONFIRM && c != null) placeCall(c)
            }
            Command.No -> {
                goHome()
                say("やめました。")
            }
            Command.Stop -> {
                goHome()
                say("最初の画面に、もどりました。")
            }
            Command.Exit -> {
                goHome()
                say("いつもの画面に、もどります。") { exitToUsualHome() }
            }
            Command.Photos -> openGooglePhotos()
            Command.Line -> openLine()
            is Command.Assistant -> handOffToAssistant(cmd.heard)
            is Command.Search -> if (cmd.query.isBlank()) askQuery() else openWeb(cmd.query)
            Command.ScrollDown -> if (webOpen) webView.pageDown(false) else run(Command.Unknown)
            Command.ScrollUp -> if (webOpen) webView.pageUp(false) else run(Command.Unknown)
            Command.Back -> {
                if (webOpen && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    goHome()
                    say("最初の画面に、もどりました。")
                }
            }
            // アプリ内の写真表示は廃止 (写真は Google フォトなどの写真アプリで開く)
            Command.Next, Command.Prev -> run(Command.Unknown)
            Command.Time ->
                say("いま、${SimpleDateFormat("ah時m分", Locale.JAPAN).format(Date())}です。")
            Command.Date ->
                say("今日は、${SimpleDateFormat("M月d日 EEEE", Locale.JAPAN).format(Date())}です。")
            Command.Greeting -> say("はい。ご用件を、どうぞ。")
            Command.Help ->
                say("「だれだれさんに電話」、「写真を見せて」、「いま何時」と、話しかけてください。調べものは、「グアムの天気は」のように、そのまま聞いてください。やめたいときは、「やめる」と言ってください。")
            Command.Unknown -> when (stage) {
                Stage.CONFIRM -> onMiss("「はい」か「いいえ」で、お答えください。")
                Stage.CHOOSE -> onMiss("どなたにかけますか。お名前を、もう一度お願いします。")
                Stage.ASK_QUERY -> onMiss("何を調べるか、もう一度お願いします。")
                Stage.ASK_NAME -> onMiss("どなたにかけますか。お名前を、もう一度お願いします。")
                Stage.IDLE -> {
                    misses = 0
                    say("すみません、聞き取れませんでした。もう一度、お話しください。")
                }
            }
        }
    }

    /** 確認待ち・写真表示をすべて解除して最初の状態に戻す。 */
    private fun goHome() {
        cancelListening()
        afterSpeech = null
        stage = Stage.IDLE
        pendingContact = null
        choices = emptyList()
        confirmRow.visibility = View.GONE
        closeWebPanel()
        showFavorites()
    }

    // ---------------------------------------------------------------- とじる・自動起動

    /** こえホームをとじて、いつものホーム画面を出す。 */
    private fun exitToUsualHome() {
        goHome()
        message.text = MSG_IDLE
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val current = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (current?.activityInfo?.packageName == packageName) {
            // こえホーム自身が既定のホームになっている場合は、もう一方のホームアプリを直接開く
            val other = packageManager.queryIntentActivities(home, 0).map { it.activityInfo }
                .firstOrNull { it.packageName != packageName && it.packageName != "com.android.settings" }
            if (other == null) {
                message.text = "ほかのホーム画面が見つかりませんでした。"
                return
            }
            home.setClassName(other.packageName, other.name)
            open(home)
            return
        }
        // ふつうのアプリとして動いているとき: 自分を後ろに下げる。
        // 直前に使っていたアプリがあればそこへ、なければいつものホームに戻る。
        if (!moveTaskToBack(true)) open(home)
    }

    /** 初回だけ、電源投入時の自動起動に必要な許可を案内する。 */
    private fun offerAutoStartSetup() {
        val prefs = getSharedPreferences("koehome", MODE_PRIVATE)
        if (Settings.canDrawOverlays(this) || prefs.getBoolean("askedAutoStart", false)) return
        prefs.edit().putBoolean("askedAutoStart", true).apply()
        AlertDialog.Builder(this)
            .setTitle("画面を点けたとき自動で開く")
            .setMessage("画面を点けたとき自動で開くには「他のアプリの上に重ねて表示」の許可が必要です。次の画面で こえホーム をオンにしてください。\n（あとから設定する場合は、時計を長押し）")
            .setPositiveButton("設定を開く") { _, _ -> openAutoStartSetting() }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun openAutoStartSetting() {
        open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- 電話

    private fun reloadContacts() {
        if (!granted(Manifest.permission.READ_CONTACTS)) return
        io.execute {
            val loaded = try {
                ContactRepo.load(contentResolver)
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                contacts = loaded
                if (stage == Stage.IDLE) showFavorites()
            }
        }
    }

    private fun showFavorites() {
        val fav = contacts.filter { it.starred }.take(MAX_FAVORITES)
        showContactButtons(
            if (fav.isEmpty()) "連絡先アプリで ★（お気に入り）をつけた人が、ここに大きなボタンで出ます。"
            else "ボタンを押しても電話できます",
            fav,
        )
    }

    private fun showContactButtons(header: String, list: List<Contact>, numbered: Boolean = false) {
        listHeader.text = header
        contactList.removeAllViews()
        for ((i, c) in list.withIndex()) {
            val b = Button(this).apply {
                text = if (numbered) "${i + 1}番　${c.name}" else iconLabel(R.drawable.ic_call, c.name, 28f)
                textSize = 28f
                isAllCaps = false
                minimumHeight = dp(88)
                setTextColor(getColor(R.color.white))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.contact))
                setOnClickListener { askConfirm(c) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            contactList.addView(b, lp)
        }
    }

    private fun askConfirm(c: Contact) {
        stage = Stage.CONFIRM
        pendingContact = c
        choices = emptyList()
        showContactButtons("", emptyList())
        confirmRow.visibility = View.VISIBLE
        say("${c.spoken}に、電話をかけます。よろしいですか？") { startListening() }
    }

    /** 候補が複数いるとき:「山田さんは3人見つかりました。…どなたに電話しますか？」 */
    private fun askChoose(candidates: List<Contact>, spoken: String) {
        closeWebPanel()
        val list = candidates.take(MAX_CHOICES)
        stage = Stage.CHOOSE
        choices = list
        pendingContact = null
        confirmRow.visibility = View.GONE
        showContactButtons("かけたい人のボタンを押すか、お名前か番号を言ってください", list, numbered = true)
        val who = if (spoken.isBlank()) "お名前の近い方が、" else "${spoken}は、"
        val names = list.mapIndexed { i, c -> "${i + 1}番、${c.spoken}" }.joinToString("。")
        val more = if (candidates.size > list.size) "画面には、最初の${list.size}人を出しています。" else ""
        say("$who${candidates.size}人見つかりました。$names。${more}どなたに電話しますか？") { startListening() }
    }

    private fun placeCall(c: Contact) {
        goHome()
        say("${c.spoken}に、電話をかけます。") {
            val uri = Uri.fromParts("tel", c.number, null)
            // 発信の許可がなければ、番号を入れた電話画面を開くところまで
            val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            try {
                startActivity(Intent(action, uri))
            } catch (e: Exception) {
                say("電話をかけられませんでした。")
            }
        }
    }

    // ---------------------------------------------------------------- LINE

    /** LINE が入っている端末でだけボタンを出す。アイコンは端末に入っている LINE のものを使う。 */
    private fun refreshLineButton() {
        val installed = packageManager.getLaunchIntentForPackage(LINE_APP) != null
        lineButton.visibility = if (installed) View.VISIBLE else View.GONE
        if (!installed) return
        val icon = try {
            packageManager.getApplicationIcon(LINE_APP)
        } catch (e: Exception) {
            null
        }
        lineButton.text = if (icon != null) iconLabel(icon, "LINE を開く", 28f) else "LINE を開く"
    }

    private fun openLine() {
        val intent = packageManager.getLaunchIntentForPackage(LINE_APP)
        if (intent == null) {
            say("ラインが、入っていません。")
            return
        }
        goHome()
        say("ラインを開きます。")
        keepSpeakingOnPause = ttsReady
        try {
            startActivity(intent)
        } catch (e: Exception) {
            keepSpeakingOnPause = false
            say("ラインを開けませんでした。")
        }
    }

    // ---------------------------------------------------------------- 写真

    /**
     * 写真アプリを開く (ボタン・「写真見せて」の両方)。
     * Google フォトがあればそれを、なければ端末の標準の写真アプリを開く。
     * こえホーム自身は写真を読まないので、写真の権限は要らない。
     */
    private fun openGooglePhotos() {
        val intent = packageManager.getLaunchIntentForPackage(GOOGLE_PHOTOS)
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        goHome()
        // 案内を読み上げながら、待たせずにすぐ Google フォトを開く
        say(PHOTOS_GUIDE)
        keepSpeakingOnPause = ttsReady
        try {
            startActivity(intent)
        } catch (e: Exception) {
            keepSpeakingOnPause = false
            say("写真を開けませんでした。")
        }
    }

    // ---------------------------------------------------------------- Google アシスタントへの引き継ぎ

    /**
     * 端末の「既定のアシスタント」(Google アシスタント / Gemini) を呼び出す。
     * 聞いた言葉をアシスタントに渡す公式の方法は無いため、もう一度話してもらう。
     */
    private fun handOffToAssistant(heardText: String) {
        goHome()
        val again = if (heardText.isBlank()) "もう一度、「オーケーグーグル」と呼びかけてから、お話しください。"
        else "もう一度、「オーケーグーグル、$heardText」と、お話しください。"
        say("グーグルアシスタントに代わります。$again") {
            message.text = MSG_IDLE
            val how = launchAssistant()
            // どの方法で呼び出したかを画面に残す (うまく動かないときの手がかり)
            showSub(if (how != null) "アシスタント呼び出し：$how" else "")
            if (how == null) {
                say("アシスタントを、呼び出せませんでした。")
                showSub("※ 設定 → アプリ → 標準のアプリ → デジタルアシスタント アプリ を確認してください", HINT_KEEP_MS)
            }
        }
    }

    /** 成功した方法の名前を返す。全部だめなら null。 */
    private fun launchAssistant(): String? {
        // 1) 正式な方法: ホームボタン長押しと同じく、既定のアシスタントを前面に出す
        try {
            if (showAssist(Bundle())) return "標準"
        } catch (e: Exception) {
            // 次の方法へ
        }
        // 2) アシスタントを開く汎用の呼び出し
        val tries = listOf(
            "アシスト" to Intent(Intent.ACTION_ASSIST),
            "音声アシスト" to Intent("android.intent.action.VOICE_ASSIST"),
            "音声コマンド" to Intent(Intent.ACTION_VOICE_COMMAND),
        )
        for ((name, intent) in tries) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return name
            } catch (e: Exception) {
                // 次の方法へ
            }
        }
        // 3) Gemini アプリ / Google アプリを直接開く
        for (pkg in listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(intent)
                return if (pkg.endsWith("bard")) "Geminiアプリ" else "Googleアプリ"
            } catch (e: Exception) {
                // 次へ
            }
        }
        return null
    }

    // ---------------------------------------------------------------- Web検索

    private fun setUpWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = WEB_TEXT_ZOOM
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            // ページ移動はアプリ内だけ。他のアプリを開くリンク (http/https 以外) は無視する。
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "https" && scheme != "http"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url == "about:blank") view.clearHistory()
            }
        }
    }

    private fun askQuery() {
        goHome()
        stage = Stage.ASK_QUERY
        say("何を調べますか？") { startListening() }
    }

    private fun openWeb(query: String) {
        goHome()
        webOpen = true
        homePanel.visibility = View.GONE
        clockArea.visibility = View.GONE      // 結果を広く見せるため、時計と「とじて…」ボタンは隠す
        exitButton.visibility = View.GONE
        webPanel.visibility = View.VISIBLE
        setCompactBottom(true)
        webView.loadUrl("https://www.google.com/search?hl=ja&safe=active&q=" + URLEncoder.encode(query, "UTF-8"))
        say("「$query」を調べました。指で上下に動かすか、下のボタンを押してください。")
    }

    /** Web 表示中だけ、メッセージを1行に、「話す」ボタンを低くする。 */
    private fun setCompactBottom(compact: Boolean) {
        message.textSize = if (compact) 18f else 26f
        message.minLines = if (compact) 1 else 2
        message.maxLines = if (compact) 1 else Int.MAX_VALUE
        message.ellipsize = if (compact) android.text.TextUtils.TruncateAt.END else null
        talk.textSize = if (compact) 28f else 40f
        talk.layoutParams = talk.layoutParams.apply { height = dp(if (compact) 80 else 132) }
    }

    private fun closeWebPanel() {
        if (webOpen) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
        webOpen = false
        webPanel.visibility = View.GONE
        setCompactBottom(false)
        clockArea.visibility = View.VISIBLE
        exitButton.visibility = View.VISIBLE
        homePanel.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 家族向けメニュー (時計を長押し)

    private fun showFamilyMenu() {
        val auto = if (Settings.canDrawOverlays(this)) "許可ずみ" else "未許可"
        val every = if (WatchService.isEnabled(this)) "オン" else "オフ"
        val items = arrayOf("アプリ一覧", "Androidの設定", "ホームアプリの切り替え", "連絡先を読み込み直す", "許可をもう一度確認する", "自動で開くための許可（いま: $auto）", "画面を点けるたびに開く（いま: $every）")
        AlertDialog.Builder(this)
            .setTitle("家族向けメニュー")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAppList()
                    1 -> open(Intent(Settings.ACTION_SETTINGS))
                    2 -> open(Intent(Settings.ACTION_HOME_SETTINGS))
                    3 -> reloadContacts()
                    4 -> requestMissingPermissions()
                    5 -> openAutoStartSetting()
                    6 -> WatchService.setEnabled(this, !WatchService.isEnabled(this))
                }
            }
            .setNegativeButton("とじる", null)
            .show()
    }

    private fun showAppList() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first }
        AlertDialog.Builder(this)
            .setTitle("アプリ一覧")
            .setItems(apps.map { it.first }.toTypedArray()) { _, which ->
                pm.getLaunchIntentForPackage(apps[which].second)?.let { open(it) }
            }
            .setNegativeButton("とじる", null)
            .show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            message.text = "開けませんでした。"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val clearSub = Runnable { heard.text = "" }

    /**
     * メッセージの下の補足行 (「聞こえた言葉」など) を出す。
     * 前回の結果が残り続けないよう、一定時間たったら自動で消す。空文字ならすぐ消す。
     */
    private fun showSub(text: CharSequence, keepMs: Long = SUB_KEEP_MS) {
        ui.removeCallbacks(clearSub)
        heard.text = text
        if (text.isNotEmpty()) ui.postDelayed(clearSub, keepMs)
    }

    /** 絵文字の代わりに、画像のアイコンを文字の先頭に置いたボタン用の文字列を作る。 */
    private fun iconLabel(resId: Int, label: String, textSp: Float): CharSequence {
        val icon = getDrawable(resId) ?: return label
        return iconLabel(icon, label, textSp)
    }

    private fun iconLabel(icon: Drawable, label: String, textSp: Float): CharSequence {
        val size = (textSp * ICON_SCALE * resources.displayMetrics.scaledDensity).toInt()
        icon.setBounds(0, 0, size, size)
        return SpannableString("*  $label").apply {
            setSpan(CenteredImageSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 文字の行の上下中央にそろえて画像を描く (標準の ImageSpan は下ぞろえのため)。 */
    private class CenteredImageSpan(icon: Drawable) : ImageSpan(icon) {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val bounds = drawable.bounds
            if (fm != null) {
                val font = paint.fontMetricsInt
                val center = (font.ascent + font.descent) / 2
                val half = bounds.height() / 2
                fm.ascent = minOf(font.ascent, center - half)
                fm.top = fm.ascent
                fm.descent = maxOf(font.descent, center + half)
                fm.bottom = fm.descent
            }
            return bounds.right
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val font = paint.fontMetricsInt
            val offsetY = y + (font.ascent + font.descent) / 2 - drawable.bounds.height() / 2
            canvas.save()
            canvas.translate(x, offsetY.toFloat())
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        const val MSG_IDLE = "下の大きなボタンを押して、お話しください。"
        const val SPEECH_RATE = 0.9f              // 読み上げ速度 (1.0 が標準。小さいほどゆっくり)
        const val MAX_AUTO_RETRY = 2              // 自動で聞き直す回数
        const val MAX_FAVORITES = 5               // ホームに並べる連絡先ボタンの数
        const val MAX_CHOICES = 6                 // 同名候補を出す上限
        const val GOOGLE_PHOTOS = "com.google.android.apps.photos"
        const val LINE_APP = "jp.naver.line.android"
        const val PHOTOS_GUIDE = "写真を開きます。写真を検索する場合は、「オーケー、グーグル」と呼びかけてから、お話しください。"
        const val ICON_SCALE = 1.5f               // ボタンのアイコンの大きさ (文字の高さの何倍か)
        const val WEB_TEXT_ZOOM = 130             // 検索結果の文字の大きさ (%)
        const val LISTEN_WATCHDOG_MS = 20_000L
        const val HINT_KEEP_MS = 60_000L          // 設定の案内 (※) を出しておく時間
        const val SUB_KEEP_MS = 12_000L           // 補足行 (聞こえた言葉など) を出しておく時間
        const val GREETING_INTERVAL_MS = 10 * 60 * 1000L
    }
}
