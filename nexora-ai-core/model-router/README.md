# NEXORA Free-only Model Router

## Current implementation

The Android dashboard now includes a text chat bridge to OpenRouter's free-model router:
- Endpoint: `https://openrouter.ai/api/v1/chat/completions`
- Fixed model selector: `openrouter/free`
- The app does not accept a user-selected model ID and cannot switch to a paid model.
- A provider quota or no-route response becomes `NO_FREE_ROUTE`; there is no paid retry.
- The OpenRouter key is encrypted with Android Keystore (AES-GCM) before it is stored in app-private preferences.
- Only the most recent 12 chat messages are sent as context; local chat history stays in the dashboard's local storage.
- This is text chat only. It does not yet read project files, run terminal commands, build APKs, or autonomously edit repositories.

## Setup

1. Create an OpenRouter account and create an API key.
2. Use only free-model routing; do not add credits or enable paid usage if you want a strict no-spend setup.
3. Open NEXORA AI Host → Dashboard → ИИ-разработчик.
4. Paste the key in the OpenRouter field and save it. The key is encrypted on the Android device.
5. If the free route is unavailable or its quota is exhausted, wait for the provider's reset or try later. NEXORA will not fall back to paid inference.

OpenRouter documents that the `openrouter/free` router selects from free model routes. Its free variants have rate limits (currently documented as 20 requests/minute and 50 requests/day for `:free` models; limits and availability can change). Verify the current limits before relying on them:
- https://openrouter.ai/collections/free-models/

## Important privacy and cost notes

- The API key is stored encrypted with Android Keystore; it is not embedded in the website JavaScript or source repository.
- Prompts are sent to OpenRouter and may be routed to a third-party model provider. Do not send passwords, private keys, or other secrets.
- The app fixes the model to `openrouter/free` and does not implement a paid fallback. Still, keep the OpenRouter account without purchased credits or paid billing if you want to ensure no charges can occur outside the app.
- Free access is quota-limited and not unlimited. Provider terms, free model availability, and privacy practices can change.

## Planned next steps

- Add a provider status and quota screen.
- Add a safe project-file context picker.
- Implement an isolated terminal/build worker with explicit permission boundaries.
- Add agent planning, checkpoints, and approval before file writes or commands.
- Add model/provider health checks and a daily verification of free-only eligibility.

## Mandatory policy

- Permit only local models installed by the user under a compatible license, or hosted endpoints verified to have a genuinely free route at the time of use.
- Reject paid-only endpoints and all paid fallback routes.
- Never auto-upgrade, top up, or bill.
- When a free quota is exhausted, return `NO_FREE_ROUTE` and pause.
- Keep provider credentials on the host/server, encrypted at rest, and never expose them to browser JavaScript or logs.
- Re-check provider terms, quotas, and model availability before enabling hosted free endpoints.
