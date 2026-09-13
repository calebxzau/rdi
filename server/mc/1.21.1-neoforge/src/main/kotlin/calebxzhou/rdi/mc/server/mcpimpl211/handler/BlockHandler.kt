package calebxzhou.rdi.mc.server.mcpimpl211.handler

import calebxzhou.rdi.mc.common2.mcp.McpBadBlockStateError
import calebxzhou.rdi.mc.common2.mcp.McpBadSlotError
import calebxzhou.rdi.mc.common2.mcp.McpBlockError
import calebxzhou.rdi.mc.common2.mcp.model.*
import calebxzau.mc.common2021.parseResId
import calebxzau.mc.common2021.resId
import calebxzau.mc.common2021.resolveBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

private const val BLOCK_BATCH_LIMIT = 2048

object BlockHandler {
    private typealias Ok = ActionResult.Ok<Unit>
    private typealias Err = ActionResult.Err
    val RBlockPos.mcBpos get() = BlockPos(x,y,z)
    fun handleBox(req: BlockPlaceBoxQ, player: ServerPlayer): Result<BlockActionP> = runCatching {
        val positions = processBox(req.startPos,req.deltaPos,req.form)
        val block = req.blockId.parseResId()?.resolveBlock()
            ?: throw McpBlockError("block id ${req.blockId} not found")
        if (countPlaceItems(player, block) == 0) {
            throw McpBlockError("insufficient item")
        }
        place(player, positions, block, req.state, req.test)
    }

    fun handleDiscrete(req: BlockPlaceDiscreteQ, player: ServerPlayer): Result<BlockActionP> = runCatching {
        if (req.targets.size > BLOCK_BATCH_LIMIT) {
            throw McpBlockError("${req.targets.size} exceeds limit $BLOCK_BATCH_LIMIT")
        }
        val block = req.blockId.parseResId()?.resolveBlock()
            ?: throw McpBlockError("block id ${req.blockId} not found")
        if (countPlaceItems(player, block) == 0) {
            throw McpBlockError("insufficient item")
        }
        val failures = mutableMapOf<Err, MutableList<RBlockPos>>()
        var count = 0
        for (target in req.targets) {
            when (val result = runCatching { placeOne(player, target.pos, block, target.state, req.test) }.getOrElse(::failureResult)) {
                is ActionResult.Ok<*> -> count++
                is Err -> failures.getOrPut(result) { mutableListOf() } += target.pos
            }
        }
        BlockActionP(count, failures, req.test)
    }

    fun breakBox(req: BlockBreakBoxQ, player: ServerPlayer): Result<BlockActionP> = runCatching {
        val positions = processBox(req.startPos,req.deltaPos,req.form)
        breakPositions(player, positions, req.toolInvSlot, req.noPickup, req.test)
    }

    private fun processBox(startPos: RBlockPos,deltaPos: RBlockPos,form: BlockActionForm): List<RBlockPos> {
        val box = RBlockAABB.fromDelta(startPos, deltaPos)
        val positions = when (form) {
            BlockActionForm.BOX -> box.posListBox
            BlockActionForm.RING -> box.posListRing
        }
        if (positions.size > BLOCK_BATCH_LIMIT) {
            throw McpBlockError("${positions.size} exceeds limit $BLOCK_BATCH_LIMIT")
        }
        return positions
    }

    fun breakDiscrete(req: BlockBreakDiscreteQ, player: ServerPlayer): Result<BlockActionP> = runCatching {
        if (req.poses.size > BLOCK_BATCH_LIMIT) {
            throw McpBlockError("${req.poses.size} exceeds limit $BLOCK_BATCH_LIMIT")
        }
        breakPositions(player, req.poses, req.toolInvSlot, req.noPickup, req.test)
    }

