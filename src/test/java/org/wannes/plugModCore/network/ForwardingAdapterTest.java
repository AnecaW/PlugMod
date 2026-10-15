package org.wannes.plugModCore.network;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ForwardingAdapterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    ServerRuntime runtime(String minecraft, String api) {
        return new ServerRuntime("Paper", minecraft, api, "fixture build", true, false);
    }
    Path configuration() throws Exception {
        Path root = temp.newFolder().toPath(); Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-global.yml"), "_version: 31\nproxies:\n  velocity:\n    enabled: false\n    online-mode: true\n    secret: ''\n");
        Files.writeString(root.resolve("server.properties"), "online-mode=true\n");
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  bungeecord: false\n");
        return root;
    }
    @Test public void betaApiBuildIsNeverComparedToMinecraftVersion() throws Exception {
        Path root = configuration();
        var support = ForwardingAdapter.inspect(runtime("26.3", "26.3.build.170-beta"), root);
        assertTrue(support.reason(), support.available());
        assertEquals("paper-global-26.3", support.adapter());
        ForwardingAdapter.requireWritable(runtime("26.3", "26.3.build.170-beta"), root);
        assertTrue(ForwardingAdapter.inspect(runtime("1.21.11", "1.21.11-R0.1-SNAPSHOT"), root).available());
    }
    @Test public void unknownGameVersionDoesNotInventForwardingFields() throws Exception {
        Path root = configuration(); byte[] before = Files.readAllBytes(root.resolve("config/paper-global.yml"));
        var support = ForwardingAdapter.inspect(runtime("26.4", "26.4.build.1-alpha"), root);
        assertFalse(support.available()); assertTrue(support.reason().contains("Management pairing remains available"));
        assertThrows(IllegalStateException.class, () -> ForwardingAdapter.requireWritable(runtime("26.4", "build"), root));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("config/paper-global.yml")));
    }
    @Test public void schemaAndTypesAreValidatedBeforeAnyWrite() throws Exception {
        Path root = configuration(); Path yaml = root.resolve("config/paper-global.yml");
        Files.writeString(yaml, Files.readString(yaml).replace("31", "99"));
        assertTrue(ForwardingAdapter.inspect(runtime("26.3", "build"), root).reason().contains("schema 99"));
        Files.writeString(yaml, Files.readString(yaml).replace("99", "31").replace("enabled: false", "enabled: bad"));
        assertFalse(ForwardingAdapter.inspect(runtime("26.3", "build"), root).available());
    }
    @Test public void conflictingBungeeConfigurationRemainsUntouched() throws Exception {
        Path root = configuration(); Path spigot = root.resolve("spigot.yml");
        Files.writeString(spigot, "settings:\n  bungeecord: true\n"); byte[] before = Files.readAllBytes(spigot);
        assertTrue(assertThrows(IllegalStateException.class, () -> ForwardingAdapter.requireWritable(runtime("26.3", "build"), root)).getMessage().contains("BungeeCord"));
        assertArrayEquals(before, Files.readAllBytes(spigot));
    }
    @Test public void regionThreadedImplementationIsNotAssumedToUsePaperLifecycle() throws Exception {
        assertFalse(ForwardingAdapter.inspect(new ServerRuntime("Folia", "26.3", "build", "build", true, true), configuration()).available());
    }
    @Test public void environmentSecretIsTheEffectiveRuntimeValueWithoutRewritingItsSource() {
        assertEquals("env-fixture-secret",ForwardingAdapter.effectiveSecret("older-yaml-secret","env-fixture-secret"));
        assertEquals("yaml-fixture-secret",ForwardingAdapter.effectiveSecret("yaml-fixture-secret",null));
        assertEquals("yaml-fixture-secret",ForwardingAdapter.effectiveSecret("yaml-fixture-secret",""));
    }
}
