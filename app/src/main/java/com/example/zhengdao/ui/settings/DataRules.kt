// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第七十七批 · 结构拆分第六阶段：Settings 存储/数据页纯逻辑抽取》§1
// （原数据设置页「资料库 / 工作区」相关卡片里的内联判定）。
//
// 边界：只搬**判定**——真实挂载状态、扫描、复制、路径读写都留在页面侧
// （那几件要 Context / IO）。本文件零 Android 依赖，输入是布尔 / 枚举 / 普通值。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.terminal.KnowledgeBase

/**
 * 终端与资料页的**纯判据层**（第七十七批 · §B 80 切片）。
 *
 * 页面里原本有五处内联判定散在组合期 lambda 里：换工作区要不要先问、挑的路径
 * 合不合法、资料库路径行显不显、别处的资料库要不要提示复制、扫描上限到了没。
 * 判据集中到本对象后，页面只留调用；语义只此一份，负例单测直接打。
 */
internal object DataRules {

    /**
     * 换工作区前是否要先问一句（原 `if ((kbStatus?.files ?: 0) > 0)`）。
     *
     * 为什么是迁移前置条件：`资料库` 挂在**工作区**下面（KnowledgeBase.root =
     * hostDir/资料库），换工作区就等于换了一份资料库——旧那份原样留在原地、不再
     * 被读。当前资料库里有东西时先问，并给「顺手复制过去」这条路（原件不动）；
     * 空资料库直接切，不多问。
     *
     * @param kbFiles 当前资料库文件数；还没读到状态（null）按 0 算——没有就一定不用问
     */
    fun shouldConfirmWorkspaceChange(kbFiles: Int?): Boolean = (kbFiles ?: 0) > 0

    /**
     * 挑中的工作区路径合不合法（原 `if (!path.startsWith("/storage/emulated/0"))`）。
     *
     * 工作区必须落在共享存储根（/storage/emulated/0）下——那是用户能在文件管理器
     * 里自己进去看、自己删的位置；指到 App 私有目录就等于把产出边界藏起来。
     */
    fun isSharedStoragePath(path: String): Boolean = path.startsWith("/storage/emulated/0")

    /**
     * 资料库路径行是否展示（原 `if (st != null && st.state != DISABLED)`）。
     *
     * DISABLED（用户把开关关了）时没有可展示的路径；其余状态（含还没读到的
     * null）都展示——路径是用户「进去放资料」的唯一线索。
     */
    fun shouldShowKbPath(state: KnowledgeBase.State?): Boolean =
        state != null && state != KnowledgeBase.State.DISABLED

    /**
     * 是否提示「别处还有一份带资料的资料库」并可一键复制（原
     * `if (st?.state == NOT_MOUNTED && otherKb.isNotEmpty())`）。
     *
     * 只在**未挂载**时报：用户常是「先去文件管理器把资料丢进 原始/，再切回 App」，
     * 旧路径不显这条提示，他看到的就是「放了东西没反应」。true 时展示别处路径
     * 与文件数，并给复制入口（只增不减，原件不动）。
     */
    fun shouldOfferOtherKbCopy(state: KnowledgeBase.State?, otherCount: Int): Boolean =
        state == KnowledgeBase.State.NOT_MOUNTED && otherCount > 0

    /**
     * 文件数是否达到扫描上限（原 `if (n >= KnowledgeBase.MAX_SCAN)`）。
     *
     * E-089：到了上限**必须提示**，否则用户会以为「资料全在这儿了」——清单是
     * 截断的，没提示就是骗人。
     */
    fun capReached(files: Int): Boolean = files >= KnowledgeBase.MAX_SCAN
}
