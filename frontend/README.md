# BeatLink Monitor frontend

Svelte 5 / TypeScript / Vite web interface for the Kotlin backend.
Application-specific code is AI-generated and licensed under EPL-2.0;
third-party code retains its own licenses. See the root
[README](../README.md), [AI disclosure](../AI_DISCLOSURE.md) and
[license notices](../THIRD_PARTY_NOTICES.md).

## Development

Use Node.js 20.19+ (20.x) or 22.12+; Node 24 is recommended.
Start the backend separately with `cd backend && ./gradlew runHeadless`
from the repository root. Then, in this directory:

```sh
npm ci
npm run dev
```

Vite serves the UI on port 5173. In development the frontend connects directly
to `127.0.0.1:17812` for API and WebSocket traffic (see
[connection.ts](src/websocket/connection.ts)); no Vite proxy is configured.

```sh
npm run check
npm run build
```

Production output is written to `dist/`; Gradle embeds it in the backend JAR.
Do not expose the development server to the Internet. See the
[development guide](../Docs/DEVELOPMENT.md).
