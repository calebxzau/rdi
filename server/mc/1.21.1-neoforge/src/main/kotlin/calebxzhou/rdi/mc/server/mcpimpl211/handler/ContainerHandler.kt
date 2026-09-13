package calebxzhou.rdi.mc.server.mcpimpl211.handler

import calebxzhou.rdi.mc.common2.mcp.McpBadSlotError
import calebxzhou.rdi.mc.common2.mcp.McpContainerError
import calebxzhou.rdi.mc.common2.mcp.model.*
import calebxzau.mc.common2021.resId
import calebxzhou.rdi.mc.server.mcpimpl211.handler.BlockHandler.mcBpos
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper

object ContainerHandler {
    fun slotList(req: ContainerSlotListQ, player: ServerPlayer): Result<ContainerSlotListP> = runCatching {
        val groups = mutableListOf<ContainerSlotListP.Group>()
        val failures = mutableMapOf<ActionResult.Err, MutableList<RBlockPos>>()
        for (pos in req.poses) {
            when (val result = readContainer(player, pos)) {
                is ActionResult.Ok<*> -> groups += result.data as ContainerSlotListP.Group
                is ActionResult.Err -> failures.getOrPut(result) { mutableListOf() } += pos
            }
        }
        ContainerSlotListP(groups, failures)
    }

    fun move(req: ContainerMoveQ, player: ServerPlayer): Result<ContainerMoveP> = runCatching {
        val failures = mutableMapOf<ActionResult.Err, MutableList<ContainerMoveP.Failure>>()
        var count = 0
        for ((groupId, group) in req.groups.withIndex()) {
            val from = resolveContainer(group.from, player)
            val to = resolveContainer(group.to, player)
            for ((moveId, move) in group.moves.withIndex()) {
                val result = from as? ActionResult.Err
                    ?: (to as? ActionResult.Err
                        ?: moveOne(
                            from = (from as ActionResult.Ok<*>).data as ResolvedContainer,
                            to = (to as ActionResult.Ok<*>).data as ResolvedContainer,
                            move = move,
                            test = req.test,
                            player = player,
                        ))
                when (result) {
                    is ActionResult.Ok<*> -> count += result.data as Int
                    is ActionResult.Err -> failures.getOrPut(result) { mutableListOf() } += ContainerMoveP.Failure(
                        groupId = groupId,
                        moveId = moveId,
                        fromSlotId = move.fromSlotId,
                        toSlotId = move.toSlotId,
                        count = move.count,
                    )
                }
            }
        }
        ContainerMoveP(count, failures, req.test)
    }

    fun dropItem(req: ContainerDropItemQ, player: ServerPlayer): Result<ContainerDropItemP> = runCatching {
        if (req.count <= 0) {
            throw McpContainerError("bad count ${req.count}")
        }
        val level = player.serverLevel()
        val spawnBlockPos = BlockPos.containing(req.x, req.y, req.z)
        if (level.isOutsideBuildHeight(spawnBlockPos) || !level.isLoaded(spawnBlockPos)) {
            throw McpContainerError("spawn position is not loaded")
        }
        val source = when (val resolved = resolveContainer(ContainerRef(req.source.pos), player)) {
            is ActionResult.Ok<*> -> resolved.data as ResolvedContainer
            is ActionResult.Err -> throw McpContainerError(resolved.reason)
        }
        val slotId = req.source.slotId
        if (!source.handler.validSlot(slotId)) {
            throw McpBadSlotError()
        }
        val extracted = source.handler.extractItem(slotId, req.count, true)
        if (extracted.isEmpty) {
            throw McpContainerError("source empty")
        }
        if (extracted.count < req.count) {
            throw McpContainerError("source only has ${extracted.count}, requested ${req.count}")
        }
        val actualExtracted = source.handler.extractItem(slotId, extracted.count, false)
        if (actualExtracted.count != req.count) {
            throw McpContainerError("extract failed, got ${actualExtracted.count}, requested ${req.count}")
        }
        val itemEntity = ItemEntity(level, req.x, req.y, req.z, actualExtracted)
        itemEntity.setDeltaMovement(0.0, 0.0, 0.0)
        if (!level.addFreshEntity(itemEntity)) {
            throw McpContainerError("spawn item entity failed")
        }
        markChanged(source, player)
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        ContainerDropItemP(
            entityId = itemEntity.id,
            item = ContainerDropItemP.Stack(actualExtracted.item.resId.toString(), actualExtracted.count),
        )
    }

    private fun readContainer(player: ServerPlayer, pos: RBlockPos): ActionResult<ContainerSlotListP.Group> {
        val level = player.serverLevel()
        val mcpos = pos.mcBpos
        if (!level.isLoaded(mcpos)) {
            return ActionResult.Err("chunk not loaded")
        }
        val state = level.getBlockState(mcpos)
        val handler = level.getCapability(Capabilities.ItemHandler.BLOCK, mcpos, null)
            ?: return ActionResult.Err("not a container")
        val slots = (0 until handler.slots).map { slotId ->
            slot(slotId, handler.getStackInSlot(slotId))
        }
        return ActionResult.Ok(
            ContainerSlotListP.Group(
                pos = pos,
                blockId = state.block.resId.toString(),
                slots = slots,
            )
        )
    }

