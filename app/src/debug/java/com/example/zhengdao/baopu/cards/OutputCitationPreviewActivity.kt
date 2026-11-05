// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **只看一眼的入口**：把「产出引依据」这张卡摆到真机上。
//
// 为什么放在 debug source set：正式包不合并这份清单，桌面也不会多一个图标
// （照 249 的规矩，不注册 LAUNCHER，只能显式 am start）。
// 为什么不写死一组「依据」字符串当样本：判据③要验的是「依据真能点开看原文」，
// 所以这里临时造两份**真文件**、过一次**真闸**，卡的依据与展开的原文同源。
package com.example.zhengdao.baopu.cards

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.baopu.BaopuActionContainer
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import java.io.File

class OutputCitationPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZhengdaoTheme {
                Surface(Modifier.fillMaxSize()) { OutputCitationPreview() }
            }
        }
    }
}

/** 造两份真文件 + 一条本轮搜索结果，过一次真闸；返回的依据与卡面那一行同源。 */
private fun previewAdmission(ctx: Context): Pair<List<Citation>, CitationVerdict> {
    val root = File(ctx.filesDir, "citation-preview")
    val persona = File(root, "人设-本项目.md")
    val kb = File(File(root, "知识库/整理"), "街巷笔记.md")
    persona.parentFile?.mkdirs()
    kb.parentFile?.mkdirs()
    persona.writeText("## 语气\n- 短句，不铺陈\n- 少用感叹号\n## 习惯\n- 先给结论\n", Charsets.UTF_8)
    kb.writeText("# 街巷笔记\n清晨的早点铺最热闹。\n", Charsets.UTF_8)

    val now = 1_700_000_000_000L
    CitationHitCache.record(
        listOf(CitationWebHit("https://news.example/hot", "近日热点", "街口新开早点铺", now)),
    )
    val view = CitationSourceView(
        docs = listOf(
            CitationSourceDoc("抱朴记忆/人设-本项目.md", persona),
            CitationSourceDoc("知识库/整理/街巷笔记.md", kb),
        ),
        personaRel = "抱朴记忆/人设-本项目.md",
        webHits = CitationHitCache.snapshot(),
    )
    val citations = OutputCitationSources.collect(view)
    val messages = CitationMessages(
        prefix = ctx.getString(R.string.baopu_citation_prefix),
        emptyHint = ctx.getString(R.string.baopu_citation_empty_hint),
        ledgerHint = ctx.getString(R.string.baopu_citation_ledger_hint),
        badRefHint = ctx.getString(R.string.baopu_citation_bad_ref_hint),
        danglingHint = ctx.getString(R.string.baopu_citation_dangling_hint),
    )
    val verdict = OutputCitationGate.check(citations, OutputCitationSources.exists(view), messages)
    return citations to verdict
}

@Composable
private fun OutputCitationPreview() {
    val ctx = LocalContext.current
    val (citations, verdict) = remember { previewAdmission(ctx) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.baopu_card_write_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        when (verdict) {
            is CitationVerdict.Pass -> BaopuActionContainer(
                title = stringResource(R.string.baopu_card_write_title),
                modifier = Modifier.padding(horizontal = 16.dp),
                citations = citations,
                citationLine = verdict.line,
            ) {
                Text(
                    text = stringResource(R.string.baopu_card_write_topic),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.baopu_card_write_style),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            is CitationVerdict.Reject -> Text(
                text = verdict.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
