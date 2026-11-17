package com.authzmatrix.ui

import com.authzmatrix.core.CapturedToken
import com.authzmatrix.core.CookieSession
import com.authzmatrix.core.I18n
import com.authzmatrix.core.PersonaStore
import javax.swing.table.AbstractTableModel

/**
 * Catalog of discovered identities: JWTs (role/owner/alg/expiry) and cookie sessions
 * (cookie names/bundle). One row per identity, keeping the freshest value.
 */
class TokenCatalogModel(private val store: PersonaStore) : AbstractTableModel() {

    private var data: List<CapturedToken> = emptyList()
    private val colKeys = arrayOf(
        "col.type", "col.role", "col.owner", "col.issuer", "col.alg", "col.exp", "col.security", "col.source",
    )

    /** Re-read headers (e.g. after a language change). */
    fun structureChanged() = fireTableStructureChanged()

    /** One row per identity (JWT sub+role, or cookie session), freshest first. */
    fun refresh() {
        data = store.capturedTokens
            .filter { it.isCookie || (it.info != null && !it.info!!.isExpired()) }
            .groupBy { identityKey(it) }
            .map { (_, toks) ->
                toks.maxByOrNull { t -> if (t.isCookie) Long.MAX_VALUE else t.info?.exp ?: 0L }!!
            }
            .sortedWith(compareBy({ it.isCookie }, { it.info?.role ?: "~" }, { it.info?.owner ?: "~" }))
        fireTableDataChanged()
    }

    private fun identityKey(t: CapturedToken): String {
        val i = t.info
        return when {
            t.isCookie -> "cookie:${t.token}"
            i?.sub != null -> "${i.sub}|${i.role ?: ""}"
            else -> "raw:${t.token}"
        }
    }

    fun tokenAt(i: Int): CapturedToken? = data.getOrNull(i)

    override fun getRowCount() = data.size
    override fun getColumnCount() = colKeys.size
    override fun getColumnName(c: Int) = I18n.t(colKeys[c])
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getValueAt(r: Int, c: Int): Any {
        val t = data[r]
        val info = t.info
        return when (c) {
            0 -> if (t.isCookie) I18n.t("token.typeCookie") else I18n.t("token.typeJwt")
            1 -> if (t.isCookie) I18n.t("token.cookieRole") else info?.role ?: "-"
            2 -> info?.owner ?: "-"
            3 -> info?.issuer ?: "-"
            4 -> info?.alg ?: if (t.isCookie) I18n.t("token.algCookie") else "-"
            5 -> when {
                t.isCookie -> "-"
                info?.exp == null -> "-"
                info.isExpired() -> I18n.t("token.expired")
                else -> info.expInstant().toString()
            }
            6 -> if (t.isCookie) {
                I18n.t("token.cookieSecurity", CookieSession.describe(t.cookies))
            } else {
                info?.securityNote() ?: I18n.t("token.noJwt")
            }
            7 -> t.source
            else -> ""
        }
    }
}