    private fun slot(id: Int, stack: ItemStack): ContainerSlot {
        return if (stack.isEmpty) {
            ContainerSlot.empty(id)
        } else {
            ContainerSlot(id, stack.item.resId.toString(), stack.count)
        }
    }

    private fun resolveContainer(ref: ContainerRef, player: ServerPlayer): ActionResult<ResolvedContainer> {
        val pos = ref.pos ?: return ActionResult.Ok(ResolvedContainer("inventory", PlayerInvWrapper(player.inventory), null))
        val level = player.serverLevel()
        val blockPos = pos.mcBpos
        if (!level.isLoaded(blockPos)) {
            return ActionResult.Err("chunk not loaded")
        }
        val handler = level.getCapability(Capabilities.ItemHandler.BLOCK, blockPos, null)
            ?: return ActionResult.Err("not a container")
        return ActionResult.Ok(ResolvedContainer("block:$pos", handler, blockPos))
    }

    private fun moveOne(
        from: ResolvedContainer,
        to: ResolvedContainer,
        move: ContainerMoveQ.Move,
        test: Boolean,
        player: ServerPlayer,
    ): ActionResult<Int> {
        val toSlotId = move.toSlotId
        if (!from.handler.validSlot(move.fromSlotId)) {
            return ActionResult.Err("bad source slot")
        }
        if (toSlotId != null && !to.handler.validSlot(toSlotId)) {
            return ActionResult.Err("bad target slot")
        }
        if (toSlotId != null && from.key == to.key && move.fromSlotId == toSlotId) {
            return ActionResult.Err("same slot")
        }
        val sourceStack = from.handler.getStackInSlot(move.fromSlotId)
        if (sourceStack.isEmpty) {
            return ActionResult.Err("source empty")
        }
        val requestCount = move.count ?: sourceStack.count
        if (requestCount <= 0) {
            return ActionResult.Err("bad count")
        }
        val extracted = from.handler.extractItem(move.fromSlotId, requestCount, true)
        if (extracted.isEmpty) {
            return ActionResult.Err("source empty")
        }
        val plan = insertionPlan(
            target = to.handler,
            targetSlotId = toSlotId,
            stack = extracted,
            excludedSlotId = if (from.key == to.key) move.fromSlotId else null,
        )
        if (plan.count <= 0) {
            return ActionResult.Err("target full")
        }
        if (test) {
            return ActionResult.Ok(plan.count)
        }
        val actualExtracted = from.handler.extractItem(move.fromSlotId, plan.count, false)
        if (actualExtracted.isEmpty) {
            return ActionResult.Err("extract failed")
        }
        val movedCount = actualExtracted.count
        insertByPlan(to.handler, actualExtracted, plan)
        markChanged(from, player)
        markChanged(to, player)
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        return ActionResult.Ok(movedCount)
    }

    private fun insertionPlan(
        target: IItemHandler,
        targetSlotId: Int?,
        stack: ItemStack,
        excludedSlotId: Int?,
    ): InsertPlan {
        val steps = mutableListOf<InsertStep>()
        var remaining = stack.copy()
        val slotIds = targetSlotId?.let(::listOf) ?: target.autoTargetSlotIds(stack, excludedSlotId)
        for (slotId in slotIds) {
            if (remaining.isEmpty) break
            val before = remaining.count
            remaining = target.insertItem(slotId, remaining, true)
            val moved = before - remaining.count
            if (moved > 0) {
                steps += InsertStep(slotId, moved)
            }
        }
        return InsertPlan(stack.count - remaining.count, steps)
    }

    private fun IItemHandler.autoTargetSlotIds(stack: ItemStack, excludedSlotId: Int?): List<Int> {
        val slotIds = (0 until slots).filter { it != excludedSlotId }
        val mergeSlots = slotIds.filter { slotId ->
            val targetStack = getStackInSlot(slotId)
            !targetStack.isEmpty && ItemStack.isSameItemSameComponents(targetStack, stack)
        }
        val emptySlots = slotIds.filter { getStackInSlot(it).isEmpty }
        return mergeSlots + emptySlots
    }

    private fun insertByPlan(target: IItemHandler, stack: ItemStack, plan: InsertPlan) {
        var remaining = stack
        for (step in plan.steps) {
            if (remaining.isEmpty) break
            val stepStack = remaining.copyWithCount(minOf(step.count, remaining.count))
            val stepRemaining = target.insertItem(step.slotId, stepStack, false)
            remaining.shrink(stepStack.count - stepRemaining.count)
        }
    }

    private fun markChanged(container: ResolvedContainer, player: ServerPlayer) {
        val blockPos = container.blockPos
        if (blockPos == null) {
            player.inventory.setChanged()
            return
        }
        val level = player.serverLevel()
        val blockEntity = level.getBlockEntity(blockPos) ?: return
        blockEntity.setChanged()
        val state = level.getBlockState(blockPos)
        level.sendBlockUpdated(blockPos, state, state, 3)
    }

    private fun IItemHandler.validSlot(slotId: Int): Boolean {
        return slotId in 0 until slots
    }

    private data class ResolvedContainer(
        val key: String,
        val handler: IItemHandler,
        val blockPos: BlockPos?,
    )

    private data class InsertStep(
        val slotId: Int,
        val count: Int,
    )

    private data class InsertPlan(
        val count: Int,
        val steps: List<InsertStep>,
    )
}
