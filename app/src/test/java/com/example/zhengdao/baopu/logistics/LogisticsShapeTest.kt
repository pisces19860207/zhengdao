// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 库的形状（派工单 §2：独立 `filesDir/物流/logistics.db`，**不动 BaopuDb 版本号**，
// 重复导入幂等 day＋waybill＋文件哈希）。
//
// 本机跑不了 `SQLiteOpenHelper`（`android.jar` 是 stub、离线缓存里没有 sqlite-jdbc），
// 所以「表长什么样」「同一单该不该覆盖」必须脱离数据库断言——房型同 197 批的 `SearchSql`。
// [LogisticsDb] 只是把这里写的语句执行一遍，判据全在这一档。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogisticsShapeTest {

    private val sample = storedRow(
        "10231",
        orderNo = "PH101",
        direction = "北京朝阳",
        unload = "三间房",
        goods = "钢材",
        pieces = 12,
        weight = "3.5",
        volume = "4.2",
        driver = "王师傅",
        phone = "13800000001",
        plate = "冀A12345",
        status = LogisticsStatus.LOADED,
        lineNo = 2,
    )

    // ── 形状 ─────────────────────────────────────────────────────────────────

    @Test
    fun `落库映射与列名一一对应，顺序就是绑定顺序`() {
        val values = storedRowValues(sample)
        assertEquals(LogisticsSql.columnNames, values.keys.toList())
        assertEquals(23, values.size)
        // 值也对得上：错位绑定比少一列坏得多（会把件数写进车牌那一格）
        assertEquals(TODAY_KEY, values["day"])
        assertEquals("10231", values["waybill"])
        assertEquals("PH101", values["order_no"])
        assertEquals("北京朝阳", values["direction"])
        assertEquals("三间房", values["unload_point"])
        assertEquals(12, values["pieces"])
        assertEquals("4.2", values["volume"])
        assertEquals("冀A12345", values["plate"])
        assertEquals("13800000001", values["phone"])
        assertEquals("LOADED", values["status"])
        assertEquals(2, values["line_no"])
    }

    @Test
    fun `INSERT 的占位符数量等于列数`() {
        val columns = LogisticsSql.INSERT_SQL.substringAfter("(").substringBefore(")").split(", ").size
        val marks = LogisticsSql.INSERT_SQL.substringAfter("VALUES (").substringBefore(")").split(", ").size
        assertEquals(columns, marks)
        assertEquals(LogisticsSql.columnNames.size, columns)
    }

    @Test
    fun `主键是 day 加 waybill，不带文件哈希`() {
        val ddl = LogisticsSql.DDL.first()
        // 派工单原话读成「主键带哈希」的话，同一单从上午一版、下午一版会落两行 ⇒ 「3 单」当场变 6 单。
        // 哈希留在列里参加判定（判「完全一样、什么都不必动」），岗位换了但三样都在。
        assertTrue(ddl.contains("PRIMARY KEY (day, waybill)"))
        assertFalse(ddl.contains("PRIMARY KEY (day, waybill, src_hash)"))
        // §1.8 的配货单号是**装车身份**，主键是**运单身份**：拿它当主键（或拿它顶替单号去重）
        // 就把一张配货单下的三单压成一行——40 单变 9 单，而且是静默的。
        assertFalse(ddl.contains("PRIMARY KEY (day, order_no)"))
        assertTrue("order_no 是一列，不是一个键：$ddl", LogisticsSql.columnNames.contains("order_no"))
        assertTrue(LogisticsSql.columnNames.contains("src_hash"))
        assertTrue(LogisticsSql.columnNames.contains("day"))
        assertTrue(LogisticsSql.columnNames.contains("waybill"))
    }

    @Test
    fun `识别列 NOT NULL，描述列可空`() {
        val decl = LogisticsSql.COLUMNS.toMap()
        assertEquals("TEXT NOT NULL", decl["day"])
        assertEquals("TEXT NOT NULL", decl["waybill"])
        assertEquals("TEXT NOT NULL", decl["status"])
        // 件数没填就是 NULL：SQLite 的 SUM 天然跳过 NULL，「全空 ⇒ 没说」这条才有出处
        assertEquals("INTEGER", decl["pieces"])
        assertFalse("件数不许 DEFAULT 0（那会把没填变成 0 件）", decl["pieces"].toString().contains("DEFAULT"))
        assertEquals("TEXT NOT NULL DEFAULT ''", decl["plate"])
    }

    @Test
    fun `数据时间的来历与有没有钟点都是列`() {
        assertTrue(LogisticsSql.columnNames.contains("exported_clock"))
        assertTrue(LogisticsSql.columnNames.contains("exported_src"))
        // 只有日期时不许写成 00:00 ⇒ 这个开关必须落在行上，而不是每次现猜
        val clock = storedRowValues(sample)
        assertEquals(1, clock["exported_clock"])
        assertEquals(0, storedRowValues(sample.copy(exportedClock = false))["exported_clock"])
    }

    @Test
    fun `缺列就补、在就不动、重复调用幂等`() {
        // §1.8 加了 order_no／volume／phone 三列 ⇒ 这里的 23 是「一张表的全部可见字」的账，
        // 少一个数字就说明有人加列没进 alterFor：老库里她勾过的状态还在，但那一列永远补不上。
        assertEquals(23, LogisticsSql.alterFor(emptySet()).size)
        assertEquals(emptyList<String>(), LogisticsSql.alterFor(LogisticsSql.columnNames.toSet()))
        val haveOne = LogisticsSql.columnNames.take(6).toSet()
        val alters = LogisticsSql.alterFor(haveOne)
        assertEquals(17, alters.size)
        assertTrue("补的是缺的那几列：$alters", alters.first().contains("pieces"))
        // SQLite 列名不区分大小写 ⇒ 库里写 `DAY` 也算在
        assertEquals(emptyList<String>(), LogisticsSql.alterFor(LogisticsSql.columnNames.map { it.uppercase() }.toSet()))
    }

    @Test
    fun `UPDATE 不动用户勾选的那一列`() {
        val sql = LogisticsSql.UPDATE_SQL
        assertFalse("人勾过的状态不被表覆盖：$sql", sql.contains("status_user = ?"))
        assertFalse("主键留在 WHERE，不进 SET：$sql", sql.contains("SET day"))
        assertTrue(sql.endsWith("WHERE day = ? AND waybill = ?"))
    }

    @Test
    fun `读一整天按来源行号排序`() {
        // 台账与面板都靠这个次序指回她在 Excel 里数出来的那一行
        assertTrue(LogisticsSql.SELECT_DAY_SQL.contains("ORDER BY line_no"))
        // 读回必须把 23 列一个不落都拿回来：漏一列 ⇒ 那一列在面板与台账里永远空着，
        // 而库里其实有值（这种「有但不显示」比报错更难查）。
        assertEquals(
            LogisticsSql.columnNames,
            LogisticsSql.SELECT_ONE_SQL.substringAfter("SELECT ").substringBefore(" FROM").split(", "),
        )
        assertEquals(23, LogisticsSql.SELECT_ONE_SQL.substringAfter("SELECT ").substringBefore(" FROM").split(", ").size)
    }

    @Test
    fun `库版本只管自己，一条索引给按天筛`() {
        assertEquals(1, LogisticsSql.VERSION)
        assertTrue(LogisticsSql.DDL.any { it.contains("CREATE INDEX IF NOT EXISTS idx_shipment_day") })
        // 整包重建只动这张表（可丢弃重建是它的立库前提，同 SearchDb）
        assertEquals(listOf("DROP TABLE IF EXISTS shipment"), LogisticsSql.DROP_ALL)
    }

    @Test
    fun `库的落点在 filesDir 下的 物流 目录`() {
        val db = LogisticsPaths.dbPathUnder(File("/sandbox/uid/files"))
        assertEquals("/sandbox/uid/files/物流/logistics.db", db.path.replace('\\', '/'))
        assertFalse("库不放工作区：工作区是用户能翻的目录，塞个 .db 她会以为是要备份的单据", db.path.contains("产物"))
    }

    @Test
    fun `inbox 与台账都在 产物 物流 下`() {
        val ws = File("/ws")
        assertEquals("/ws/产物/物流/inbox", LogisticsPaths.inboxDirUnder(ws).path.replace('\\', '/'))
        assertEquals(
            "/ws/产物/物流/$TODAY_KEY-发货台账.md",
            LogisticsPaths.ledgerFileUnder(ws, TODAY_KEY).path.replace('\\', '/'),
        )
    }

    @Test
    fun `只认 csv 与 xlsx 两种后缀`() {
        assertTrue(LogisticsPaths.isReadable("配货单.csv"))
        assertTrue(LogisticsPaths.isReadable("配货单.XLSX"))
        // `.xlsm`（带宏）不认：多认一种就是把没验证过的读法当成验证过的
        assertFalse(LogisticsPaths.isReadable("配货单.xlsm"))
        assertFalse(LogisticsPaths.isReadable("配货单.pdf"))
        assertFalse(LogisticsPaths.isReadable("配货单.txt"))
    }

    // ── 处置判据（幂等的本体）──────────────────────────────────────────────

    @Test
    fun `库里没有就记，完全一样就不动`() {
        assertEquals(RowAction.INSERT, LogisticsSql.decide(null, sample))
        assertEquals("负例③：同一份表导两次不多一行", RowAction.SKIP, LogisticsSql.decide(sample, sample.copy(srcFile = "另一份.csv", srcHash = "另一个")))
    }

    @Test
    fun `更晚的数据时间覆盖，更早的保住旧行`() {
        val inLib = sample.copy(exportedAt = millisOf(25, 9))
        val newer = inLib.copy(goods = "铝材", exportedAt = millisOf(25, 18))
        val older = inLib.copy(goods = "铝材", exportedAt = millisOf(25, 6))
        assertEquals(RowAction.UPDATE, LogisticsSql.decide(inLib, newer))
        assertEquals("旧表不许静默盖掉新行", RowAction.KEEP, LogisticsSql.decide(inLib, older))
        // 时间相同但值不同 ⇒ 按这次导入的记（tied 单独报出去）
        assertEquals(RowAction.UPDATE, LogisticsSql.decide(inLib, inLib.copy(goods = "铝材")))
    }

    @Test
    fun `逐字段比的是看得见的字，不比身份三列与勾选`() {
        val a = sample
        val b = sample.copy(day = a.day, waybill = a.waybill, exportedAt = 999L, srcFile = "别的.csv", srcHash = "别的哈希", lineNo = 77, statusUser = LogisticsStatus.PENDING.name)
        assertTrue("身份三列与勾选都不参与比较", LogisticsSql.samePayload(a, b))
        assertFalse(LogisticsSql.samePayload(a, b.copy(pieces = 13)))
        assertFalse(LogisticsSql.samePayload(a, b.copy(missing = "件数")))
        // 件数「12」与「十二件」是两个原文 ⇒ 原文也要比，否则两份表会被判成等价
        assertFalse(LogisticsSql.samePayload(a, b.copy(piecesRaw = "12件")))
        // §1.8 新三列都是**看得见的字**：配货单号换了就是换了一张车（车数跟着变），
        // 漏比任何一列 ⇒ 重导时判成「没变」，界面上一切照旧但库里其实还是旧的那台车。
        assertFalse("配货单号变了要说成没变", LogisticsSql.samePayload(a, b.copy(orderNo = "PH999")))
        assertFalse(LogisticsSql.samePayload(a, b.copy(volume = "5.0")))
        assertFalse(LogisticsSql.samePayload(a, b.copy(phone = "13800000002")))
        assertFalse(LogisticsSql.samePayload(a, b.copy(driver = "李师傅")))
    }

    @Test
    fun `一行都没写进库时处置统计报 failed，不许报成功`() {
        val t = ImportTally(inserted = 3)
        assertEquals(3, t.total)
        val failed = ImportTally(failed = 3)
        assertEquals("全失败时 total 是 0，但 failed 有 3", 0, failed.total)
        assertEquals(3, failed.failed)
    }
}
