package com.example.zhengdao.baopu.state.anatomy

internal data class Attachment(
    val owner: RegionId,
    val target: RegionId,
)

internal data class Adjacency(
    val from: RegionId,
    val to: RegionId,
)

internal data class Transition(
    val from: RegionId,
    val to: RegionId,
)

internal data class TopologyRelations(
    val attachments: List<Attachment>,
    val adjacency: List<Adjacency>,
    val transitions: List<Transition>,
)

internal interface TopologyGraph {
    val relations: TopologyRelations
}
