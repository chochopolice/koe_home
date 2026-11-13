package app.koehome

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * こえホーム: 音声で操作するホーム画面 (ランチャー)。
 * 画面はこの1枚だけ。「話す」ボタン → 聞き取り → 復唱して確認 → 実行。
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener, RecognitionListener {

    private class Photo(val uri: Uri, val millis: Long)

    private lateinit var message: TextView
    private lateinit var heard: TextView
    private lateinit var talk: Button
    private lateinit var confirmRow: View
    private lateinit var homePanel: View
    private lateinit var photoPanel: View
    private lateinit var photoView: ImageView
    private lateinit var photoInfo: TextView
    private lateinit var listHeader: TextView
    private lateinit var contactList: LinearLayout

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
    private var photos: List<Photo> = emptyList()
    private var photoIndex = 0
    private var photoOpen = false
    private var lastGreeting = 0L

    // ---------------------------------------------------------------- ライフサイクル

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC

        message = findViewById(R.id.message)
        heard = findViewById(R.id.heard)
        talk = findViewById(R.id.talk)
        confirmRow = findViewById(R.id.confirmRow)
        homePanel = findViewById(R.id.homePanel)
        photoPanel = findViewById(R.id.photoPanel)
        photoView = findViewById(R.id.photo)
        photoInfo = findViewById(R.id.photoInfo)
        listHeader = findViewById(R.id.listHeader)
        contactList = findViewById(R.id.contactList)

        talk.setOnClickListener { onTalkPressed() }
        findViewById<Button>(R.id.yes).setOnClickListener { run(Command.Yes) }
        findViewById<Button>(R.id.no).setOnClickListener { run(Command.No) }
        findViewById<Button>(R.id.photoNext).setOnClickListener { run(Command.Next) }
        findViewById<Button>(R.id.photoPrev).setOnClickListener { run(Command.Prev) }
        findViewById<Button>(R.id.photoClose).setOnClickListener { run(Command.Stop) }
        findViewById<View>(R.id.clockArea).setOnLongClickListener { showFamilyMenu(); true }

        tts = TextToSpeech(this, this)
        message.text = MSG_IDLE
        showFavorites()
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        reloadContacts()
        // しばらくぶりに画面を見たときだけ、使い方をひとこと案内する
        val now = SystemClock.elapsedRealtime()
        if (stage == Stage.IDLE && !photoOpen && (lastGreeting == 0L || now - lastGreeting > GREETING_INTERVAL_MS)) {
            lastGreeting = now
            say("ご用のときは、下の大きなボタンを押して、お話しください。")
        }
    }

    override fun onPause() {
        super.onPause()
        cancelListening()
        afterSpeech = null
        pendingSay = null
        if (ttsReady) tts.stop()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    /** ホームボタンを押されたら、必ず最初の画面に戻す。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        goHome()
        message.text = MSG_IDLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // ホーム画面なので閉じない。最初の状態に戻すだけ。
        goHome()
        message.text = MSG_IDLE
    }

    // ---------------------------------------------------------------- 権限

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun requestMissingPermissions() {
        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            photoPermission,
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
            heard.text = "※ 日本語の読み上げ音声が入っていません（設定 → テキスト読み上げ）"
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
        heard.text = "聞いています…"
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
        heard.text = "聞こえた言葉：「${texts[0]}」"
        run(CommandParser.parse(texts, contacts, stage, choices, photoOpen))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (listening && !t.isNullOrBlank()) heard.text = t
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_CLIENT && SystemClock.elapsedRealtime() < ignoreClientErrorUntil) return
        val wasListening = listening
        listening = false
        session++
        updateTalkButton()
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

    override fun onReadyForSpeech(params: Bundle?) { heard.text = "どうぞ、お話しください" }
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
                if (cmd.candidates.size == 1) askConfirm(cmd.candidates[0]) else askChoose(cmd.candidates)
            }
            Command.CallUnknown ->
                onMiss("どなたにかけるか、わかりませんでした。お名前を、もう一度お願いします。")
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
            Command.Photos -> openPhotos()
            Command.Next -> movePhoto(+1)
            Command.Prev -> movePhoto(-1)
            Command.Time ->
                say("いま、${SimpleDateFormat("ah時m分", Locale.JAPAN).format(Date())}です。")
            Command.Date ->
                say("今日は、${SimpleDateFormat("M月d日 EEEE", Locale.JAPAN).format(Date())}です。")
            Command.Help ->
                say("「だれだれさんに電話」、「写真を見せて」、「いま何時」と、話しかけてください。やめたいときは、「やめる」と言ってください。")
            Command.Unknown -> when (stage) {
                Stage.CONFIRM -> onMiss("「はい」か「いいえ」で、お答えください。")
                Stage.CHOOSE -> onMiss("どなたにかけますか。お名前を、もう一度お願いします。")
                Stage.IDLE -> {
                    misses = 0
                    say("すみません、わかりませんでした。「だれだれさんに電話」や、「写真を見せて」と言ってみてください。")
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
        closePhotoPanel()
        showFavorites()
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

    private fun showContactButtons(header: String, list: List<Contact>) {
        listHeader.text = header
        contactList.removeAllViews()
        for (c in list) {
            val b = Button(this).apply {
                text = "☎ ${c.name}"
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
        closePhotoPanel()
        stage = Stage.CONFIRM
        pendingContact = c
        choices = emptyList()
        showContactButtons("", emptyList())
        confirmRow.visibility = View.VISIBLE
        say("${c.spoken}に、電話をかけます。よろしいですか？") { startListening() }
    }

    private fun askChoose(candidates: List<Contact>) {
        closePhotoPanel()
        val list = candidates.take(MAX_CHOICES)
        stage = Stage.CHOOSE
        choices = list
        pendingContact = null
        confirmRow.visibility = View.GONE
        showContactButtons("かけたい人のボタンを押すか、お名前を言ってください", list)
        say("${list.joinToString("、") { it.spoken }}が見つかりました。どなたにかけますか？") { startListening() }
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

    // ---------------------------------------------------------------- 写真

    private fun openPhotos() {
        if (!granted(photoPermission)) {
            say("写真を見るための許可が必要です。")
            requestMissingPermissions()
            return
        }
        io.execute {
            val list = ArrayList<Photo>()
            try {
                val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(
                    base,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                    null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && list.size < MAX_PHOTOS) {
                        val taken = c.getLong(1)
                        val millis = if (taken > 0) taken else c.getLong(2) * 1000
                        list.add(Photo(ContentUris.withAppendedId(base, c.getLong(0)), millis))
                    }
                }
            } catch (e: Exception) {
                // 読めなければ「写真なし」として扱う
            }
            ui.post {
                if (list.isEmpty()) {
                    say("写真が見つかりませんでした。")
                } else {
                    goHome()
                    photos = list
                    photoIndex = 0
                    photoOpen = true
                    homePanel.visibility = View.GONE
                    photoPanel.visibility = View.VISIBLE
                    showPhoto()
                    say("いちばん新しい写真です。次の写真を見るときは、「次」と言うか、右下のボタンを押してください。")
                }
            }
        }
    }

    private fun movePhoto(delta: Int) {
        if (!photoOpen) {
            run(Command.Unknown)
            return
        }
        val next = photoIndex + delta
        when {
            next < 0 -> say("これが、いちばん新しい写真です。")
            next >= photos.size -> say("これが、最後の写真です。")
            else -> {
                photoIndex = next
                showPhoto()
                say(if (delta > 0) "次の写真です。" else "前の写真です。")
            }
        }
    }

    private fun showPhoto() {
        val index = photoIndex
        val p = photos[index]
        photoInfo.text = "${index + 1} / ${photos.size}　　${SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date(p.millis))}"
        io.execute {
            val bmp: Bitmap? = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, p.uri)) { decoder, info, _ ->
                    // 大きすぎる写真は縮小して読む (メモリ不足の防止)
                    val longSide = max(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(max(1, longSide / 2000))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (photoOpen && photoIndex == index) photoView.setImageBitmap(bmp)
            }
        }
    }

    private fun closePhotoPanel() {
        photoOpen = false
        photoPanel.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        photoView.setImageDrawable(null)
    }

    // ---------------------------------------------------------------- 家族向けメニュー (時計を長押し)

    private fun showFamilyMenu() {
        val items = arrayOf("アプリ一覧", "Androidの設定", "ホームアプリの切り替え", "連絡先を読み込み直す", "許可をもう一度確認する")
        AlertDialog.Builder(this)
            .setTitle("家族向けメニュー")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAppList()
                    1 -> open(Intent(Settings.ACTION_SETTINGS))
                    2 -> open(Intent(Settings.ACTION_HOME_SETTINGS))
                    3 -> reloadContacts()
                    4 -> requestMissingPermissions()
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

    private companion object {
        const val MSG_IDLE = "下の大きなボタンを押して、お話しください。"
        const val SPEECH_RATE = 0.9f              // 読み上げ速度 (1.0 が標準。小さいほどゆっくり)
        const val MAX_AUTO_RETRY = 2              // 自動で聞き直す回数
        const val MAX_FAVORITES = 5               // ホームに並べる連絡先ボタンの数
        const val MAX_CHOICES = 4                 // 同名候補を出す上限
        const val MAX_PHOTOS = 300                // さかのぼって見られる写真の枚数
        const val LISTEN_WATCHDOG_MS = 20_000L
        const val GREETING_INTERVAL_MS = 10 * 60 * 1000L
    }
}
