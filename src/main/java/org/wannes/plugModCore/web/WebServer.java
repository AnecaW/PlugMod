package org.wannes.plugModCore.web;

import fi.iki.elonen.NanoHTTPD;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.module.ModuleContainer;
import org.wannes.plugModCore.module.ModuleState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class WebServer extends NanoHTTPD {

    private final PlugModCore plugin;

    public WebServer(PlugModCore plugin, String hostname, int port) {
        super(hostname, port);
        this.plugin = plugin;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {

        String uri = session.getUri();
        Method method = session.getMethod();

        if (uri.equals("/") && method == Method.GET) return showDashboard();
        if (uri.equals("/modules") && method == Method.GET) return listModules();
        if (uri.equals("/modules/upload") && method == Method.POST) return handleModuleUpload(session);

        if (uri.startsWith("/modules/load/") && method == Method.POST) return loadModule(uri);
        if (uri.startsWith("/modules/unload/") && method == Method.POST) return unloadModule(uri);
        if (uri.startsWith("/modules/enable/") && method == Method.POST) return enableModule(uri);
        if (uri.startsWith("/modules/disable/") && method == Method.POST) return disableModule(uri);
        if (uri.startsWith("/modules/delete/") && method == Method.POST) return deleteModule(uri);

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    /* =========================
       DASHBOARD
       ========================= */
    private Response showDashboard() {
        try (InputStream in = plugin.getResource("web/dashboard.html")) {
            if (in == null) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>PlugModCore Dashboard</h1><p>Resource missing</p>");
            }
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return newFixedLengthResponse(Response.Status.OK, "text/html", s);
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>PlugModCore Dashboard</h1><p>Error</p>");
        }
    }

    /* =========================
       MODULE UPLOAD
       ========================= */
    @SuppressWarnings("deprecation")
    private Response handleModuleUpload(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            String originalFileName = session.getParms().get("file");
            if (originalFileName == null || !originalFileName.toLowerCase().endsWith(".jar")) {
                return badRequest("Alleen .jar bestanden toegestaan");
            }

            String tempPath = files.get("file");
            if (tempPath == null) return badRequest("Upload mislukt");

            File tempFile = new File(tempPath);

            File modulesDir = new File(plugin.getDataFolder(), "modules");
            modulesDir.mkdirs();

            String internalId;
            do {
                internalId = org.wannes.plugModCore.util.IdUtils.newInternalId(12);
            } while (plugin.getRegistryManager().exists(internalId));

            File target = new File(modulesDir, internalId + ".jar");
            Files.copy(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            String sha256 = org.wannes.plugModCore.util.HashUtils.sha256(target);
            plugin.getRegistryManager().registerNew(internalId, originalFileName, sha256);

            plugin.getModuleManager().scanModules();
            plugin.getLogger().info("Module uploaded: " + internalId);

            return redirect("/modules");

        } catch (Exception e) {
            e.printStackTrace();
            return badRequest("Upload mislukt: " + e.getMessage());
        }
    }

    /* =========================
       MODULE LIST (LIVE REFRESH)
       ========================= */
    private Response listModules() {
        // build only the module list fragment
        StringBuilder listHtml = new StringBuilder();
        listHtml.append("<h1>Modules</h1><ul>");

        for (ModuleContainer m : plugin.getModuleManager().getModules()) {

            listHtml.append("<li id='module-")
                    .append(m.getInternalId())
                    .append("' data-id='")
                    .append(m.getInternalId())
                    .append("' data-state='")
                    .append(m.getState())
                    .append("'>");

            listHtml.append(m.getInfo() != null && m.getInfo().name != null
                    ? m.getInfo().name
                    : m.getFileName());

            listHtml.append(" (").append(m.getState()).append(")");

            listHtml.append(" <small style='color:gray'>[")
                    .append(m.getInternalId())
                    .append("]</small> ");

            if (m.getState() == ModuleState.UPLOADED) {
                action(listHtml, "Load", "/modules/load/" + m.getInternalId());
                action(listHtml, "Delete", "/modules/delete/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.DISABLED) {
                action(listHtml, "Enable", "/modules/enable/" + m.getInternalId());
                action(listHtml, "Unload", "/modules/unload/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.ENABLED) {
                action(listHtml, "Disable", "/modules/disable/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.FAILED) {
                action(listHtml, "Unload", "/modules/unload/" + m.getInternalId());
                action(listHtml, "Delete", "/modules/delete/" + m.getInternalId());
            }

            listHtml.append("</li>");
        }

        listHtml.append("</ul><p><a href='/'>Terug</a></p>");

        // load template from resources and replace placeholder (now located under web/)
        try (InputStream in = plugin.getResource("web/modules.html")) {
            if (in == null) {
                // fallback: return fragment directly
                return newFixedLengthResponse(Response.Status.OK, "text/html", listHtml.toString());
            }

            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String page = template.replace("{{MODULE_LIST}}", listHtml.toString());
            return newFixedLengthResponse(Response.Status.OK, "text/html", page);

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.OK, "text/html", listHtml.toString());
        }
    }

    /* =========================
       ACTIONS
       ========================= */
    private Response enableModule(String uri) {
        return doAction(uri, "/modules/enable/", m -> plugin.getModuleManager().enableModule(m));
    }

    private Response disableModule(String uri) {
        return doAction(uri, "/modules/disable/", m -> plugin.getModuleManager().disableModule(m));
    }

    private Response loadModule(String uri) {
        return doAction(uri, "/modules/load/", m -> plugin.getModuleManager().loadModule(m));
    }

    private Response unloadModule(String uri) {
        return doAction(uri, "/modules/unload/", m -> plugin.getModuleManager().unloadModule(m));
    }

    private Response deleteModule(String uri) {
        return doAction(uri, "/modules/delete/", m -> {
            plugin.getModuleManager().deleteModule(m);
            plugin.getModuleManager().scanModules();
        });
    }

    /* =========================
       HELPERS
       ========================= */
    private Response doAction(String uri, String prefix, java.util.function.Consumer<ModuleContainer> action) {
        String id = uri.replace(prefix, "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getInternalId().equals(id))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        action.accept(module);
        return redirect("/modules");
    }

    private void action(StringBuilder html, String label, String path) {
        // markeer formulieren zodat client-side JS ze kan onderscheppen
        html.append("<form method='post' action='")
                .append(path)
                .append("' class='module-action' style='display:inline'>")
                .append("<button>")
                .append(label)
                .append("</button></form> ");
    }

    private Response redirect(String path) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", path);
        return r;
    }

    private Response badRequest(String message) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", message);
    }
}
