# Published skills

Sanitized copies of the assistant's skill files - the runbooks it follows when asked to drive hardware. See [`../AI_ASSISTANT.md`](../AI_ASSISTANT.md) for how they fit into the pipeline.

| file | what it covers |
|---|---|
| [`blender-mcp-engine.md`](blender-mcp-engine.md) | Driving the Blender engine embedded in the app over its MCP socket, and the STL export handoff into the slicer UI |
| [`image-to-3d-model.md`](image-to-3d-model.md) | Photograph to printable STL: the generator, its settings, post-processing, validation and the delivery handoff |
| [`gpu-box-power.md`](gpu-box-power.md) | Hibernating and waking the GPU box over Wake-on-LAN, and why each piece of that configuration is needed |
| [`harness-session-control.md`](harness-session-control.md) | Driving harness sessions over the HTTP API: the auth bootstrap, the request envelope, and stopping work that has already started |

## Why these are copies

The live skills live in `.dsh/skills/` and are **gitignored**, because driving real hardware needs real details: machine addresses, a login, a MAC for the wake packet, device identifiers. Those must not be published.

These copies are the same text with every one of those values substituted. A small script does the substitution so the two cannot drift apart silently:

```bash
node scripts/publish-skills.mjs
```

It reads the substitution map from `.dsh/publish-map.json` - itself gitignored, for the obvious reason - and rewrites everything in this directory. **Committing a new map entry is never necessary and never safe**; the script is what is versioned, not the values.

## Address conventions

Substituted values say what belongs there rather than inventing an address, so a
reader is told what to supply instead of being handed an example that looks real
and will not work:

| placeholder | what to put there |
|---|---|
| `<phone-lan-ip>` | the phone's address on your own network |
| `<phone-tailscale-ip>` | the phone's tailnet address - the one that works anywhere |
| `<gpu-box-lan-ip>`, `<gpu-box-tailscale-ip>` | the GPU box, on the LAN and on the tailnet |
| `<pc-lan-ip>`, `<pc-tailscale-ip>` | the machine running the harness |
| `<pi-lan-ip>`, `<pi-link-ip>` | the Raspberry Pi, on your network and on the point-to-point link |
| `<gpu-box-link-ip>`, `<link-broadcast>` | the far end and broadcast address of that link |
| `<lan-ip>`, `<windows-hotspot-ip>` | any local address, and one handed out by a Windows hotspot |
| `<wake-target-mac>` | the MAC that wake-on-LAN is addressed to |

Hostnames and logins appear as placeholders (`<user>`, `<you>`, `phone-host`, `<pi-password>`). The application's own package id is left as-is: it is public, and the commands that reference it would be useless without it.
