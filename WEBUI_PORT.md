# WebUI port administration

Use the existing root command and administrator permission (`plugmodcore.manage`, op by default):

```
/module webui port
/module webui port 8081
```

The first command reports the actual listener (or explicitly reports that it is not listening). The second accepts integer TCP ports 1–65535. Operating-system binding determines whether a port is occupied, reserved or inaccessible; privileged ports are not rejected arbitrarily on systems where binding is permitted. The configured `web.host` and authentication token remain unchanged.

The existing NanoHTTPD WebServer is replaced transactionally. Its replacement binds and answers an unauthenticated HTTP probe with the expected 401 response before an atomic update of `web.port` in `core-config.yml`. Only after that commit does the previous listener stop. Binding, response validation or configuration-write failures stop the candidate and preserve the previous listener and configuration. The same-port request is a no-op. Startup loads the persisted setting and logs the listening address. Disabled WebUI configuration is not implicitly enabled by the command.

The brief overlap uses the same existing WebServer implementation and module manager, authentication, origin checks and routes. Connections on the old port close; clients must reconnect. Relative UI/API URLs continue using the current browser origin. External reverse proxy configuration is outside PlugModCore's control: update its upstream port. This implementation currently contains no WebSocket server, so WebSocket continuity is not claimed.

## Verification

`mvn verify` runs the existing reactor tests and five new core tests covering administrator permission, help/completion, range rejection, transactional configuration-failure rollback, same-port no-op, shutdown cleanup, and real NanoHTTPD HTTP binding/replacement/occupied-port rollback/old-listener closure.

The opt-in isolated Paper test exercises the actual console command, the default port, invalid inputs, occupied-port failure, new-port dashboard/API/static assets, authentication/origin protection, old-listener closure and restart persistence:

```
python PlugModCore/src/test/paper/webui-port.py --accept-eula --paper <Paper jar>
```

It requires `JAVA_HOME`, the built core JAR, and explicit EULA acceptance. It creates a temporary server directory, binds only localhost, and stops its server in cleanup. It does not change a production server. Per-run logs and results are saved in that temporary directory. No graphical in-game testing or external reverse proxy deployment is covered.

Verified on 2026-10-10: reactor `mvn verify` succeeded (29 core tests passed; two existing opt-in PlaceholderAPI tests skipped). All 17 isolated Paper 1.21.11 checks passed, including a full stop/restart. The temporary server was stopped. Build output: `webui-port-build.log`; integration output: `webui-port-paper.log`; detailed server logs/results: `C:\Users\wanne\AppData\Local\Temp\plugmodcore-port-test-8xtep6i5`.
