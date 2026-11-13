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

    // 同じ名前として扱う漢字のゆれ (左の字を右の字にそろえる)
    private val VARIANTS: Map<Char, Char> = HashMap<Char, Char>().apply {
        for (p in listOf(
            "斎斉", "齋斉", "齊斉", "邊辺", "邉辺", "髙高", "﨑崎", "嵜崎", "濱浜", "澤沢", "廣広",
            "嶋島", "嶌島", "國国", "櫻桜", "眞真", "惠恵", "德徳", "冨富", "條条", "龍竜", "瀨瀬", "栁柳",
        )) put(p[0], p[1])
    }

    /** 照合用の正規化: 全角半角をそろえ、カタカナをひらがなにし、空白と記号を除く。 */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (c in n) {
            when {
                c in 'ァ'..'ヶ' -> sb.append(c - 0x60)
                c.isWhitespace() -> {}
                PUNCT.indexOf(c) >= 0 -> {}
                else -> sb.append(VARIANTS[c] ?: c)
            }
        }
        return sb.toString()
    }
}

/** いま何を待っているか。 */
enum class Stage { IDLE, CONFIRM, CHOOSE, ASK_QUERY, ASK_NAME }

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
    /** spoken は「山田さん」のように、利用者が言った呼び方 (取れなければ空)。 */
    data class Call(val candidates: List<Contact>, val spoken: String = "") : Command()
    data class Search(val query: String) : Command()

    /** Google アシスタントに引き継ぐ。heard は言い直してもらう言葉 (無ければ空)。 */
    data class Assistant(val heard: String) : Command()
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
    private val ASSIST = listOf("あしすたんと", "じぇみに", "おけぐぐる", "ねえぐぐる")
    // 「写真見せて」から取り除く決まり文句。これ以外の言葉が残れば「条件つきの写真探し」とみなす
    private val PHOTO_FILLER = Regex("(しゃしん|写真|ふぉと|あるばむ|画像|みせて|見せて|みたい|見たい|みせ|見せ|だして|出して|ひらいて|開いて|ひょうじ|表示|ください|下さい|ちょうだい|おねがいします|お願いします|おねがい|お願い|ちょっと|また|して|を|の|が|は|も|ね|よ|で|に|と|か|な|さ|あ|え|お)")
    private val SEARCH = listOf("しらべ", "調べ", "けんさく", "検索", "ぐぐ")
    private val SEARCH_RAW = listOf("調べ", "しらべ", "検索", "けんさく", "ググ", "ぐぐ")
    private val CLOSE = listOf("やめ", "とじ", "閉じ")
    private val DOWN = listOf("した", "下", "つづき", "続き")
    private val UP = listOf("うえ", "上")
    private val BACK = listOf("まえ", "前", "もどる", "戻る", "もどって", "戻って")
    private val NEXT = listOf("つぎ", "次", "すすむ", "すすんで", "進", "ほか", "他", "べつ", "別", "もっと", "ふるい", "古い", "むかし", "昔")
    private val PREV = listOf("まえ", "前", "あたらしい", "新しい", "さっき")
    private val PHOTOS = listOf("しゃしん", "写真", "ふぉと", "あるばむ", "画像")
    private val CALL = listOf("でんわ", "電話", "かけて", "かける", "かけたい", "こる", "通話", "連絡")
    private val TIME = listOf("何時", "なんじ", "時間", "じかん", "時刻")
    private val DATE = listOf("何日", "なんにち", "日付", "ひづけ", "曜日", "ようび", "今日", "きょう")
    private val HELP = listOf("つかいかた", "使い方", "なにができ", "何ができ", "へるぷ", "できること")

    private fun String.hasAny(words: List<String>) = words.any { contains(it) }

    // ---- 名前のあいまい照合 ----

    private val CALL_CUT = listOf("でんわ", "電話", "こる", "通話", "連絡", "かけ")
    private val HONORIFIC = listOf("さん", "ちゃん", "くん", "君", "先生", "様", "さま", "氏")
    private val LEAD = Regex("^(えっと|ええと|あのう|あの|じゃあ|じゃ|では|それじゃ)")
    private val TAIL_PARTICLE = Regex("(に|へ|を|の|って)$")
    private val ORDINALS = listOf(
        listOf("1番", "1ばん", "一番", "いちばん", "1人目", "一人目", "ひとりめ", "最初", "さいしょ"),
        listOf("2番", "2ばん", "二番", "にばん", "2人目", "二人目", "ふたりめ"),
        listOf("3番", "3ばん", "三番", "さんばん", "3人目", "三人目", "さんにんめ"),
        listOf("4番", "4ばん", "四番", "よんばん", "4人目", "四人目", "よにんめ"),
        listOf("5番", "5ばん", "五番", "ごばん", "5人目", "五人目", "ごにんめ"),
        listOf("6番", "6ばん", "六番", "ろくばん", "6人目", "六人目", "ろくにんめ"),
    )

    /** 照合の結果。spoken は「山田さん」のように読み上げに使う呼び方 (取れなければ空)。 */
    class NameMatch(val contacts: List<Contact>, val spoken: String)

    /**
     * 話した文から「名前らしい部分」の候補を取り出す (正規化済みの文を渡す)。
     * 例:「山田さんに電話したいです」→ [山田さん, 山田]
     */
    fun nameCandidates(t: String): List<String> {
        var s = t
        val cut = CALL_CUT.map { s.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (cut != null) s = s.substring(0, cut)
        s = s.replace(LEAD, "")
        s = s.replace(TAIL_PARTICLE, "")
        val out = ArrayList<String>()
        out.add(s)                                             // 山田さん / お母さん
        HONORIFIC.firstOrNull { s.endsWith(it) }?.let { out.add(s.removeSuffix(it)) }   // 山田
        // 「山田さんの携帯」のように敬称のあとに言葉が続く形
        val first = HONORIFIC.map { s.indexOf(it) }.filter { it > 0 }.minOrNull()
        if (first != null) out.add(s.substring(0, first))
        return out.filter { it.isNotEmpty() }.distinct()
    }

    private fun spokenName(qs: List<String>): String {
        val a = qs.firstOrNull() ?: return ""
        if (a.length > 8) return ""
        return if (HONORIFIC.any { a.endsWith(it) }) a else a + "さん"
    }

    private fun isKanji(c: Char) = c in '\u4e00'..'\u9fff'

    private fun commonPrefix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }

    /** 1文字ちがい (置き換え・抜け・余分) までを「近い」とみなす。 */
    private fun near(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++diff > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    /**
     * 連絡先のあいまい照合。認識候補を上から順に試し、次の強さで当たった人を返す。
     *  1) 話した文に、登録名・姓・名・ふりがな・ニックネームがそのまま入っている
     *  2) 登録名の一部に、話した名前が入っている (「山田」→「山田太郎」)、または出だしが2文字以上同じ
     *  3) 1文字ちがい (「山田太朗」→「山田太郎」)。fuzzy=true のときだけ
     * 1) と 2) は一致した文字数が最も長い人たちをまとめて返す。
     */
    fun matchContacts(texts: List<String>, pool: List<Contact>, fuzzy: Boolean = true): NameMatch {
        var nearMatch: NameMatch? = null
        for (raw in texts) {
            val t = Text.norm(raw)
            val qs = nameCandidates(t)
            val spoken = spokenName(qs)
            var best = 0
            val hits = ArrayList<Pair<Contact, Int>>()
            for (c in pool) {
                var score = c.keys.filter { t.contains(it) }.maxOfOrNull { it.length } ?: 0
                for (q in qs) {
                    val ok = when {
                        q.length >= 2 -> c.keys.any { it.contains(q) }
                        isKanji(q[0]) -> c.keys.any { it.startsWith(q) }      // 「林」「森」など1文字の姓
                        else -> false
                    }
                    if (ok && q.length > score) score = q.length
                    // 出だしが同じ長さ (「山田太朗」と「山田太郎」は3文字) も一致の強さに数える
                    val head = c.keys.maxOfOrNull { commonPrefix(q, it) } ?: 0
                    if (head >= 2 && head > score) score = head
                }
                if (score > 0) {
                    hits.add(c to score)
                    if (score > best) best = score
                }
            }
            if (hits.isNotEmpty()) return NameMatch(hits.filter { it.second == best }.map { it.first }, spoken)

            if (fuzzy && nearMatch == null) {
                for (q in qs) {
                    if (q.length < 3) continue
                    val found = pool.filter { c ->
                        c.keys.any { k -> near(q, k) || (k.length > q.length && near(q, k.take(q.length))) }
                    }
                    if (found.isNotEmpty()) {
                        nearMatch = NameMatch(found, spoken)
                        break
                    }
                }
            }
        }
        return nearMatch ?: NameMatch(emptyList(), "")
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
            // 「2番」「最初の人」のように順番で選ぶ
            for ((i, words) in ORDINALS.withIndex()) {
                if (i < choices.size && t.hasAny(words)) return Command.Call(listOf(choices[i]))
            }
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.Stop
            val m = matchContacts(texts, choices)
            // 絞り込めたときだけ進む (「山田さん」と言い直しただけなら聞き直す)
            if (m.contacts.isNotEmpty() && (m.contacts.size < choices.size || choices.size == 1)) {
                return Command.Call(m.contacts, m.spoken)
            }
            return Command.Unknown
        }
        if (stage == Stage.ASK_NAME) {
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.Stop
            val m = matchContacts(texts, contacts)
            return if (m.contacts.isNotEmpty()) Command.Call(m.contacts, m.spoken) else Command.CallUnknown
        }

        if (stage == Stage.ASK_QUERY) {
            if (t.hasAny(NO) || t.hasAny(STOP)) return Command.Stop
            return Command.Search(extractQuery(texts[0]))
        }

        if (t.hasAny(ASSIST)) return Command.Assistant("")
        if (t.hasAny(SEARCH) && !t.hasAny(CLOSE)) return Command.Search(extractQuery(texts[0]))
        if (webOpen) {
            if (t.hasAny(DOWN)) return Command.ScrollDown
            if (t.hasAny(UP)) return Command.ScrollUp
            if (t.hasAny(BACK)) return Command.Back
        }
        if (photoOpen) {
            if (t.hasAny(NEXT)) return Command.Next
            if (t.hasAny(PREV)) return Command.Prev
            // 写真を見ている最中の「写真見せて」は、最初に戻さず次の1枚へ進める
            if (t.hasAny(PHOTOS)) return Command.Next
        }
        if (t.hasAny(EXIT)) return Command.Exit
        if (t.hasAny(STOP)) return Command.Stop
        if (t.hasAny(PHOTOS)) {
            // 「去年の桜の写真」のように条件がつくものは、探すのが得意な Google アシスタントに任せる
            val rest = t.replace(PHOTO_FILLER, "")
            return if (rest.isNotEmpty()) Command.Assistant(texts[0].trim()) else Command.Photos
        }

        val calling = t.hasAny(CALL)
        // 1文字ちがいまで許すのは、電話だとはっきり言われたときだけ
        val m = matchContacts(texts, contacts, fuzzy = calling)
        if (calling) return if (m.contacts.isNotEmpty()) Command.Call(m.contacts, m.spoken) else Command.CallUnknown
        if (t.hasAny(TIME)) return Command.Time
        if (t.hasAny(DATE)) return Command.Date
        if (t.hasAny(HELP)) return Command.Help
        // 名前だけ言われた場合も電話とみなす (発信前に必ず確認するので安全)
        if (m.contacts.isNotEmpty()) return Command.Call(m.contacts, m.spoken)
        return Command.Unknown
    }
}
