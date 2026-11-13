package app.koehome

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data

/** 端末の連絡先から「電話番号を持つ人」を読み込む。 */
object ContactRepo {

    private class Row(val name: String, val number: String, val starred: Boolean, val phonetic: String?)

    fun load(cr: ContentResolver): List<Contact> {
        // 1) 電話番号つきの連絡先。1人に複数番号があれば「既定の番号」を優先して1つだけ使う。
        val rows = LinkedHashMap<Long, Row>()
        cr.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.STARRED, Phone.PHONETIC_NAME),
            null, null,
            "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (rows.containsKey(id)) continue
                val name = c.getString(1)?.trim().orEmpty()
                val number = c.getString(2)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                rows[id] = Row(name, number, c.getInt(3) != 0, c.getString(4))
            }
        }

        // 2) 姓・名・それぞれのふりがな・ニックネーム (「むすこ」など家族が登録した呼び名)
        val extras = HashMap<Long, MutableList<String>>()
        cr.query(
            Data.CONTENT_URI,
            arrayOf(
                Data.CONTACT_ID, Data.MIMETYPE,
                StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME,
                StructuredName.PHONETIC_GIVEN_NAME, StructuredName.PHONETIC_FAMILY_NAME,
                Nickname.NAME,
            ),
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!rows.containsKey(id)) continue
                val list = extras.getOrPut(id) { ArrayList() }
                if (c.getString(1) == Nickname.CONTENT_ITEM_TYPE) {
                    c.getString(6)?.let { list.add(it) }
                } else {
                    for (i in 2..5) c.getString(i)?.let { list.add(it) }
                }
            }
        }

        return rows.map { (id, r) ->
            Contact(id, r.name, r.number, r.starred, Contact.buildKeys(r.name, r.phonetic, extras[id].orEmpty()))
        }
    }
}
