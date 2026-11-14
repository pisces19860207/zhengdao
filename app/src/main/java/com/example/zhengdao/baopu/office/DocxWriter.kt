// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376 Part 1（WordprocessingML：document/styles/numbering/header）、
//   DrawingML（wp:inline 图片嵌入）、OPC（[Content_Types].xml 与 .rels 关系）。
// 实现只用 JDK 自带 StringBuilder / java.util.zip —— 不引 poi-ooxml、不引 xmlbeans。
//
// 第六百三十二批 · docx 写出（**自研 OOXML 拼装**，判据⑤：零新增依赖）。
//
// 一套内置样式（用户口径：**不做样式编辑**）：
//   · 正文 15pt（sz 30 半磅）、行距 1.5（line=360 auto）；
//   · 标题 1/2/3 级 = 22pt / 17pt / 15pt（sz 44/34/30），带 outlineLvl ⇒ 导航窗格里认层级；
//   · 列表用 Word 自己的 numPr（无序 •／有序 1.），序号按块重起；
//   · 页眉一行（右下细线，9pt），表格单线边框、表头加粗。
package com.example.zhengdao.baopu.office

/** 一张待嵌入的图片（字节 + 探测到的宽高）。 */
internal data class OfficeImage(val bytes: ByteArray, val info: ImageInfo)

/** 一份关系（`document.xml.rels` 的一行）。 */
private data class DocxRel(val id: String, val type: String, val target: String)

/**
 * docx 装配器：模型（[DocxSpec]）→ 一个能打开的 `.docx` 字节包。
 *
 * 不做的事（派工单 §三）：不做模板、不写宏、不做公式、不生成封面/目录域。
 */
internal object DocxWriter {

    private const val REL = OoxmlNs.OFFICE_REL
    private const val NS_W = OoxmlNs.WORD

    /**
     * 生成 docx 字节。
     *
     * @param images 图片块路径 → 已读好的字节与尺寸（**由调用方先读、先验**：
     *   本函数不碰盘，JVM 测试可以喂假图；读不到/不是 PNG-JPEG 由工具层明确报错）
     */
    fun build(spec: DocxSpec, images: Map<String, OfficeImage>): ByteArray {
        val pictures = spec.blocks.filterIsInstance<DocxBlock.Picture>()
        require(pictures.size <= OfficeLimits.DOCX_MAX_IMAGES) { "图片超过上限" }
        require(pictures.all { images.containsKey(it.path) }) { "图片字节没准备好" }
        val ids = Ids(spec.header.isNotBlank())
        val imageIds = pictures.map { ids.image() }
        val pkg = OoxmlPackage()
        pkg.xml(OoxmlPackage.CONTENT_TYPES, contentTypes(spec.header.isNotBlank(), pictures, images))
        pkg.xml(OoxmlPackage.ROOT_RELS, rootRels())
        val body = DocxBody(spec, images, imageIds)
        val (bodyXml, orderedCount) = body.render()
        pkg.xml("word/document.xml", documentXml(bodyXml, ids.header))
        pkg.xml("word/styles.xml", stylesXml())
        pkg.xml("word/numbering.xml", numberingXml(orderedCount))
        pkg.xml("word/_rels/document.xml.rels", documentRels(ids, pictures, images))
        if (ids.header.isNotEmpty()) pkg.xml("word/header1.xml", headerXml(spec.header))
        pictures.forEachIndexed { i, pic ->
            val info = images.getValue(pic.path)
            pkg.binary("word/media/image${i + 1}.${info.info.ext}", info.bytes)
        }
        return pkg.build()
    }

    /** 关系 id 分配：styles=1、numbering=2、header=3（有页眉时）、图片从下一个起。 */
    private class Ids(hasHeader: Boolean) {
        private var next = 1
        val styles: String = rid()
        val numbering: String = rid()
        val header: String = if (hasHeader) rid() else ""
        fun image(): String = rid()
        private fun rid(): String = "rId${next++}"
    }

    // ── 包清单与关系 ──────────────────────────────────────────────────────

