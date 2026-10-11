package org.wannes.plugModCore.module;

import org.bukkit.command.*;
import java.util.*;

public final class ModuleCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ACTIONS = List.of("list", "info", "load", "unload", "enable", "disable", "reload", "webui");
    private final ModuleManager manager;
    private final org.bukkit.plugin.Plugin plugin;
    public ModuleCommand(ModuleManager manager, org.bukkit.plugin.Plugin plugin) { this.manager = manager; this.plugin = plugin; }
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugmodcore.manage")) { sender.sendMessage("§cServer Manager: permission denied."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("webui")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("port") || args.length > 3) {
                sender.sendMessage("§eUsage: /" + label + " webui port [1-65535]"); return true;
            }
            var core = (org.wannes.plugModCore.PlugModCore) plugin;
            int current = core.getListeningWebPort();
            if (args.length == 2) {
                sender.sendMessage("§7WebUI: " + (current < 0 ? "not listening; configured port " + core.getCoreConfig().webPort : "http://" + core.getCoreConfig().webHost + ":" + current)); return true;
            }
            try {
                if (!args[2].matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port must be an integer from 1 to 65535.");
                int port = Integer.parseInt(args[2]);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be an integer from 1 to 65535.");
                core.changeWebPort(port);
                sender.sendMessage("§aWebUI listening on http://" + core.getCoreConfig().webHost + ":" + core.getListeningWebPort() + ". Port saved for restart. Existing browser connections must reconnect; update any reverse proxy upstream port.");
            } catch (IllegalArgumentException failure) {
                sender.sendMessage("§c" + failure.getMessage() + " Usage: /" + label + " webui port <port>");
            } catch (java.io.IOException | RuntimeException failure) {
                sender.sendMessage("§cWebUI port change failed: " + failure.getMessage() + ". Check whether the port is occupied, reserved or blocked, and whether core-config.yml is writable. Previous listener/configuration preserved.");
                plugin.getLogger().warning("WebUI port change failed: " + failure);
            }
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            sender.sendMessage("§6Server Manager §7— " + manager.getModules().size() + " modules");
            for (ModuleContainer m : manager.getModules()) sender.sendMessage((m.getState() == ModuleState.ENABLED ? "§a" : m.getState() == ModuleState.FAILED ? "§c" : "§7") + m.getInfo().id + " §7" + m.getInfo().version + " — " + m.getState());
            return true;
        }
        if (args.length != 2 || !ACTIONS.contains(args[0].toLowerCase(Locale.ROOT)) || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§eUsage: /" + label + " list | info/load/unload/enable/disable/reload <module> | webui port [1-65535]"); return true;
        }
        ModuleContainer m = manager.findModule(args[1]);
        if (m == null) { sender.sendMessage("§cUnknown module: " + args[1]); return true; }
        try {
            if (args[0].equalsIgnoreCase("info")) {
                    sender.sendMessage("§6" + m.getInfo().name + " §7" + m.getInfo().version + " — " + m.getState());
                    sender.sendMessage("§7Dependencies: " + m.getInfo().dependencies);
                    if (m.getInfo().error != null) sender.sendMessage("§c" + m.getInfo().error);
                    return true;
            }
            manager.executeAsync(args[0].toLowerCase(Locale.ROOT), m).whenComplete((state, failure) -> {
                Runnable report = () -> sender.sendMessage(failure == null ? "§aServer Manager: " + m.getInfo().id + " — " + state : "§cServer Manager: " + failure.getMessage());
                if (org.bukkit.Bukkit.isPrimaryThread()) report.run(); else org.bukkit.Bukkit.getScheduler().runTask(plugin, report);
            });
        } catch (RuntimeException e) { sender.sendMessage("§cServer Manager: operation failed. " + (m.getState() == ModuleState.FAILED ? "See server log; state is FAILED." : e.getMessage())); }
        return true;
    }
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugmodcore.manage")) return List.of();
        List<String> values = args.length == 1 ? ACTIONS : args.length == 2 ? (args[0].equalsIgnoreCase("webui") ? List.of("port") : manager.getModules().stream().map(m -> m.getInfo().valid ? m.getInfo().id : m.getInternalId()).toList()) : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
