// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：静态关键帧（口径件）。
package com.example.zhengdao.baopu.state.motion

/** 口径：对外只给档位；档位→弧度只是内部喂给 655 的抽象，不是真实解剖正向运动学。 */
object MotionNotes {
    const val ASSUMPTION =
        "静态关键姿：以动作强度档派生关节角档与重心高度档；档位到弧度/坐标的映射仅为驱动 655 四项判定的工程抽象，" +
            "非真实解剖 FK；无时间积分、无连续轨迹"
    const val NOTE = "模拟非测量"
}