    fun harvestResult(req: BlockHarvestResultQ, player: ServerPlayer): Result<BlockHarvestResultP> = runCatching {
        val level = player.serverLevel()
        val blockPos = req.pos.mcBpos
        if (level.isOutsideBuildHeight(blockPos) || !level.isLoaded(blockPos)) {
            throw McpBlockError("block position is not loaded")
        }
        val state = level.getBlockState(blockPos)
        if (state.isAir) {
            throw McpBlockError("block is air")
        }

        val tool = player.inventory.items.getOrNull(req.invSlot) ?: throw McpBadSlotError()
        val harvestable = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)
        val drops = if (harvestable) {
            val blockEntity = if (state.hasBlockEntity()) level.getBlockEntity(blockPos) else null
            Block.getDrops(state, level, blockPos, blockEntity, player, tool.copy()).harvestStacks()
        } else {
            emptyList()
        }
        BlockHarvestResultP(
            pos = req.pos,
            blockId = state.block.resId.toString(),
            tool = tool.harvestStack(),
            toolSlot = req.invSlot,
            harvestable = harvestable,
            drops = drops,
        )
    }

    fun useItemOn(req: BlockUseItemQ, player: ServerPlayer): Result<BlockUseItemP> = runCatching {
        val level = player.serverLevel()
        val blockPos = req.pos.mcBpos
        if (level.isOutsideBuildHeight(blockPos) || !level.isLoaded(blockPos)) {
            throw McpBlockError("block position is not loaded")
        }
        if (!level.mayInteract(player, blockPos)) {
            throw McpBlockError("target can't be interacted")
        }
        val direction = Direction.byName(req.face.lowercase())
            ?: throw McpBlockError("invalid face ${req.face}")
        if (req.hitX !in 0.0..1.0 || req.hitY !in 0.0..1.0 || req.hitZ !in 0.0..1.0) {
            throw McpBlockError("hit point must be 0.0..1.0")
        }
        val itemStack = player.inventory.items.getOrNull(req.invSlot) ?: throw McpBadSlotError()
        if (itemStack.isEmpty) {
            throw McpBlockError("slot ${req.invSlot} is empty")
        }

        val blockBefore = level.getBlockState(blockPos)
        if (blockBefore.isAir) {
            throw McpBlockError("block is air")
        }
        val itemBefore = itemStack.toString()
        val hitResult = BlockHitResult(
            Vec3(blockPos.x + req.hitX, blockPos.y + req.hitY, blockPos.z + req.hitZ),
            direction,
            blockPos,
            false,
        )
        val result = withToolSlot(player, req.invSlot) {
            player.gameMode.useItemOn(player, level, player.mainHandItem, InteractionHand.MAIN_HAND, hitResult)
        }
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND, true)
        }
        player.inventory.setChanged()
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()

        BlockUseItemP(
            pos = req.pos,
            invSlot = req.invSlot,
            itemBefore = itemBefore,
            itemAfter = player.inventory.items[req.invSlot].toString(),
            blockBefore = blockBefore.block.resId.toString(),
            blockAfter = level.getBlockState(blockPos).block.resId.toString(),
            result = result.name,
        )
    }

    private fun breakPositions(
        player: ServerPlayer,
        positions: List<RBlockPos>,
        toolInvSlot: Int?,
        noPickup: Boolean,
        test: Boolean,
    ): BlockActionP {
        val failures = mutableMapOf<Err, MutableList<RBlockPos>>()
        var count = 0
        for (pos in positions) {
            when (val result = runCatching { breakOne(player, pos, toolInvSlot, noPickup, test) }.getOrElse(::failureResult)) {
                is ActionResult.Ok<*> -> count++
                is Err -> failures.getOrPut(result) { mutableListOf() } += pos
            }
        }
        return BlockActionP(count, failures, test)
    }

    private fun breakOne(
        player: ServerPlayer,
        pos: RBlockPos,
        toolInvSlot: Int?,
        noPickup: Boolean,
        test: Boolean,
    ): ActionResult<Unit> {
        val level = player.serverLevel()
        val blockPos = pos.mcBpos
        val targetCheck = checkBreakTarget(player, blockPos)
        if (targetCheck is Err) {
            return targetCheck
        }
        if (toolInvSlot != null && player.inventory.items.getOrNull(toolInvSlot) == null) {
            return Err("bad slot")
        }
        if (test) {
            return Ok(Unit)
        }
        val beforeIds = if (noPickup) emptySet() else freshItemEntityIds(player, blockPos)
        val broken = withToolSlot(player, toolInvSlot) {
            player.gameMode.destroyBlock(blockPos)
        }
        if (!broken) {
            return Err("rejected")
        }
        if (!noPickup) {
            pickupFreshBlockDrops(player, blockPos, beforeIds)
        }
        player.inventory.setChanged()
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        return Ok(Unit)
    }

    private fun place(
        player: ServerPlayer,
        positions: List<RBlockPos>,
        block: Block,
        stateOverrides: Map<String, String>,
        test: Boolean,
    ): BlockActionP {
        val failures = mutableMapOf<Err, MutableList<RBlockPos>>()
        var count = 0
        for (pos in positions) {
            when (val result = runCatching { placeOne(player, pos, block, stateOverrides, test) }.getOrElse(::failureResult)) {
                is ActionResult.Ok<*> -> count++
                is Err -> failures.getOrPut(result) { mutableListOf() } += pos
            }
        }
        return BlockActionP(count, failures, test)
    }

    private fun checkBreakTarget(player: ServerPlayer, pos: BlockPos): ActionResult<Unit> {
        val level = player.serverLevel()
        if (level.isOutsideBuildHeight(pos)) {
            return Err("out of chunk build height")
        }
        if (!level.isLoaded(pos)) {
            return Err("chunk not loaded")
        }
        if (level.getBlockState(pos).isAir) {
            return Err("air")
        }
        if (!level.mayInteract(player, pos)) {
            return Err("target can't be interacted")
        }
        return Ok(Unit)
    }

    private inline fun <T> withToolSlot(player: ServerPlayer, toolInvSlot: Int?, action: () -> T): T {
        val selectedSlot = player.inventory.selected
        if (toolInvSlot == null || toolInvSlot == selectedSlot) {
            return action()
        }
        val items = player.inventory.items
        items.getOrNull(toolInvSlot) ?: throw McpBadSlotError()
        val selectedStack = items[selectedSlot]
        items[selectedSlot] = items[toolInvSlot]
        items[toolInvSlot] = selectedStack
        return try {
            action()
        } finally {
            val toolAfterAction = items[selectedSlot]
            items[selectedSlot] = items[toolInvSlot]
            items[toolInvSlot] = toolAfterAction
        }
    }

    private fun freshItemEntityIds(player: ServerPlayer, pos: BlockPos): Set<Int> {
        return blockDropSearchBox(pos).itemEntities(player)
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun pickupFreshBlockDrops(player: ServerPlayer, pos: BlockPos, beforeIds: Set<Int>) {
        blockDropSearchBox(pos).itemEntities(player)
            .filter { it.id !in beforeIds && it.tickCount == 0 }
            .forEach { entity ->
                val stack = entity.item.copy()
                entity.discard()
                player.inventory.placeItemBackInInventory(stack)
            }
    }

    private fun blockDropSearchBox(pos: BlockPos): AABB {
        return AABB(pos).inflate(1.0)
    }

    private fun AABB.itemEntities(player: ServerPlayer): List<ItemEntity> {
        return player.serverLevel().getEntitiesOfClass(ItemEntity::class.java, this)
    }

    private fun placeOne(
        player: ServerPlayer,
        pos: RBlockPos,
        block: Block,
        stateOverrides: Map<String, String>,
        test: Boolean,
    ): ActionResult<Unit> {
        val level = player.serverLevel()
        val blockPos = pos.mcBpos
        val targetCheck = checkPlaceTarget(player, blockPos)
        if (targetCheck is Err) {
            return targetCheck
        }
        val beforeState = level.getBlockState(blockPos)
        if (!beforeState.isAir) {
            return Err("not air")
        }
        val stack = findPlaceStack(player, block) ?: return Err("insufficient item")
        val blockItem = stack.item as BlockItem
        val hitResult = placeHitResult(player, blockPos)
        if (!level.mayInteract(player, hitResult.blockPos)) {
            return Err("target protected")
        }
        val placeContext = BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hitResult)
        val plannedState = blockItem.block.getStateForPlacement(placeContext) ?: return Err("can't find state for placement ")
        applyState(plannedState, stateOverrides)
        if (test) {
            return Ok(Unit)
        }
        val result = blockItem.place(placeContext)
        val placedState = level.getBlockState(blockPos)
        if (!result.consumesAction() || placedState.isAir) {
            return Err("rejected")
        }
        val overrideState = applyState(placedState, stateOverrides)
        if (overrideState != placedState) {
            level.setBlock(blockPos, overrideState, 3)
        }
        player.inventory.setChanged()
        player.inventoryMenu.broadcastChanges()
        player.containerMenu.broadcastChanges()
        return Ok(Unit)
    }

    private fun failureResult(e: Throwable): Err {
        return Err(e.message ?: e.javaClass.simpleName)
    }



    private fun checkPlaceTarget(player: ServerPlayer, pos: BlockPos): ActionResult<Unit> {
        val level = player.serverLevel()
        if (level.isOutsideBuildHeight(pos)) {
            return Err("out of chunk build height")
        }
        if (!level.isLoaded(pos)) {
            return Err("chunk not loaded")
        }

        if (!level.mayInteract(player, pos)) {
            return Err("target can't be interacted")
        }
        return Ok(Unit)
    }

    private fun countPlaceItems(player: ServerPlayer, block: Block): Int {
        return player.inventory.items.sumOf { stack ->
            if (stack != null && !stack.isEmpty && (stack.item as? BlockItem)?.block == block) stack.count else 0
        }
    }

    private fun findPlaceStack(player: ServerPlayer, block: Block): ItemStack? {
        return player.inventory.items.firstOrNull { stack ->
            stack != null && !stack.isEmpty && (stack.item as? BlockItem)?.block == block
        }
    }

    private fun Iterable<ItemStack>.harvestStacks(): List<BlockHarvestResultP.HarvestStack> {
        return filterNot(ItemStack::isEmpty)
            .groupingBy { it.item.resId.toString() }
            .fold(0) { count, stack -> count + stack.count }
            .map { (itemId, count) -> BlockHarvestResultP.HarvestStack(itemId, count) }
    }

    private fun ItemStack.harvestStack(): BlockHarvestResultP.HarvestStack {
        return if (isEmpty) {
            BlockHarvestResultP.HarvestStack(ContainerSlot.EMPTY_ITEM_ID, 0)
        } else {
            BlockHarvestResultP.HarvestStack(item.resId.toString(), count)
        }
    }

    private fun placeHitResult(player: ServerPlayer, target: BlockPos): BlockHitResult {
        val level = player.serverLevel()
        val faces = arrayOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN)
        for (face in faces) {
            val clickedPos = target.relative(face.opposite)
            if (!level.isLoaded(clickedPos) || level.getBlockState(clickedPos).isAir || !level.mayInteract(player, clickedPos)) {
                continue
            }
            return BlockHitResult(Vec3.atCenterOf(target), face, clickedPos, false)
        }
        return BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
    }

    private fun applyState(state: BlockState, overrides: Map<String, String>): BlockState {
        var next = state
        for ((name, value) in overrides) {
            val property = next.block.stateDefinition.getProperty(name) ?: throw McpBadBlockStateError()
            next = setPropertyValue(next, property, value) ?: throw McpBadBlockStateError()
        }
        return next
    }

    private fun <T : Comparable<T>> setPropertyValue(state: BlockState, property: Property<T>, rawValue: String): BlockState? {
        return property.getValue(rawValue).map { state.setValue(property, it) }.orElse(null)
    }

}
