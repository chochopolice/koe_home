package app.koehome

/**
 * 家族への通知 (SMS) の、端末に依存しない部分。
 * 段階の判定・文面・電話番号の読み取りだけを持つので、PC 上で単体テストできる。
 */
object CareLogic {
    /** 通知は 1 (24時間)・2 (48時間)・3 (72時間) の3段階で打ち止め。 */
    const val MAX_STAGE = 3
    const val MAX_NUMBERS = 3

    /**
     * 最後に開いてからの経過時間をもとに、いま届いているはずの段階 (0〜3) を返す。
     * unitMs は1段階ぶんの長さ (通常は24時間)。
     */
    fun stageDue(elapsedMs: Long, unitMs: Long): Int {
        if (elapsedMs < 0 || unitMs <= 0) return 0
        return minOf(MAX_STAGE.toLong(), elapsedMs / unitMs).toInt()
    }

    private fun who(name: String): String = if (name.isBlank()) "ご家族" else name.trim() + "さん"

    /** 段階ごとの文面。72時間は緊急度が高いので内容を変える。 */
    fun message(stage: Int, name: String): String = when (stage) {
        1 -> "【こえホーム】${who(name)}のスマホが24時間使われていません。念のためご連絡ください。"
        2 -> "【こえホーム】${who(name)}のスマホが48時間使われていません。連絡がつくかご確認ください。"
        else -> "【こえホーム・至急】${who(name)}のスマホが72時間使われていません。体調不良などの可能性があります。すぐに安否をご確認ください。"
    }

    fun testMessage(name: String): String =
        "【こえホーム】テスト送信です。${who(name)}のスマホが長時間使われないとき、この番号にお知らせします。"

    /**
     * 入力欄の文字列から電話番号を取り出す。カンマ・読点・改行で区切り、
     * 空白・ハイフン・かっこは取り除く。数字が10〜15桁のものだけを、最大3件まで返す。
     */
    fun parseNumbers(raw: String): List<String> =
        raw.split(',', '、', '，', '\n', ';')
            .map { part ->
                val t = java.text.Normalizer.normalize(part, java.text.Normalizer.Form.NFKC).trim()
                val digits = t.filter { it.isDigit() }
                if (t.startsWith("+")) "+$digits" else digits
            }
            .filter { it.count { c -> c.isDigit() } in 10..15 }
            .distinct()
            .take(MAX_NUMBERS)
}
