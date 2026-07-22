package com.authzmatrix.ui

import com.authzmatrix.core.CapturedToken
import com.authzmatrix.core.I18n
import com.authzmatrix.core.PersonaStore
import javax.swing.table.AbstractTableModel

/** Rich catalog of discovered JWTs: role, owner, issuer, alg, expiry and a security note. */
class TokenCatalogModel(private val store: PersonaStore) : AbstractTableModel() {

    private var data: List<CapturedToken> = emptyList()
    private val colKeys = arrayOf("col.role", "col.owner", "col.issuer", "col.alg", "col.exp", "col.security", "col.source")

    /** Re-read headers (e.g. after a language change). */
    fun structureChanged() = fireTableStructureChanged()

    /** One row per identity (sub+role), keeping the freshest token. Only valid (parseable,
     *  non-expired) JWTs are shown — expired/opaque tokens are hidden. */
    fun refresh() {
        data = store.capturedTokens
            .filter { it.info != null && !it.info!!.isExpired() }
            .groupBy { identityKey(it) }
            .map { (_, toks) -> toks.maxByOrNull { it.info?.exp ?: 0L }!! }
            .sortedWith(compareBy({ it.info?.role ?: "~" }, { it.info?.owner ?: "~" }))
        fireTableDataChanged()
    }

    private fun identityKey(t: CapturedToken): String {
        val i = t.info
        return if (i?.sub != null) "${i.sub}|${i.role ?: ""}" else "raw:${t.token}"
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
            0 -> info?.role ?: "-"
            1 -> info?.owner ?: "-"
            2 -> info?.issuer ?: "-"
            3 -> info?.alg ?: "-"
            4 -> when {
                info?.exp == null -> "-"
                info.isExpired() -> I18n.t("token.expired")
                else -> info.expInstant().toString()
            }
            5 -> info?.securityNote() ?: I18n.t("token.noJwt")
            6 -> t.source
            else -> ""
        }
    }
}
