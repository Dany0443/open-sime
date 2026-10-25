# Open SIME Desktop / Web (`sime-desktop`)

This directory contains the Open SIME web and desktop application (`server.js` + `public/`).

## Commands

Run inside `sime-desktop/`:

```bash
npm install     # Install dependencies
npm run check   # Syntax check server and client files
npm test        # Run automated test suite (node:test)
npm start       # Start local server on http://127.0.0.1:3000
```

## Environment Variables

- `SIME_WEB_HOST`: Bind address (default `127.0.0.1`)
- `SIME_WEB_PORT`: Port number (default `3000`)
- `SIME_DEBUG=1`: Enables `/api/debug/parser-health` (never returns personal data)
- `SIME_REMEMBER_SECRET`: Optional secret for encrypting remember-me tokens

