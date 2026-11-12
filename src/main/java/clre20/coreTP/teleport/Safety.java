package clre20.coreTP.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.EnumSet;
import java.util.Set;

/** 落點安全判斷。呼叫前必須確定該區塊已載入。 */
public final class Safety {

    private static final Set<Material> DANGEROUS_GROUND = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.WITHER_ROSE);

    private Safety() {
    }

    /** 給家、地標、歷史位置用的寬鬆檢查：不會窒息、不在岩漿或火裡。 */
    public static boolean isSafeDestination(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (burns(feet.getType()) || burns(head.getType())) {
            return false;
        }
        if (burns(feet.getRelative(BlockFace.DOWN).getType())) {
            return false;
        }
        return !head.getType().isOccluding();
    }

    /** 給隨機傳送用的嚴格檢查：腳下是實心且無害的方塊，身體兩格可通過。 */
    public static boolean isSafeStand(Block ground) {
        Material g = ground.getType();
        if (!g.isSolid() || DANGEROUS_GROUND.contains(g)) {
            return false;
        }
        Block feet = ground.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);
        return clear(feet) && clear(head);
    }

    private static boolean clear(Block b) {
        return b.isPassable() && !b.isLiquid() && !DANGEROUS_GROUND.contains(b.getType());
    }

    private static boolean burns(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE;
    }
}
