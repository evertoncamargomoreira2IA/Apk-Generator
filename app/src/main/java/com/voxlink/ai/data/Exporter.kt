package com.voxlink.ai.data

import com.voxlink.ai.model.Entry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Meeting minutes ("ata") as real Office files, written directly (no external library):
 * Excel .xlsx (one row per sentence) and Word .docx (table, landscape).
 */
object Exporter {

    data class Meta(val title: String, val participants: String, val glossary: String)

    private val time = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
    private val dateTime = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun fileName(ext: String): String =
        "Ata_VoxLink_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + "." + ext

    // ------------------------------------------------------------------ Excel

    fun writeXlsx(file: File, meta: Meta, entries: List<Entry>) {
        val headers = listOf("Hora", "De", "Original", "Pinyin (original)", "Para", "Tradução", "Pinyin (tradução)", "Fonte")
        val widths = listOf(10, 6, 48, 40, 6, 48, 40, 14)
        val rows = StringBuilder()
        var r = 1
        fun cell(col: Int, row: Int, text: String, style: Int) =
            "<c r=\"${('A' + col)}$row\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"

        rows.append("<row r=\"$r\" ht=\"24\" customHeight=\"1\">").append(cell(0, r, meta.title, 3)).append("</row>"); r++
        rows.append("<row r=\"$r\">").append(cell(0, r, "Data: ${dateTime.format(Date())}   ·   Participantes: ${meta.participants}   ·   Glossário: ${meta.glossary}", 4)).append("</row>"); r++
        r++
        rows.append("<row r=\"$r\">")
        headers.forEachIndexed { i, h -> rows.append(cell(i, r, h, 1)) }
        rows.append("</row>")
        val headerRow = r
        r++
        entries.forEach { e ->
            val values = listOf(
                time.format(Date(e.time)), e.from.short, e.original, e.originalPinyin ?: "",
                e.to.short, e.translation, e.translationPinyin ?: "", e.provider,
            )
            rows.append("<row r=\"$r\">")
            values.forEachIndexed { i, v -> rows.append(cell(i, r, v, 2)) }
            rows.append("</row>")
            r++
        }
        val cols = widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="$headerRow" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<cols>$cols</cols><sheetData>$rows</sheetData>
<autoFilter ref="A$headerRow:H${maxOf(headerRow, r - 1)}"/>
<pageSetup orientation="landscape" paperSize="9"/>
</worksheet>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="4"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font><font><b/><sz val="14"/><color rgb="FF0B2D5B"/><name val="Arial"/></font><font><i/><sz val="9"/><color rgb="FF555555"/><name val="Arial"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B2D5B"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="5">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
        ZipOutputStream(file.outputStream()).use { z ->
            z.put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""")
            z.put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            z.put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Ata" sheetId="1" r:id="rId1"/></sheets>
<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Ata!${'$'}A${'$'}$headerRow:${'$'}H${'$'}${maxOf(headerRow, r - 1)}</definedName></definedNames>
</workbook>""")
            z.put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
            z.put("xl/worksheets/sheet1.xml", sheet)
            z.put("xl/styles.xml", styles)
        }
    }

    // ------------------------------------------------------------------ Word

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, size: Int = 20, color: String? = null): String {
        val props = buildString {
            append("<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Microsoft YaHei\" w:cs=\"Arial\"/>")
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (color != null) append("<w:color w:val=\"$color\"/>")
            append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        }
        return "<w:r><w:rPr>$props</w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }

    private fun para(vararg runs: String, spacingAfter: Int = 60) =
        "<w:p><w:pPr><w:spacing w:after=\"$spacingAfter\"/></w:pPr>${runs.joinToString("")}</w:p>"

    private fun tc(width: Int, content: String, shade: String? = null) =
        "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>" +
            (if (shade != null) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$shade\"/>" else "") +
            "</w:tcPr>$content</w:tc>"

    fun writeDocx(file: File, meta: Meta, entries: List<Entry>) {
        val w = listOf(1100, 6650, 6650) // total 14400 twips (landscape A4 minus margins)
        val body = StringBuilder()
        body.append(para(run(meta.title, bold = true, size = 32, color = "0B2D5B"), spacingAfter = 80))
        body.append(para(run("Data: ${dateTime.format(Date())}   ·   Participantes: ${meta.participants}   ·   Glossário: ${meta.glossary}", size = 18, color = "555555"), spacingAfter = 200))
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${w.sum()}\" w:type=\"dxa\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
            body.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>")
        }
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        w.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
        body.append("</w:tblGrid>")
        body.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
        listOf("Hora", "Original", "Tradução").forEachIndexed { i, h ->
            body.append(tc(w[i], para(run(h, bold = true, color = "FFFFFF")), "0B2D5B"))
        }
        body.append("</w:tr>")
        entries.forEach { e ->
            body.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            body.append(tc(w[0], para(run(time.format(Date(e.time)), size = 18))))
            val orig = StringBuilder(para(run("${e.from.short}  ", bold = true, size = 16, color = "1788FF"), run(e.original)))
            e.originalPinyin?.let { orig.append(para(run(it, italic = true, size = 18, color = "B07A00"))) }
            body.append(tc(w[1], orig.toString()))
            val tr = StringBuilder(para(run("${e.to.short}  ", bold = true, size = 16, color = "10A987"), run(e.translation)))
            e.translationPinyin?.let { tr.append(para(run(it, italic = true, size = 18, color = "B07A00"))) }
            body.append(tc(w[2], tr.toString()))
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
        body.append(para(run("Gerado pelo VoxLink AI — ${entries.size} frases.", italic = true, size = 16, color = "888888"), spacingAfter = 0))
        val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body<w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/><w:pgMar w:top="1000" w:right="1000" w:bottom="1000" w:left="1000" w:header="500" w:footer="500" w:gutter="0"/></w:sectPr></w:body>
</w:document>"""
        ZipOutputStream(file.outputStream()).use { z ->
            z.put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""")
            z.put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""")
            z.put("word/document.xml", doc)
        }
    }
}