    private fun contentTypes(hasHeader: Boolean, pictures: List<DocxBlock.Picture>, images: Map<String, OfficeImage>): String {
        val exts = pictures.map { images.getValue(it.path).info.ext }.distinct()
        val ct = StringBuilder(XML_HEAD)
        ct.append("<Types xmlns=\"").append(OoxmlNs.CONTENT_TYPES).append("\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (ext in exts) {
            val mime = if (ext == "png") "image/png" else "image/jpeg"
            ct.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(mime).append("\"/>")
        }
        ct.append(override("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))
        ct.append(override("/word/styles.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"))
        ct.append(override("/word/numbering.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"))
        if (hasHeader) {
            ct.append(override("/word/header1.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"))
        }
        ct.append("</Types>")
        return ct.toString()
    }

    private fun override(part: String, type: String): String =
        "<Override PartName=\"$part\" ContentType=\"$type\"/>"

    private fun rootRels(): String = XML_HEAD +
        "<Relationships xmlns=\"" + OoxmlNs.PACKAGE_REL + "\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private fun documentRels(
        ids: Ids,
        pictures: List<DocxBlock.Picture>,
        images: Map<String, OfficeImage>,
    ): String {
        val rels = mutableListOf(
            DocxRel(ids.styles, "$REL/styles", "styles.xml"),
            DocxRel(ids.numbering, "$REL/numbering", "numbering.xml"),
        )
        if (ids.header.isNotEmpty()) rels += DocxRel(ids.header, "$REL/header", "header1.xml")
        pictures.forEachIndexed { i, pic ->
            val ext = images.getValue(pic.path).info.ext
            rels += DocxRel(imageRid(ids, i), "$REL/image", "media/image${i + 1}.$ext")
        }
        val head = XML_HEAD + "<Relationships xmlns=\"" + OoxmlNs.PACKAGE_REL + "\">"
        return rels.fold(head) { acc, rel ->
            acc + "<Relationship Id=\"${rel.id}\" Type=\"${rel.type}\" Target=\"${rel.target}\"/>"
        } + "</Relationships>"
    }

    /** 第 [index] 张图的 rId：与 [Ids.image] 的分配顺序一一对应（先 styles/序号，再 header，再按图片顺序）。 */
    private fun imageRid(ids: Ids, index: Int): String {
        val first = if (ids.header.isEmpty()) 3 else 4
        return "rId${first + index}"
    }

    // ── 部件 ──────────────────────────────────────────────────────────────

    private fun documentXml(body: String, headerRid: String): String {
        val head = StringBuilder(XML_HEAD)
        head.append("<w:document xmlns:w=\"").append(NS_W).append("\"")
        head.append(" xmlns:r=\"").append(REL).append("\"")
        head.append(" xmlns:wp=\"").append(OoxmlNs.DRAWING_WORD).append("\"")
        head.append(" xmlns:a=\"").append(OoxmlNs.DRAWING).append("\">")
        head.append("<w:body>").append(body)
        head.append(sectPr(headerRid))
        head.append("</w:body></w:document>")
        return head.toString()
    }

    private fun sectPr(headerRid: String): String {
        val ref = if (headerRid.isEmpty()) "" else "<w:headerReference w:type=\"default\" r:id=\"$headerRid\"/>"
        return "<w:sectPr>$ref" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"" +
            " w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/>" +
            "<w:cols w:space=\"425\"/>" +
            "<w:docGrid w:type=\"lines\" w:linePitch=\"312\"/>" +
            "</w:sectPr>"
    }

    private fun headerXml(text: String): String {
        val runs = DocxBody.runsXml(listOf(DocxRun(text, bold = false, italic = false)), sizeHalfPt = 18)
        val border = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"999999\"/></w:pBdr>"
        return XML_HEAD +
            "<w:hdr xmlns:w=\"$NS_W\" xmlns:r=\"$REL\">" +
            "<w:p><w:pPr>$border<w:spacing w:after=\"0\"/><w:jc w:val=\"right\"/></w:pPr>$runs</w:p>" +
            "</w:hdr>"
    }

    /** 一套内置样式（**不做样式编辑**：模型没有任何改这些数的参数）。 */
    private fun stylesXml(): String = XML_HEAD +
        "<w:styles xmlns:w=\"$NS_W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"宋体\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"30\"/><w:szCs w:val=\"30\"/>" +
        "<w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/>" +
        "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>" +
        "<w:spacing w:before=\"0\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/>" +
        "</w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr>" +
        "<w:rPr><w:sz w:val=\"30\"/><w:szCs w:val=\"30\"/></w:rPr></w:style>" +
        headingStyle(1, 44) + headingStyle(2, 34) + headingStyle(3, 30) +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/>" +
        "<w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"60\" w:line=\"360\" w:lineRule=\"auto\"/><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>" +
        "</w:style>" +
        "</w:styles>"

    private fun headingStyle(level: Int, sizeHalfPt: Int): String {
        val before = 240 + (3 - level) * 60
        return "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
            "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"$before\" w:after=\"120\"/>" +
            "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
            "<w:rPr><w:b/><w:sz w:val=\"$sizeHalfPt\"/><w:szCs w:val=\"$sizeHalfPt\"/></w:rPr>" +
            "</w:style>"
    }

    /** 编号定义：0=无序（•），1=有序（1.）；每个有序列表一条 `w:num`（起始值强制 1）。 */
    private fun numberingXml(orderedLists: Int): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<w:numbering xmlns:w=\"").append(NS_W).append("\">")
        sb.append(abstractNum(0, "bullet", "•")).append(abstractNum(1, "decimal", "%1."))
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for (n in 1..orderedLists.coerceAtLeast(1)) {
            sb.append("<w:num w:numId=\"").append(n + 1).append("\">")
                .append("<w:abstractNumId w:val=\"1\"/>")
                .append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride>")
                .append("</w:num>")
        }
        sb.append("</w:numbering>")
        return sb.toString()
    }

    private fun abstractNum(id: Int, fmt: String, text: String): String =
        "<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"singleLevel\"/>" +
            "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/>" +
            "<w:lvlText w:val=\"$text\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>"
}

