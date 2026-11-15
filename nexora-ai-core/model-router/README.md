# NEXORA Free-only Model Router

This is the policy and interface contract for the future model gateway. No inference provider is enabled in the prototype.

## Mandatory policy
- Permit only local models installed by the user under a compatible license, or hosted endpoints verified to have a genuinely free tier at the time of use.
- Reject paid-only endpoints and all paid fallback routes.
- Never auto-upgrade, top up, or bill.
- When a free quota is exhausted, return NO_FREE_ROUTE and pause. Do not retry through a paid route.
- Keep provider credentials on the host/server, encrypted at rest, and never expose them to browser JavaScript or logs.
- Re-check provider terms, quotas, and model availability before enabling hosted free endpoints.

## Registry fields
Provider, model ID, capabilities, context limit, modalities, license, endpoint health, quota notes, last verification time, local/hosted type, and zero-cost eligibility.

## Router scoring
Task type, context size, code capability, image support, latency, endpoint health, license compatibility, and verified zero-cost eligibility. The cost gate is a hard filter, not a ranking preference.

## Current implementation status
The dashboard is a front-end prototype. No live model API or model inference is configured. This document defines required behavior; it does not claim inference is implemented.
