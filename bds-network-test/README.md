# NEXORA — isolated BDS network test

This folder describes an **isolated network proof-of-connection**, not a replacement for the NEXORA runtime.

## Goal

Temporarily use Mojang's official Bedrock Dedicated Server (BDS) as the network/login baseline for an unmodified Bedrock client. Once a phone can join BDS on the McpeHost allocation, we will know that the host's TCP+UDP route and the client network are capable of carrying the current NetherNet transport.

Official Minecraft support guidance says that, following the October 29, 2026 networking migration, self-hosted servers need BDS 26.60 or newer, TCP and UDP open on the same game port, and `transport=nethernet`. See:
- https://help.minecraft.net/hc/en-us/articles/49914677714957
- https://www.minecraft.net/en-us/download/server/bedrock

## Important limits

- This is a **diagnostic baseline only**. BDS is not the NEXORA game engine and does not load the existing Java JAR as a plugin.
- NEXORA's Java world generator, player model, RPG rules, HUD, custom inventory and gameplay are not discarded. They remain in the NEXORA codebase and will be integrated/reimplemented behind a compatible server adapter after the network baseline is proven.
- BDS is proprietary Mojang server software. Download it directly from the official Minecraft page and accept its license. This repository intentionally does not redistribute the BDS binary.
- McpeHost must allow a native Linux executable/custom startup command. A Java-only JAR slot cannot run BDS. Confirm this with the host before changing the live server.
- Use a **separate test server/slot** if possible. Do not overwrite the current NEXORA installation or its world data.

## Test configuration

Use an official BDS release compatible with the retail client being tested (target: Bedrock 1.26.60; BDS 26.60 or newer is required for NetherNet). Keep the BDS package's own generated `server.properties` and change only these entries:

```properties
server-name=NEXORA BDS Network Test
server-port=23472
server-portv6=23472
online-mode=true
transport=nethernet
```

Keep the host allocation's TCP signaling and UDP game mappings on the same external port, 23472. Do not change RCON or Query mappings for this test. Do not assume that an STUN-discovered address proves inbound reachability.

## Start

From the extracted official BDS directory on a supported 64-bit Ubuntu Linux host:

```bash
chmod +x bedrock_server
LD_LIBRARY_PATH=. ./bedrock_server
```

The exact startup command may need to be entered in the McpeHost startup settings. If the panel only starts Java JAR files, stop here and ask McpeHost whether they can provide a Bedrock/native executable template. Do not rename the BDS executable to `server.jar`.

## Pass/fail test

1. Start BDS on the separate test slot with TCP and UDP 23472 allocated.
2. Add the server in the retail Bedrock client using the host's actual public hostname/IP and port 23472.
3. Confirm that the client enters the world.
4. Save the BDS console log and record the exact server version shown at startup.

A successful BDS login proves only that the host/client network path works with the official server. It does not yet prove NEXORA's own login/session implementation works. If BDS also fails and produces no connection log, investigate McpeHost's native-process support and TCP/UDP forwarding before changing NEXORA code.
