package org.wannes.plugModCore.module;

import org.bukkit.command.CommandSender;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class WebPortCommandTest {
    private final List<String> messages = new ArrayList<>();
    private CommandSender sender(boolean allowed) {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) { assertEquals("plugmodcore.manage", args[0]); return allowed; }
            if (method.getName().equals("sendMessage")) { messages.add((String) args[0]); return null; }
            return null;
        });
    }
    @Test public void existingAdministratorPermissionGuardsCommandAndCompletion() {
        var command = new ModuleCommand(null, null);
        command.onCommand(sender(false), null, "module", new String[]{"webui", "port", "8081"});
        assertTrue(messages.getFirst().contains("permission denied"));
        assertEquals(List.of(), command.onTabComplete(sender(false), null, "module", new String[]{"web"}));
    }
    @Test public void helpAndCompletionIncludeWebuiPort() {
        var command = new ModuleCommand(null, null);
        assertEquals(List.of("webui"), command.onTabComplete(sender(true), null, "module", new String[]{"web"}));
        assertEquals(List.of("port"), command.onTabComplete(sender(true), null, "module", new String[]{"webui", "p"}));
        assertEquals(List.of(), command.onTabComplete(sender(true), null, "module", new String[]{"webui", "port", "8"}));
        command.onCommand(sender(true), null, "module", new String[]{"webui"});
        assertTrue(messages.getFirst().contains("/module webui port [1-65535]"));
    }
}
