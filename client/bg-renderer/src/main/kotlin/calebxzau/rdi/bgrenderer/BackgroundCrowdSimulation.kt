package calebxzau.rdi.bgrenderer

/** Reused actor poses; rendering does not advance the simulation (including reflection passes). */
internal interface BackgroundCrowdPose {
    val visible: Boolean get() = true
    val id: String
    val x: Float
    val y: Float
    val z: Float
    val yaw: Float
    val phase: Float
    val shadowX: Float get() = x
    val shadowY: Float
    val shadowZ: Float get() = z
}

internal interface BackgroundCrowdSimulation {
    val runners: Collection<BackgroundCrowdPose>
    fun setPlayers(ids: List<String>)
    fun advance(deltaSeconds: Double)
}
