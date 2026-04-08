# PlugModCore

PlugModCore is a modular Minecraft server core with a web dashboard for module management.

> Project status: **beta**
>
> APIs, behavior, and module contracts may change while the system is stabilized.

## What it does

- Loads and manages modules without restarting the full server.
- Provides a web dashboard to upload, load, enable, disable, and unload modules.
- Keeps module-specific logic inside modules instead of hardcoding it in the core.
- Supports module web pages and module-owned API routes.

## Current beta scope

- Core loader and module lifecycle management are active.
- Dashboard-based module management is active.
- Dynamic module web routing is active.
- Ongoing improvements to UX, validation, and runtime safety are in progress.

## Quick start

### 1. Build

```bash
mvn -DskipTests clean package
```

### 2. Install

- Copy the generated PlugModCore jar into your server `plugins/` folder.
- Start your server.

### 3. Open dashboard

- Open the PlugModCore web dashboard in your browser.
- Upload a module jar.
- Use actions in the dashboard to load and enable the module.

## Module lifecycle

Typical flow:

1. Upload module jar
2. Load module
3. Enable module
4. Update settings through the module web UI (if provided)
5. Disable or unload when needed

## How to create a module

Use this flow to build your own PlugModCore module.

### 1. Create a Maven project

- Create a new Java/Maven project for your module.
- Add a dependency on the PlugModCore API artifact.
- API repository: https://github.com/AnecaW/PlugModCore-API

Example dependency:

```xml
<dependency>
	<groupId>org.wannes</groupId>
	<artifactId>plugmodcore-api</artifactId>
	<version>YOUR_VERSION</version>
	<scope>provided</scope>
</dependency>
```

### 2. Implement a module class

- Create a class that implements the API `Module` contract.
- Implement lifecycle methods such as enable/disable behavior.
- Keep all module-specific logic inside this module project.

### 3. Add module metadata

- Add `module.info.yml` to your module resources.
- Define at least:
	- `id` (unique module id)
	- `name`
	- `version`
	- `mainClass` (fully qualified class name)

Minimal example:

```yaml
id: example-module
name: Example Module
version: 1.0.0
mainClass: org.example.module.ExampleModule
```

### 4. (Optional) Add module web UI

- Put module web assets in your module resources (for example under `web/`).
- Expose a website entry in your module metadata if your module has a web page.
- Optionally implement module-owned API handling for custom endpoints.

### 5. Build and upload

- Build your module jar with Maven.
- Upload the jar through the PlugModCore dashboard.
- Load and enable it from the dashboard.

## Useful links

- PlugModCore API repository (for module development): https://github.com/AnecaW/PlugModCore-API
- Example ScoreBoard module repository (already built and working): https://github.com/AnecaW/ScoreBoard

### Install API dependency (Maven)

If you build a module in a separate repository, you can consume the API directly from GitHub via JitPack.

1. Add the JitPack repository:

```xml
<repositories>
	<repository>
		<id>jitpack.io</id>
		<url>https://jitpack.io</url>
	</repository>
</repositories>
```

2. Add the PlugModCore API dependency:

```xml
<dependency>
	<groupId>com.github.AnecaW</groupId>
	<artifactId>PlugModCore-API</artifactId>
	<version>TAG_OR_RELEASE</version>
	<scope>provided</scope>
</dependency>
```

Use a real Git tag or release name as version (for example `v1.0.0`).

## Project structure

- `src/main/java/`: Java source code for PlugModCore.
- `src/main/resources/web/`: dashboard frontend files.
- `src/main/resources/templates/`: module and config templates.
- `scripts/`: local helper scripts.

## API and web behavior

- Dashboard endpoints manage module states and actions.
- Modules can expose their own web pages.
- Modules can optionally expose module-owned API handlers through the core.

## Beta notes

- Backward compatibility is not guaranteed yet.
- Error messages and validation are still being refined.
- Performance tuning and edge-case handling are ongoing.

## Roadmap

- Improve dashboard feedback and diagnostics.
- Expand module template/examples.
- Add broader automated testing around module lifecycle operations.
- Harden hot-reload behavior for complex modules.

## Contributing

Feedback and testing reports are welcome during beta.

If you open an issue, include:

- Server version
- PlugModCore version
- Module used
- Steps to reproduce
- Logs or stack traces

## License

This project is proprietary and released under **All Rights Reserved** for the PlugModCore loader/core.

Independent module development against the PlugModCore API is allowed.
That permission does not extend to copying, modifying, or redistributing PlugModCore source code itself.

- See `LICENSE` for full terms.
- See `COPYRIGHT` for copyright notice.
- No use, redistribution, or modification is allowed without prior written permission.

© 2026 by Wannes Aneca. 

