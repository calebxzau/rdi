package calebxzau.rdi.mc.client.mixin;

import java.util.concurrent.CompletableFuture;
import net.neoforged.neoforge.common.IOUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to NeoForge's save task chain.
 *
 * <p>World synchronization reads this reference on the server thread right after its own
 * {@code saveEverything} call, then hands the future to a background worker so the worker
 * can wait for the work that was already queued at that moment. Nothing here mutates the
 * chain: there is no setter, no reset, and {@code withIOWorker} is untouched.
 *
 * <p>The captured future only covers tasks submitted to NeoForge's chain before the read.
 * A mod's own executor, database, or a later save is not included, and the future does not
 * prevent a future write from overwriting a file that has already been copied.
 *
 * <p>{@code IOUtilities} is a NeoForge class rather than a Minecraft one, so it is never
 * remapped.
 */
@Mixin(value = IOUtilities.class, remap = false)
public interface AccessorIOUtilities {
    @Accessor("saveDataTasks")
    static CompletableFuture<Void> rdi$getSaveDataTasks() {
        throw new AssertionError("AccessorIOUtilities mixin was not applied");
    }
}
