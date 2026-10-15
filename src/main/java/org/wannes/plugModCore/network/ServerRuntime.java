package org.wannes.plugModCore.network;

import org.bukkit.Bukkit;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

/** Game, API and implementation versions are separate runtime facts. */
public record ServerRuntime(String implementation, String minecraft, String api, String build,
                            boolean paperCompatible, boolean regionThreaded) {
    public static ServerRuntime detect() {
        var info = ServerBuildInfo.buildInfo();
        return new ServerRuntime(info.brandName(), minecraftVersion(), Bukkit.getBukkitVersion(),
                info.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                info.isBrandCompatible(Key.key("papermc:paper")),
                info.isBrandCompatible(Key.key("papermc:folia")));
    }

    public static String minecraftVersion() {
        String version = Bukkit.getServer().getMinecraftVersion();
        if (version == null || version.isBlank())
            throw new IllegalStateException("Server did not provide its Minecraft game version");
        return version;
    }
}
