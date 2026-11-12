// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 599 段C 判据（2026-11-12）：写→读往返字段与 assets 清单一致；损坏 zip 明确报错不吞；不联网。
// 全部夹具**运行时合成**、全内存零临时目录（水位闸口径）；真件内容零进仓。
package com.example.zhengdao.baopu.card

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharxTest {

    // ── 夹具（母档走既有 CardBuild.cardJson 链，证「母档打成 .charx」全链） ──

    private fun motherCard(): String = CardBuild.cardJson(
        CardBuild.Spec(
            format = CardBuild.FORMAT_ST,
            name = "示例角色",
            persona = "示例人格",
            world = listOf("示例设定"),
            keywords = listOf("示例词"),
            style = "",
            html = "",
            css = "",
        ),
    )

    /** uri 写方不参与（由 type/name/ext 生成），给占位串证「传入 uri 不被采用」。 */
    private fun asset(type: String, name: String, ext: String, bytes: ByteArray?) =
        CharxAsset(type, "占位-uri", name, ext, bytes)

    private fun fiveAssets(): List<CharxAsset> = listOf(
        asset("icon", "头像", "png", byteArrayOf(1, 2, 3)),
        asset("user_icon", "用户头像", "jpg", byteArrayOf(4, 5, 6)),
        asset("emotion", "喜", "png", byteArrayOf(7)),
        asset("expression", "笑", "webp", byteArrayOf(8, 9)),
        asset("background", "背景", "jpg", byteArrayOf(10)),
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, Charsets.UTF_8).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun cardEntry(json: String): Pair<String, ByteArray> = "card.json" to json.toByteArray(Charsets.UTF_8)

    /**
     * 手拼 STORED zip 字节（局部头×N＋中央目录＋EOCD，全小端）。
     * 为什么手拼：`ZipOutputStream` 写入侧自己拒重名（ZipException），造不出「重名条目」
     * 这种坏包——而别的工具造得出，读方必须防。只有重名用例走这里，其余仍用 ZipOutputStream。
     */
    private fun rawStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(data) }.value.toInt()
            val local = java.nio.ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            local.putInt(0x04034b50).putShort(20).putShort(0x0800).putShort(0)
            local.putShort(0).putShort(0x21) // 1980-01-01 00:00（zip 时间戳下限）
            local.putInt(crc).putInt(data.size).putInt(data.size)
            local.putShort(nameBytes.size.toShort()).putShort(0)
            out.write(local.array())
            out.write(nameBytes)
            out.write(data)
            val cd = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            cd.putInt(0x02014b50).putShort(20).putShort(20).putShort(0x0800).putShort(0)
            cd.putShort(0).putShort(0x21)
            cd.putInt(crc).putInt(data.size).putInt(data.size)
            cd.putShort(nameBytes.size.toShort()).putShort(0).putShort(0)
            cd.putShort(0).putShort(0).putInt(0).putInt(offset)
            central.write(cd.array())
            central.write(nameBytes)
            offset += 30 + nameBytes.size + data.size
        }
        val cdBytes = central.toByteArray()
        val eocd = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054b50).putShort(0).putShort(0)
        eocd.putShort(entries.size.toShort()).putShort(entries.size.toShort())
        eocd.putInt(cdBytes.size).putInt(offset).putShort(0)
        out.write(cdBytes)
        out.write(eocd.array())
        return out.toByteArray()
    }

    private fun v3WithAssets(assetsJson: String): String =
        JSONObject(motherCard()).also { it.getJSONObject("data").put("assets", JSONArray(assetsJson)) }.toString()

    private fun readError(bytes: ByteArray): Charx.CharxError {
        val err = runCatching { Charx.read(bytes) }.exceptionOrNull()!!
        assertTrue("应是 CharxError，实为 ${err.javaClass.simpleName}: ${err.message}", err is Charx.CharxError)
        return err as Charx.CharxError
    }

    // ── 判据：写→读往返，字段与 assets 清单一致 ──────────────────────────

    @Test
    fun `write_read_roundtrip_keeps_card_fields_and_asset_manifest`() {
        val src = motherCard()
        val assets = fiveAssets()
        val back = Charx.read(Charx.write(src, assets))

        assertEquals("chara_card_v3", back.card.getString("spec"))
        assertEquals("3.0", back.card.getString("spec_version"))
        val srcData = JSONObject(src).getJSONObject("data")
        val backData = back.card.getJSONObject("data")
        for (key in listOf("name", "description", "first_mes", "personality", "scenario", "mes_example", "creator", "character_version")) {
            assertEquals("字段 $key 往返不一致", srcData.getString(key), backData.getString(key))
        }

        val manifest = backData.getJSONArray("assets")
        assertEquals(assets.size, manifest.length())
        assertEquals(assets.size, back.assets.size)
        for (i in assets.indices) {
            val a = assets[i]
            val m = manifest.getJSONObject(i)
            val r = back.assets[i]
            assertEquals(a.type, m.getString("type"))
            assertEquals(a.name, m.getString("name"))
            assertEquals(a.ext, m.getString("ext"))
            assertEquals("uri 由 type/name/ext 生成（规范原文 embeded 拼写）", "embeded://assets/${a.type}/${a.name}.${a.ext}", m.getString("uri"))
            assertEquals(m.getString("type"), r.type)
            assertEquals(m.getString("uri"), r.uri)
            assertEquals(m.getString("name"), r.name)
            assertEquals(m.getString("ext"), r.ext)
            assertTrue("资产字节往返不一致：${a.name}", a.bytes!!.contentEquals(r.bytes!!))
        }
    }

    @Test
    fun `write_rewrites_existing_manifest_from_given_assets`() {
        val old = JSONObject().put("type", "icon").put("uri", "embeded://assets/icon/旧.png").put("name", "旧").put("ext", "png")
        val src = JSONObject(motherCard()).also { it.getJSONObject("data").put("assets", JSONArray().put(old)) }.toString()
        val back = Charx.read(Charx.write(src, fiveAssets()))
        val manifest = back.card.getJSONObject("data").getJSONArray("assets")
        assertEquals(5, manifest.length())
        for (i in 0 until manifest.length()) {
            assertTrue("旧清单不许残留", manifest.getJSONObject(i).getString("name") != "旧")
        }
    }

    @Test
    fun `empty_assets_roundtrip`() {
        val back = Charx.read(Charx.write(motherCard(), emptyList()))
        assertEquals(0, back.card.getJSONObject("data").getJSONArray("assets").length())
        assertTrue(back.assets.isEmpty())
    }

    // ── 判据：损坏/非 zip 明确报错不吞 ────────────────────────────────────

    @Test
    fun `non_zip_bytes_report_clearly`() {
        val err = readError("这不是 zip".toByteArray(Charsets.UTF_8))
        assertTrue(err.message!!.contains("card.json"))
    }

    @Test
    fun `truncated_zip_reports_clearly`() {
        val noise = ByteArray(64 * 1024).also { Random(42L).nextBytes(it) }
        val zip = Charx.write(motherCard(), listOf(asset("icon", "大", "png", noise)))
        // 砍头：局部文件头都不完整
        assertTrue(readError(zip.copyOf(10)).message!!.isNotBlank())
        // 拦腰：资产数据截断（ZipInputStream 可能抛 EOF，也可能静默结束——两条路都必须报错）
        assertTrue(readError(zip.copyOf(zip.size / 2)).message!!.isNotBlank())
    }

    @Test
    fun `zip_without_card_json_reports`() {
        val zip = zipOf("assets/icon/头像.png" to byteArrayOf(1))
        assertTrue(readError(zip).message!!.contains("card.json"))
    }

    @Test
    fun `non_v3_spec_reports`() {
        val v2 = JSONObject().put("spec", "chara_card_v2").put("data", JSONObject()).toString()
        assertTrue(readError(zipOf(cardEntry(v2))).message!!.contains("chara_card_v3"))
        val noData = JSONObject().put("spec", "chara_card_v3").toString()
        assertTrue(readError(zipOf(cardEntry(noData))).message!!.contains("data"))
    }

    @Test
    fun `bad_card_json_text_reports`() {
        assertTrue(readError(zipOf("card.json" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()))).message!!.contains("UTF-8"))
        assertTrue(readError(zipOf(cardEntry("不是 JSON"))).message!!.contains("JSON"))
    }

    @Test
    fun `manifest_registered_but_file_missing_reports`() {
        val card = v3WithAssets("""[{"type":"icon","uri":"embeded://assets/icon/缺.png","name":"缺","ext":"png"}]""")
        val err = readError(zipOf(cardEntry(card)))
        assertTrue(err.message!!.contains("找不到"))
        assertTrue(err.message!!.contains("embeded://assets/icon/缺.png"))
    }

    @Test
    fun `manifest_entry_missing_keys_reports`() {
        val card = v3WithAssets("""[{"type":"icon"}]""")
        assertTrue(readError(zipOf(cardEntry(card))).message!!.contains("type/uri/name/ext"))
    }

    @Test
    fun `manifest_not_array_reports`() {
        val card = JSONObject(motherCard()).also { it.getJSONObject("data").put("assets", "不是数组") }.toString()
        assertTrue(readError(zipOf(cardEntry(card))).message!!.contains("assets"))
    }

    @Test
    fun `zip_slip_and_absolute_entries_rejected`() {
        val slip = zipOf(cardEntry(motherCard()), "../evil.txt" to byteArrayOf(1))
        assertTrue(readError(slip).message!!.contains("zip-slip"))
        val abs = zipOf(cardEntry(motherCard()), "/evil.txt" to byteArrayOf(1))
        assertTrue(readError(abs).message!!.contains("绝对路径"))
    }

    @Test
    fun `duplicate_entries_rejected`() {
        val zip = rawStoredZip(listOf(cardEntry(motherCard()), cardEntry(motherCard())))
        assertTrue(readError(zip).message!!.contains("重名"))
    }

    // ── 判据：不联网（远端 URI 只留清单登记，bytes=null） ──────────────────

    @Test
    fun `remote_and_data_uris_keep_manifest_without_bytes`() {
        val card = v3WithAssets(
            """[
                {"type":"icon","uri":"https://example.invalid/头像.png","name":"远端","ext":"png"},
                {"type":"background","uri":"data:image/png;base64,AAAA","name":"内联","ext":"png"}
            ]""",
        )
        val back = Charx.read(zipOf(cardEntry(card)))
        assertEquals(2, back.assets.size)
        assertEquals("https://example.invalid/头像.png", back.assets[0].uri)
        assertNull("远端 URI 不联网取", back.assets[0].bytes)
        assertNull("data URI 也不解", back.assets[1].bytes)
    }

    // ── 写方拒坏资产（不给出歧义包的机会） ────────────────────────────────

    @Test
    fun `write_rejects_bad_asset_parts`() {
        val bad = listOf(
            asset("icon", "", "png", byteArrayOf(1)),
            asset("icon", "a/b", "png", byteArrayOf(1)),
            asset("ic..on", "头像", "png", byteArrayOf(1)),
            asset("icon", "头像", "p/ng", byteArrayOf(1)),
        )
        for (a in bad) {
            val err = runCatching { Charx.write(motherCard(), listOf(a)) }.exceptionOrNull()!!
            assertTrue(err is Charx.CharxError)
        }
    }

    @Test
    fun `write_rejects_duplicate_assets`() {
        val dup = listOf(asset("icon", "头像", "png", byteArrayOf(1)), asset("icon", "头像", "png", byteArrayOf(2)))
        val err = runCatching { Charx.write(motherCard(), dup) }.exceptionOrNull()!!
        assertTrue(err is Charx.CharxError)
        assertTrue(err.message!!.contains("重名"))
    }

    @Test
    fun `write_rejects_asset_without_bytes`() {
        val err = runCatching { Charx.write(motherCard(), listOf(asset("icon", "头像", "png", null))) }.exceptionOrNull()!!
        assertTrue(err is Charx.CharxError)
        assertTrue(err.message!!.contains("不联网"))
    }

    @Test
    fun `write_rejects_non_v3_mother_card`() {
        val err = runCatching { Charx.write("""{"spec":"chara_card_v2"}""", emptyList()) }.exceptionOrNull()!!
        assertTrue(err is Charx.CharxError)
    }

    // ── 口径：已压缩格式走 STORED（与 ZipPacker 同一份 ArchiveRules） ──────

    @Test
    fun `jpg_stored_png_deflated_card_deflated`() {
        val zip = Charx.write(motherCard(), fiveAssets())
        val methods = HashMap<String, Int>()
        ZipInputStream(ByteArrayInputStream(zip), Charsets.UTF_8).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                methods[e.name] = e.method
                zin.readBytes()
            }
        }
        assertEquals(ZipEntry.DEFLATED, methods["card.json"])
        assertEquals(ZipEntry.STORED, methods["assets/user_icon/用户头像.jpg"])
        assertEquals(ZipEntry.STORED, methods["assets/background/背景.jpg"])
        assertEquals(ZipEntry.DEFLATED, methods["assets/icon/头像.png"])
        assertEquals(ZipEntry.DEFLATED, methods["assets/expression/笑.webp"])
    }
}