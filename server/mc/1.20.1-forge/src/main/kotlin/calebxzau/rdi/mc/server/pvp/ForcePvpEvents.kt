package calebxzau.rdi.mc.server.pvp

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraftforge.event.entity.living.LivingAttackEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = "rdi")
object ForcePvpEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onAttackEntity(event: AttackEntityEvent) {
        if (blocksInteraction(event.entity, event.target)) {
            // Forge posts this before Item.onLeftClickEntity, which can modify health directly.
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
        if (blocksInteraction(event.entity, event.target)) {
            event.cancellationResult = InteractionResult.FAIL
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onEntityInteractSpecific(event: PlayerInteractEvent.EntityInteractSpecific) {
        if (blocksInteraction(event.entity, event.target)) {
            event.cancellationResult = InteractionResult.FAIL
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onLivingAttack(event: LivingAttackEvent) {
        if (blocksDamage(event.entity, event.source)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onLivingHurt(event: LivingHurtEvent) {
        if (blocksDamage(event.entity, event.source)) {
            event.isCanceled = true
        }
    }

    private fun blocksInteraction(actor: Player, target: Entity): Boolean =
        actor is ServerPlayer && target is Player && actor.uuid != target.uuid &&
            ForcePvpSavedData.get(actor.server).protectionEnabled

    private fun blocksDamage(target: Entity, source: DamageSource): Boolean {
        if (target !is ServerPlayer) return false
        val attacker = playerOwner(source.entity) ?: playerOwner(source.directEntity) ?: return false
        return attacker.uuid != target.uuid && ForcePvpSavedData.get(target.server).protectionEnabled
    }

    // Only standard player/projectile attribution. Custom area effects need separate compatibility.
    private fun playerOwner(entity: Entity?): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.owner as? Player
        else -> null
    }
}