/**
 * 正文渲染（逐块 → `<w:p>`/`<w:tbl>`）。
 *
 * 为什么单独一个类：编号 id 是**有状态的**（每个有序列表要自己的 numId 才能从 1 重起），
 * 而「块 → XML」的每条分支又都是纯字符串拼装；把状态收在这一个对象里，
 * 每一块的渲染函数就都能短到看得懂。
 */
private class DocxBody(
    private val spec: DocxSpec,
    private val images: Map<String, OfficeImage>,
    private val imageIds: List<String>,
) {

    private var imageIndex = 0
    private var orderedLists = 0

    /** @return (正文 XML, 有序列表个数——numbering.xml 要按它建 num) */
    fun render(): Pair<String, Int> {
        val sb = StringBuilder()
        for (block in spec.blocks) {
            when (block) {
                is DocxBlock.Heading -> sb.append(heading(block))
                is DocxBlock.Paragraph -> sb.append(paragraph(block))
                is DocxBlock.Bullets -> sb.append(bullets(block))
                is DocxBlock.Table -> sb.append(table(block))
                is DocxBlock.Picture -> sb.append(picture(block))
            }
        }
        return sb.toString() to orderedLists
    }

    private fun heading(block: DocxBlock.Heading): String =
        "<w:p><w:pPr><w:pStyle w:val=\"Heading${block.level}\"/></w:pPr>" +
            runsXml(DocxInline.runs(block.text)) + "</w:p>"

    private fun paragraph(block: DocxBlock.Paragraph): String =
        "<w:p>" + runsXml(DocxInline.runs(block.text)) + "</w:p>"

    private fun bullets(block: DocxBlock.Bullets): String {
        val numId = if (block.ordered) {
            orderedLists++
            orderedLists + 1
        } else {
            1
        }
        val sb = StringBuilder()
        for (item in block.items) {
            sb.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/>")
                .append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr>")
                .append("</w:pPr>").append(runsXml(DocxInline.runs(item))).append("</w:p>")
        }
        return sb.toString()
    }

    private fun table(block: DocxBlock.Table): String {
        val width = contentWidth(block.columns.size)
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:type=\"pct\" w:w=\"5000\"/>")
            .append("<w:tblBorders>")
            .append(border("top")).append(border("left")).append(border("bottom"))
            .append(border("right")).append(border("insideH")).append(border("insideV"))
            .append("</w:tblBorders><w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>")
        repeat(block.columns.size) { sb.append("<w:gridCol w:w=\"$width\"/>") }
        sb.append("</w:tblGrid>")
        sb.append(row(block.columns, width, header = true))
        block.rows.forEach { sb.append(row(it, width, header = false)) }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    /** 表格的一条单线边框（`w:val=single`，0.5pt 灰线——比默认的黑色粗框克制）。 */
    private fun border(side: String): String =
        "<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>"

    private fun row(cells: List<String>, width: Int, header: Boolean): String {
        val sb = StringBuilder("<w:tr>")
        if (header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
        for (cell in cells) {
            sb.append("<w:tc><w:tcPr><w:tcW w:type=\"dxa\" w:w=\"$width\"/></w:tcPr>")
            if (cell.isBlank() && !header) {
                sb.append("<w:p/>")
            } else {
                sb.append("<w:p>").append(runsXml(DocxInline.runs(cell), bold = header)).append("</w:p>")
            }
            sb.append("</w:tc>")
        }
        return sb.append("</w:tr>").toString()
    }

    private fun picture(block: DocxBlock.Picture): String {
        val info = images.getValue(block.path).info
        val (cx, cy) = extent(info)
        val rid = imageIds.getOrElse(imageIndex) { "" }
        val docPrId = imageIndex + 1
        imageIndex++
        return "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$docPrId\" name=\"图片 $docPrId\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"" + OoxmlNs.PICTURE + "\">" +
            "<pic:pic xmlns:pic=\"" + OoxmlNs.PICTURE + "\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$docPrId\" name=\"image$docPrId\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    companion object {

        /** 一段文字 → `<w:r>`：粗/斜用 `<w:b/>`/`<w:i/>`，空格保留（`xml:space="preserve"`）。 */
        fun runsXml(runs: List<DocxRun>, bold: Boolean = false, sizeHalfPt: Int = 0): String {
            val sb = StringBuilder()
            for (run in runs) {
                sb.append("<w:r>")
                val rPr = StringBuilder()
                if (run.bold || bold) rPr.append("<w:b/>")
                if (run.italic) rPr.append("<w:i/>")
                if (sizeHalfPt > 0) rPr.append("<w:sz w:val=\"$sizeHalfPt\"/><w:szCs w:val=\"$sizeHalfPt\"/>")
                if (rPr.isNotEmpty()) sb.append("<w:rPr>").append(rPr).append("</w:rPr>")
                sb.append("<w:t xml:space=\"preserve\">").append(Xml.text(run.text)).append("</w:t></w:r>")
            }
            return sb.toString()
        }

        /** 每列宽度（twip）：A4 版心 9000 twip 均分（Word 按 `tblW pct` 归一，不差这几 twip）。 */
        private fun contentWidth(columns: Int): Int = 9000 / columns.coerceAtLeast(1)

        /**
         * 图片显示尺寸（EMU）：按 96dpi 原尺寸；宽超过 14cm（5,040,000 EMU）时按比例缩。
         *
         * 为什么必须缩：手机照片常见 3000+ 像素宽，原尺寸放进 A4 会把版心挤爆，
         * 用户看到的是「图被裁掉一半」，而不是"图太大"。
         */
        private fun extent(info: ImageInfo): Pair<Long, Long> {
            val perPx = 9_525L
            val maxWidth = 5_040_000L
            val w = info.width.toLong() * perPx
            val h = info.height.toLong() * perPx
            if (w <= maxWidth) return w to h
            return maxWidth to (h * maxWidth) / w
        }
    }
}
