package org.wannes.plugModCore.web;

import fi.iki.elonen.NanoHTTPD;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.module.ModuleContainer;
import org.wannes.plugModCore.module.ModuleInfo;
import org.wannes.plugModCore.module.ModuleState;
import org.wannes.plugModCore.module.ModuleManager;

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
        if (uri.equals("/api/modules") && method == Method.GET) return listModulesJson();
        if (uri.equals("/modules/upload") && method == Method.POST) return handleModuleUpload(session);

        if (uri.startsWith("/modules/load/") && method == Method.POST) return loadModule(uri);
        if (uri.startsWith("/modules/unload/") && method == Method.POST) return unloadModule(uri);
        if (uri.startsWith("/modules/enable/") && method == Method.POST) return enableModule(uri);
        if (uri.startsWith("/modules/disable/") && method == Method.POST) return disableModule(uri);
        if (uri.startsWith("/modules/delete/") && method == Method.POST) return deleteModule(uri);

        // serve static web assets deployed to the plugin data folder (and fallback to classpath)
        if (uri.startsWith("/web/") && method == Method.GET) return serveStatic(uri);

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

            // determine temp uploaded file path
            String tempPath = null;
            if (files.containsKey("file")) tempPath = files.get("file");
            if (tempPath == null && !files.isEmpty()) tempPath = files.values().iterator().next();
            if (tempPath == null) return badRequest("Upload mislukt: geen bestand gevonden");

            File tempFile = new File(tempPath);
            if (!tempFile.exists()) return badRequest("Upload mislukt: tijdelijk bestand niet gevonden");

            // original filename from parms (multipart parser usually sets the field value to filename)
            String originalFileName = session.getParms().get("file");
            if (originalFileName == null || originalFileName.trim().isEmpty()) {
                // attempt to use temp file name as fallback
                originalFileName = tempFile.getName();
            }

            if (!originalFileName.toLowerCase().endsWith(".jar")) {
                return badRequest("Alleen .jar bestanden toegestaan");
            }

            File modulesDir = new File(plugin.getDataFolder(), "modules");
            modulesDir.mkdirs();

            String internalId;
            do {
                internalId = org.wannes.plugModCore.util.IdUtils.newInternalId(12);
            } while (plugin.getRegistryManager() != null && plugin.getRegistryManager().exists(internalId));

            // Make a final copy for use in lambdas / inner classes
            final String chosenInternalId = internalId;

            File target = new File(modulesDir, chosenInternalId + ".jar");
            Files.copy(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            String sha256 = org.wannes.plugModCore.util.HashUtils.sha256(target);
            if (plugin.getRegistryManager() != null) {
                plugin.getRegistryManager().registerNew(chosenInternalId, originalFileName, sha256);
            }

            plugin.getModuleManager().scanModules();
            // Ensure upload only registers the file and marks it UPLOADED.
            // Do NOT attempt to load or enable the module here; loading should
            // be an explicit action by the user or controlled by the auto-load
            // configuration on startup.
            try {
                ModuleManager mgr = plugin.getModuleManager();
                ModuleContainer mc = mgr.getModules().stream()
                        .filter(x -> x.getInternalId().equals(chosenInternalId))
                        .findFirst().orElse(null);
                if (mc != null) {
                    mc.setState(ModuleState.UPLOADED);
                    if (plugin.getRegistryManager() != null) plugin.getRegistryManager().setModuleState(chosenInternalId, ModuleState.UPLOADED.name());
                }
            } catch (Exception ignored) {}
            plugin.getLogger().info("Module uploaded: " + chosenInternalId);

            String json = "{\"status\":\"ok\",\"id\":\"" + jsonEscape(chosenInternalId) + "\"}";
            return newFixedLengthResponse(Response.Status.OK, "application/json", json);

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

    private Response serveStatic(String uri) {
        try {
            String rel = uri.substring("/web/".length());

            // first try data folder (deployed assets)
            File f = new File(plugin.getDataFolder(), "web" + File.separator + rel);
            if (f.exists() && f.isFile()) {
                byte[] data = Files.readAllBytes(f.toPath());
                String ct = contentTypeByName(rel);
                String body = new String(data, StandardCharsets.UTF_8);
                return newFixedLengthResponse(Response.Status.OK, ct, body);
            }

            // fallback to packaged resource in classpath
            try (InputStream in = plugin.getResource("web/" + rel)) {
                if (in != null) {
                    String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return newFixedLengthResponse(Response.Status.OK, contentTypeByName(rel), s);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500: " + e.getMessage());
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    private String contentTypeByName(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff") || name.endsWith(".woff2")) return "font/woff";
        return "text/plain";
    }

    private Response listModulesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");

        boolean first = true;
        for (ModuleContainer m : plugin.getModuleManager().getModules()) {
            if (!first) sb.append(",");
            first = false;

            ModuleInfo info = m.getInfo();
            sb.append("{");
            sb.append("\"internalId\":\"").append(jsonEscape(m.getInternalId())).append("\"");
            sb.append(",\"fileName\":\"").append(jsonEscape(m.getFileName())).append("\"");
            sb.append(",\"state\":\"").append(jsonEscape(String.valueOf(m.getState()))).append("\"");

            if (info != null) {
                sb.append(",\"name\":\"").append(jsonEscape(info.name)).append("\"");
                sb.append(",\"description\":\"").append(jsonEscape(info.description)).append("\"");
                sb.append(",\"websiteEnabled\":").append(info.websiteEnabled);
                sb.append(",\"websiteEntry\":\"").append(jsonEscape(info.websiteEntry)).append("\"");
                sb.append(",\"websiteTitle\":\"").append(jsonEscape(info.websiteTitle)).append("\"");
                sb.append(",\"valid\":").append(info.valid);
                sb.append(",\"error\":\"").append(jsonEscape(info.error)).append("\"");
            } else {
                sb.append(",\"name\":\"\"");
                sb.append(",\"description\":\"\"");
                sb.append(",\"websiteEnabled\":false");
                sb.append(",\"websiteEntry\":\"\"");
                sb.append(",\"websiteTitle\":\"\"");
                sb.append(",\"valid\":false");
                sb.append(",\"error\":\"\"");
            }

            sb.append("}");
        }

        sb.append("]");

        return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
