package calebxzhou.rdi.mc.client.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

public interface ClientEntityFailures {
    Set<UUID> rdi$hiddenEntityIds();

    static boolean isHidden(Entity entity) {
        return entity.level() instanceof ClientEntityFailures failures
                && failures.rdi$hiddenEntityIds().contains(entity.getUUID());
    }

    @SuppressWarnings("removal")
    static void record(Entity entity, String operation, Throwable error) {
        // Vanilla can wrap VM failures in a ReportedException.
        Throwable cause = error;
        Set<Throwable> seen = null;
        while (cause != null) {
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath death) throw death;
            cause = cause.getCause();
            if (cause != null) {
                if (seen == null) seen = Collections.newSetFromMap(new IdentityHashMap<>());
                if (!seen.add(cause)) break;
            }
        }
        if (entity.level() instanceof ClientEntityFailures failures
                && failures.rdi$hiddenEntityIds().add(entity.getUUID())) {
            Logger logger = LogUtils.getLogger();
            logger.error("Skipping client entity rendering after {} failure: type={}, uuid={}",
                    operation, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.getUUID(), error);
        }
    }
}
