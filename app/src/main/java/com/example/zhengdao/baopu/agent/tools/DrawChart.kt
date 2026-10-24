// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：柱状图 / 折线图的**本地排版**（纯函数、零 IO、零 Android）。
//
// 输入只有结构化数据：{ categories（或 labels）:[…], series:[{ name, values:[…] }] }。
// 不做 flowchart/sequence 之类私有 DSL（方向更正：那是在重新发明 Mermaid），
// 复杂拓扑交给模型直接写 SVG 走 `render_artifact`。
//
// 为什么自己排而不是引图表库：Mermaid/Chart.js 都是 JS 运行时，本批红线第一条就是
// 「不执行 JS」；这里只产出静态 SVG，坐标与字号都可复算。
package com.example.zhengdao.baopu.agent

import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

internal object DrawChart {

    val KINDS = listOf("bar", "line")

    data class Rendered(
        val scene: DrawScene,
        val kind: String,
        val notes: List<String>,
        val summary: String,
    )

    fun render(kindRaw: String, data: JSONObject, titleRaw: String?): DrawResult<Rendered> {
        val kind = kindRaw.trim().lowercase()
        if (kind !in KINDS) {
            return drawErr("不认识的 kind「$kindRaw」：只认 ${KINDS.joinToString(" / ")}。")
        }
        return try {
            val cleaner = DrawCleaner()
            val title = titleRaw
                ?.let { cleaner.text(it, "标题", DrawLimits.MAX_TITLE_CHARS) }
                ?.ifBlank { null }
            val (scene, summary) = chart(data, title, cleaner, line = kind == "line")
            drawOk(Rendered(scene, kind, cleaner.notes(), summary))
        } catch (b: DrawBad) {
            drawErr(b.message ?: "图表参数不对")
        } catch (t: Throwable) {
            drawErr("图表参数解析失败：${t.javaClass.simpleName}（检查 data 的结构）")
        }
    }

    private data class Series(val name: String, val values: List<Double>)

    private fun chart(
        data: JSONObject,
        title: String?,
        cleaner: DrawCleaner,
        line: Boolean,
    ): Pair<DrawScene, String> {
        val kind = if (line) "line" else "bar"
        val cArr = data.optJSONArray("categories") ?: data.optJSONArray("labels")
            ?: throw DrawBad("$kind 缺 categories（或 labels）数组：给出每个类别的名字。")
        if (cArr.length() == 0) throw DrawBad("$kind 的 categories 是空的：至少给一个类别。")
        if (cArr.length() > DrawLimits.MAX_CATEGORIES) {
            throw DrawBad("类别太多：${cArr.length()} 个（上限 ${DrawLimits.MAX_CATEGORIES}）。")
        }
        val cats = (0 until cArr.length()).map { i ->
            cleaner.text(cArr.optString(i), "类别 ${i + 1}", 12).ifEmpty { "第${i + 1}项" }
        }
        val series = readSeries(data, cats.size, cleaner)
        val xLabel = data.optString("xLabel").takeIf { it.isNotBlank() }
            ?.let { cleaner.text(it, "横轴标题", 40) }
            ?.ifBlank { null }
        val scene = draw(cats, series, title, xLabel, line)
        val kindName = if (line) "折线图" else "柱状图"
        return scene to "$kindName：${cats.size} 个类别 × ${series.size} 个系列"
    }

