// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import android.content.Context
import com.example.zhengdao.terminal.StorageAudit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上次实测值缓存（2026-10-12 走查报告 §5.4/§5.5「无感化」的地基）。
 *
 * 要解决的现象：数字类界面「先占位（统计中…）→ 算完整体蹦出来」——用户原话
 * 「突然出信息，有点吓人」。药方是 stale-while-revalidate：
 * **打开就有上次的真实值**（旧值次级色/「约」字示意），新值到达后就地覆盖那一个数。
 *
 * 存放：SharedPreferences 里一段 JSON（项目已有 6 个 SP 文件，这是第 7 个）。
 * 只缓存「展示用」数据，不缓存任何判定依据；缓存读取失败一律当没有（返回 null），
 * 由调用方走正常的「统计中…」路径——**缓存永不挡真实测量**。
 */
internal object LastValues {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("zhengdao-last-values", Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String): String? =
        runCatching { prefs(ctx).getString(key, null) }.getOrNull()

    private fun put(ctx: Context, key: String, value: String) {
        runCatching { prefs(ctx).edit().putString(key, value).apply() }
    }

    // ── 环境体检表 ────────────────────────────────────────────────────────────

    fun loadChecks(ctx: Context): List<EnvHealth.Check>? {
        val raw = get(ctx, "health_checks") ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EnvHealth.Check(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    ok = o.getBoolean("ok"),
                    detail = o.getString("detail"),
                    fixId = o.optString("fixId").takeIf { it.isNotEmpty() },
                    warn = o.optBoolean("warn", false),
                    terminalCmd = o.optString("terminalCmd").takeIf { it.isNotEmpty() },
                    route = o.optString("route").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrNull()
    }

    fun saveChecks(ctx: Context, checks: List<EnvHealth.Check>) {
        runCatching {
            val arr = JSONArray()
            checks.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("label", c.label)
                        put("ok", c.ok)
                        put("detail", c.detail)
                        if (c.fixId != null) put("fixId", c.fixId)
                        if (c.warn) put("warn", true)
                        if (c.terminalCmd != null) put("terminalCmd", c.terminalCmd)
                        if (c.route != null) put("route", c.route)
                    },
                )
            }
            put(ctx, "health_checks", arr.toString())
        }
    }

    // ── 存储三个数（rootfs / home / cache，MB）────────────────────────────────

    fun loadStorageMb(ctx: Context): Triple<Long, Long, Long>? {
        val raw = get(ctx, "storage_mb") ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Triple(o.getLong("rootfs"), o.getLong("home"), o.getLong("cache"))
        }.getOrNull()
    }

    fun saveStorageMb(ctx: Context, rootfs: Long, home: Long, cache: Long) {
        runCatching {
            put(
                ctx,
                "storage_mb",
                JSONObject().apply {
                    put("rootfs", rootfs)
                    put("home", home)
                    put("cache", cache)
                }.toString(),
            )
        }
    }

    // ── 存储占用审计明细 ──────────────────────────────────────────────────────

    fun loadAudit(ctx: Context): List<StorageAudit.Item>? {
        val raw = get(ctx, "storage_audit") ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StorageAudit.Item(
                    group = o.getString("group"),
                    label = o.getString("label"),
                    mb = o.getLong("mb"),
                    removable = o.optBoolean("removable", false),
                    note = o.optString("note", ""),
                )
            }
        }.getOrNull()
    }

    fun saveAudit(ctx: Context, items: List<StorageAudit.Item>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { it ->
                arr.put(
                    JSONObject().apply {
                        put("group", it.group)
                        put("label", it.label)
                        put("mb", it.mb)
                        put("removable", it.removable)
                        put("note", it.note)
                    },
                )
            }
            put(ctx, "storage_audit", arr.toString())
        }
    }

    // ── 通用文本快照（安装包缓存卡的多行快照等）──────────────────────────────

    fun loadText(ctx: Context, key: String): String? = get(ctx, key)

    fun saveText(ctx: Context, key: String, value: String) = put(ctx, key, value)

    // ── 缓存清理各目录体积（名字 → MB，§5.5 第 3 处）──────────────────────────

    fun loadCacheSizes(ctx: Context): Map<String, Long> {
        val raw = get(ctx, "cache_sizes") ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getLong(it) }
        }.getOrElse { emptyMap() }
    }

    fun saveCacheSizes(ctx: Context, sizes: Map<String, Long>) {
        runCatching {
            val o = JSONObject()
            sizes.forEach { (name, mb) -> o.put(name, mb) }
            put(ctx, "cache_sizes", o.toString())
        }
    }
}
