package calebxzau.rdi.mc.server.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("minecraft")
@PrefixGameTestTemplate(false)
public final class EntitySectionLimitGameTests {
    private EntitySectionLimitGameTests() {
    }

    @GameTest(template = "bastion/blocks/air", timeoutTicks = 20)
    public static void slimeSplitDoesNotReenterEntityLimit(GameTestHelper helper) {
        BlockPos position = new BlockPos(1, 1, 1);
        Slime parent = helper.spawn(EntityType.SLIME, position);
        parent.setSize(4, true);

        for (int i = 0; i < 511; i++) {
            helper.spawn(EntityType.MARKER, position);
        }

        parent.setHealth(0.0F);
        parent.remove(Entity.RemovalReason.KILLED);

        if (!parent.isRemoved()) {
            helper.fail("The dying parent slime was not removed");
        }
        helper.assertEntityPresent(EntityType.SLIME, position, 4.0);

        int entityCount = helper.getLevel()
                .getEntitiesOfClass(Entity.class, parent.getBoundingBox().inflate(1.0), entity -> true)
                .size();
        if (entityCount > 512) {
            helper.fail("Entity section exceeded the limit: " + entityCount);
        }
        helper.succeed();
    }
}
