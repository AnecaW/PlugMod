# PlugModCore — Beta

This repository contains the PlugModCore plugin and a small embedded web dashboard to manage modules.

## Beta checklist

- Cleaned: `.gitignore` added, IDE/workspace files removed from repo.
- Frontend: `src/main/resources/web/dashboard.html`, `dashboard.js`, `styles.css` separated and cleaned.
- Upload behaviour: server registers uploads without auto-loading modules.
- UX: confirmation modal, toast notifications, and disabled action buttons based on state.

## Local cleanup
Run the supplied cleanup script to remove local IDE/build artifacts before committing:

```powershell
pwsh ./scripts/cleanup.ps1
git rm -r --cached target || echo 'target not tracked'
git add .gitignore
git commit -m "chore: repo cleanup for beta"
```

## Build & Run
This is a Maven Java project. Build with:

```bash
mvn -DskipTests clean package
```

Install the built plugin jar per your server/runtime. The web dashboard is served from the plugin's resources under `/web`.

## Development notes
- Frontend files are under `src/main/resources/web/`.
- API endpoints used by the dashboard: `/api/modules`, `/modules/upload`, `/modules/load/...`, `/modules/enable/...`, etc.
- Use the dashboard to upload/register modules — uploads are registered with state `UPLOADED` and do not auto-load.

## Formatting
I applied focused frontend refactors and removed legacy files. If you want uniform Java code formatting, run your preferred formatter (e.g. `mvn fmt:format` or an IDE reformat) — I can apply a formatting pass if you want.

## Next steps (recommended before public beta)
1. Run full formatting on Java sources and validate compilation.
2. Add automated tests for module lifecycle (optional).
3. Add CHANGELOG and versioning for beta release.

If you want, I can (A) run a Java formatting pass and tidy imports, (B) add a changelog and release notes, or (C) do both.
