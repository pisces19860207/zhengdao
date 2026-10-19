// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十批 · 运行时提案自检 v1：系统提示的**唯一拼装口**。
//
// 为什么单独一个文件：派工单 §4 的负例是「把用落盘后人设改回 BaopuPreset.personaText
// ⇒ 至少一条用例红」。有一个可 grep 的文件名与函数名，比在 RuntimeEval 里内联一段
// `assemble(...)` 好钉得多。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.BaopuPreset
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
import java.io.File

internal object PersonaPrompt {

    fun of(workspacePath: String, global: String?, project: String?): String =
        PersonaRules.assemble(BaopuPreset.personaText(workspacePath), global, project)
            .joinToString("\n\n") { it.text }

    /** 从落盘的人设文件现读一次（**必须在提案写完之后调用**，否则测的是旧人设）。 */
    fun ofDisk(personaFiles: PersonaFiles, workspace: File): String = of(
        workspacePath = workspace.absolutePath,
        global = personaFiles.read(PersonaLayer.B_GLOBAL),
        project = personaFiles.read(PersonaLayer.C_PROJECT),
    )
}
