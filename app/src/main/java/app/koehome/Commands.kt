package app.koehome

import java.text.Normalizer

/** 連絡先1件。keys は照合用に正規化した呼び名 (氏名・姓・名・ふりがな・ニックネーム)。 */
data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val starred: Boolean,
    val keys: List<String>,
) {
    /** 読み上げ用の呼び方。「さん」などが付いていなければ付ける。 */
    val spoken: String
        get() = if (HONORIFICS.any { name.endsWith(it) }) name else name + "さん"

    companion object {
        private val HONORIFICS = listOf("さん", "ちゃん", "くん", "君", "先生", "様", "さま")

        /** 氏名・ふりがな・ニックネームから照合キーを作る。1文字のキーは誤爆するので捨てる。 */
        fun buildKeys(name: String, phonetic: String?, extras: List<String>): List<String> {
            val raw = ArrayList<String>()
            for (s in listOf(name, phonetic ?: "")) {
                raw.add(s)
                raw.addAll(s.split(' ', '　'))
            }
            raw.addAll(extras)
            return raw.map { Text.norm(it) }.filter { it.length >= 2 }.distinct()
        }
    }
}

object Text {
    private const val PUNCT = "、。,.!?・「」『』()〜~ー-"

    /** 照合用の正規化: 全角半角をそろえ、カタカナをひらがなにし、空白と記号を除く。 */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (c in n) {
            when {
                c in 'ァ'..'ヶ' -> sb.append(c - 0x60)
                c.isWhitespace() -> {}
                PUNCT.indexOf(c) >= 0 -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** いま何を待っているか。 */
enum class Stage { IDLE, CONFIRM, CHOOSE, ASK_QUERY }

sealed class Command {
    object Yes : Command()
    object No : Command()
    object Stop : Command()
    object Exit : Command()
    object Next : Command()
    object Prev : Command()
    object Photos : Command()
    object Time : Command()
    object Date : Command()
    object Help : Command()
    object CallUnknown : Command()
    object Unknown : Command()
    object ScrollDown : Command()
    object ScrollUp : Command()
    object Back : Command()
    data class Call(val candidates: List<Contact>) : Command()
    data class Search(val query: String) : Command()
}

/**
 * 聞き取った言葉から操作を決める (ルールベース、通信不要)。
 * texts は音声認識の候補 (確からしい順)。
 */
object CommandParser {
    // 正規化後 (ひらがな・小文字・長音なし) の形で書く
    private val NO = listOf("いいえ", "いや", "ううん", "ちがう", "違う", "じゃない", "ではない", "やめ", "だめ", "駄目", "きゃんせる", "かけない", "まちがい", "間違")
    private val YES = listOf("はい", "うん", "ええ", "そう", "おねがい", "お願い", "かけて", "おけ", "ok", "いいよ", "いいです", "よろしい", "だいじょうぶ", "大丈夫")
    private val STOP = listOf("やめ", "もどる", "戻る", "もどって", "戻って", "とじ", "閉じ", "おわり", "終わり", "おしまい", "ほむ", "きゃんせる", "最初")
    private val EXIT = listOf("終了", "しゅうりょう", "いつもの", "あぷりをとじ", "あぷりを閉じ")
    private val SEARCH = listOf("しらべ", "調べ", "けんさく", "検索", "ぐぐ")
    private val SEARCH_RAW = listOf("調べ", "しらべ", "検索", "けんさく", "ググ", "ぐぐ")
    private val CLOSE = listOf("やめ", "とじ", "閉じ")
    private val DOWN = listOf("した", "下", "つづき", "続き")
    private val UP = listOf("うえ", "上")
    private val BACK = listOf("まえ", "前", "もどる", "戻る", "もどって", "戻って")
    private val NEXT = listOf("つぎ", "次", "すすむ", "すすんで", "進")
    private val PREV = listOf("まえ", "前")
    private val PHOTOS = listOf("しゃしん", "写真", "ふぉと", "あるばむ", "画像")
    private val CALL = listOf("でんわ", "電話", "かけて", "かける", "かけたい", "こる", "通話", "連絡")
    private val TIME = listOf("何時", "なんじ", "時間", "じかん", "時刻")
    private val DATE = listOf("何日", "なんにち", "日付", "ひづけ", "曜日", "ようび", "今日", "きょう")
    private val HELP = listOf("つかいかた", "使い方", "なにができ", "何ができ", "へるぷ", "できること")

    private fun String.hasAny(words: List<String>) = words.any { contains(it) }

    /** 候補を上から順に見て、最初に連絡先が当たった候補の結果を返す。最長一致の人だけ残す。 */
    fun matchContacts(texts: List<String>, pool: List<Contact>): List<Contact> {
        for (t in texts) {
            val n = Text.norm(t)
            var best = 0
            val hits = ArrayList<Pair<Contact, Int>>()
            for (c in pool) {
                val score = c.keys.filter { n.contains(it) }.maxOfOrNull { it.length } ?: 0
                if (score > 0) {
                    hits.add(c to score)
                    if (score > best) best = score
                }
            }
            if (hits.isNotEmpty()) return hits.filter { it.second == best }.map { it.first }
        }
        return emptyList()
    }

    /** 「東京の天気を調べて」→「東京の天気」。調べる言葉が無ければ空文字。 */
    fun extractQuery(raw: String): String {
        val s = raw.trim()
        val hit = SEARCH_RAW.map { it to s.lastIndexOf(it) }.filter { it.second >= 0 }.maxByOrNull { it.second }
            ?: return clean(s)
        val before = clean(s.substring(0, hit.second))
        if (before.isNotEmpty()) return before
        // 「検索 天気予報」のように後ろに言葉が来る形
        val after = s.substring(hit.second + hit.first.length)
            .replace(Regex("^(して|て|る|たい)?(ください|下さい|ほしい|欲しい)?"), "")
        return clean(after)
    }

    private fun clean(s: String): String = s
        .replace(Regex("(インターネット|ネット|ウェブ|グーグル|Google|google)で"), "")
        .trim().trim('、', '。', '？', '?', '！', '!', ' ', '　')
        .replace(Regex("(について|に関して|のこと)?(を|って|は)?$"), "")
        .trim()

    fun parse(
        texts: List<String>,
        contacts: List<Contact>,
        stage: Stage,
        choices: List<Contact>,
        photoOpen: Boolean,
        webOpen: Boolean = false,
    ): Command {
        if (texts.isEmpty()) return Command.Unknown
        val t = Text.norm(texts[0])

        if (stage == Stage.CONFIRM) {
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.No
            if (t.hasAny(YES)) return Command.Yes
            return Command.Unknown
        }
        if (stage == Stage.CHOOSE) {
            val m = matchContacts(texts, choices)
            if (m.isNotEmpty()) return Command.Call(m)
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.Stop
            return Command.Unknown
        }

        if (stage == Stage.ASK_QUERY) {
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.Stop
            return Command.Search(extractQuery(texts[0]))
        }

        if (t.hasAny(SEARCH) && !t.hasAny(CLOSE)) return Command.Search(extractQuery(texts[0]))
        if (webOpen) {
            if (t.hasAny(DOWN)) return Command.ScrollDown
            if (t.hasAny(UP)) return Command.ScrollUp
            if (t.hasAny(BACK)) return Command.Back
        }
        if (photoOpen) {
            if (t.hasAny(NEXT)) return Command.Next
            if (t.hasAny(PREV)) return Command.Prev
        }
        if (t.hasAny(EXIT)) return Command.Exit
        if (t.hasAny(STOP)) return Command.Stop
        if (t.hasAny(PHOTOS)) return Command.Photos

        val m = matchContacts(texts, contacts)
        if (t.hasAny(CALL)) return if (m.isNotEmpty()) Command.Call(m) else Command.CallUnknown
        if (t.hasAny(TIME)) return Command.Time
        if (t.hasAny(DATE)) return Command.Date
        if (t.hasAny(HELP)) return Command.Help
        // 名前だけ言われた場合も電話とみなす (発信前に必ず確認するので安全)
        if (m.isNotEmpty()) return Command.Call(m)
        return Command.Unknown
    }
}