    /**
     * 读系列。主形状是 `series:[{name,values}]`；也接住两种常见误写：
     * - 顶层直接给 `values:[…]`（单系列）；
     * - 顶层 `series` 是一个对象而不是数组。
     * 误写不静默猜数值：长度对不上、出现负数都直接报错。
     */
    private fun readSeries(data: JSONObject, catCount: Int, cleaner: DrawCleaner): List<Series> {
        val arr: JSONArray = data.optJSONArray("series")
            ?: data.optJSONObject("series")?.let { JSONArray().put(it) }
            ?: data.optJSONArray("values")?.let {
                JSONArray().put(JSONObject().put("name", data.optString("name")).put("values", it))
            }
            ?: throw DrawBad("缺 series 数组：[{name:\"系列名\", values:[…]}]。")
        if (arr.length() == 0) throw DrawBad("series 是空的：至少给一个系列。")
        if (arr.length() > DrawLimits.MAX_SERIES) {
            throw DrawBad("系列太多：${arr.length()} 个（上限 ${DrawLimits.MAX_SERIES}）。")
        }
        val out = ArrayList<Series>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw DrawBad("series[$i] 不是对象。")
            val name = cleaner.text(o.optString("name").ifBlank { "系列${i + 1}" }, "系列 $i", 20)
            val vArr = o.optJSONArray("values") ?: throw DrawBad("series[$i] 缺 values 数组。")
            if (vArr.length() != catCount) {
                throw DrawBad("series[$i] 有 ${vArr.length()} 个值，categories 有 $catCount 个：必须一一对应。")
            }
            val values = (0 until vArr.length()).map { k ->
                val v = vArr.optDouble(k, Double.NaN)
                if (v.isNaN() || v.isInfinite()) throw DrawBad("series[$i].values[$k] 不是数字。")
                if (v < 0.0) throw DrawBad("series[$i].values[$k] 是负数：v1 只画非负值（坐标轴从 0 起）。")
                v
            }
            out.add(Series(name, values))
        }
        return out
    }

    private fun draw(
        cats: List<String>,
        series: List<Series>,
        title: String?,
        xLabel: String?,
        line: Boolean,
    ): DrawScene {
        val legendY = if (title != null) 58 else 26
        val plotTop = if (title != null) 96 else 66
        val left = 66
        val right = 32
        val bottom = 70
        val width = max(560, cats.size * 78 + left + right).coerceAtMost(1800)
        val plotW = width - left - right
        val groupW = plotW.toDouble() / cats.size
        val maxV = series.maxOf { it.values.maxOrNull() ?: 0.0 }
        val top = DrawText.niceMax(maxV * 1.08)
        val plotH = 330
        val height = plotTop + plotH + bottom
        fun yOf(v: Double): Int = (plotTop + plotH - (v / top) * plotH).roundToInt()

        val shapes = mutableListOf<DrawShape>()
        if (title != null) shapes += DrawShape.Text(width / 2, 28, title, 20, "middle", bold = true)
        var lx = left
        for (i in series.indices) {
            val color = DrawColors.SERIES[i % DrawColors.SERIES.size]
            shapes += DrawShape.Rect(lx, legendY - 6, 12, 12, fill = color)
            shapes += DrawShape.Text(
                lx + 18, legendY, series[i].name, 12, "start", color = DrawColors.MUTED,
            )
            lx += 18 + DrawText.width(series[i].name, 12) + 24
        }
        for (t in 0..5) {
            val v = top * t / 5.0
            val y = yOf(v)
            shapes += DrawShape.Line(left, y, width - right, y, DrawColors.GRID, 1)
            shapes += DrawShape.Text(left - 8, y, DrawText.number(v), 11, "end", color = DrawColors.MUTED)
        }
        shapes += DrawShape.Line(left, plotTop, left, plotTop + plotH, DrawColors.LINE, 1)
        shapes += DrawShape.Line(left, plotTop + plotH, width - right, plotTop + plotH, DrawColors.LINE, 1)

        val catChars = max(2, DrawText.fitChars(cats.maxByOrNull { it.length } ?: "", groupW.toInt() - 8, 12))
        for (i in cats.indices) {
            shapes += DrawShape.Text(
                (left + groupW * (i + 0.5)).roundToInt(), plotTop + plotH + 22,
                DrawText.clip(cats[i], catChars), 12, "middle", color = DrawColors.MUTED,
            )
        }

        if (line) {
            for (si in series.indices) {
                val color = DrawColors.SERIES[si % DrawColors.SERIES.size]
                val pts = series[si].values.mapIndexed { i, v ->
                    (left + groupW * (i + 0.5)).roundToInt() to yOf(v)
                }
                if (pts.size > 1) {
                    shapes += DrawShape.Poly(pts, stroke = color, strokeWidth = 2, closed = false)
                }
                for (p in pts) {
                    shapes += DrawShape.Poly(
                        listOf(
                            p.first - 4 to p.second, p.first + 4 to p.second,
                            p.first to p.second - 4, p.first to p.second + 4,
                        ),
                        fill = color, closed = true,
                    )
                }
            }
        } else {
            val barW = max(4.0, groupW * 0.68 / series.size)
            val showValue = cats.size * series.size <= 24 && barW >= 22
            for (si in series.indices) {
                val color = DrawColors.SERIES[si % DrawColors.SERIES.size]
                for (i in cats.indices) {
                    val v = series[si].values[i]
                    val y = yOf(v)
                    val x = (left + groupW * i + groupW * 0.16 + si * barW).roundToInt()
                    val h = (plotTop + plotH - y).coerceAtLeast(1)
                    shapes += DrawShape.Rect(x, y, barW.roundToInt().coerceAtLeast(4), h, fill = color)
                    if (showValue) {
                        shapes += DrawShape.Text(
                            x + barW.roundToInt() / 2, y - 9,
                            DrawText.number(v), 10, "middle", color = DrawColors.MUTED,
                        )
                    }
                }
            }
        }
        val note = xLabel
        if (note != null) {
            shapes += DrawShape.Text(
                width / 2, height - 12, note, 11, "middle", color = DrawColors.MUTED,
            )
        }
        return DrawScene(width, height, shapes)
    }
}
