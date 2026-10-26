# The tailnet: reaching the app from another machine

TrioSlicer runs its engines - Blender (9876), the Python CAD engine (9877) and SolidCraft (9878) -
as child processes and publishes each one's MCP session on a loopback port. Usually you drive them
from the same phone. This document is how to reach them from a computer **without adb, without a
USB cable and without the agent sitting at a desk**, over a private tailnet such as Tailscale.

The same tailnet also carries the other direction: a harness running on a computer is reachable from
the phone's browser at its tailnet address, so the phone can be the client of a desk machine and the
client of its own engines at the same time.

## 1. Root mode, not the userspace app

The official Tailscale Android app is a **userspace VPN**: traffic from the phone's apps is proxied,
but the phone cannot accept an inbound TCP connection to a loopback-listening app socket. That is why
an inbound bridge needs a publish rule. Root mode is different: the daemon runs as root, owns a real
kernel `tailscale0` interface, and the phone's tailnet address accepts inbound TCP like any other
Linux node.

The daily-driver setup used here is [TailSocks](https://github.com/bropines/tailsocks) in **root
mode**, which ships the full `tailscale` CLI:

    tailscale version                      # 1.104.1 in this setup
    ls -l /system/bin/tailscale            # the CLI lives here under root mode
    tailscale up                           # join the tailnet (also in the app's UI)
    tailscale status                       # the node and its peers
    tailscale ip -4                        # this node's 100.x.y.z address

Requirements and caveats:

- **Root.** Root mode is a root daemon; treat every device on the tailnet with the trust a root
  shell on the phone deserves. Tailnet ACLs are the boundary - use them.
- **The node name changes.** Root mode enrolls a new node (here `phone-node`); the node made by the
  userspace app (`phone-node-old`) is a different machine with a different address and should be
  removed once root mode is the daily driver, or the tailnet accumulates stale entries.
- **Excluded Apps does not help.** In root mode, per-app exclusion affects exit-node and DNS
  behaviour, not tailnet reachability: the tailnet address still accepts connections. Do not rely on
  it to hide a bridge.

## 2. Publishing the app's bridges (the easy way)

Open the CAD agent sheet and turn on **Reachable on the network**. The app then republishes **all
three engines** on this device's own address at their loopback ports:

| engine | port | behind it |
|---|---|---|
| Blender MCP | 9876 | the embedded Blender session (mesh editing, STL handoff) |
| CAD engine | 9877 | build123d/OCP on the Klipper host payload |
| SolidCraft | 9878 | the CAD document on the plate |

The address is chosen from the phone's own interfaces - **tailnet first** (an address inside
`100.64.0.0/10`), then a private LAN address, then a mobile address only if there is no LAN. The
sheet prints it, so there is nothing to configure.

Each row's **Copy** puts **one line** on the clipboard - the address and that engine's own token
together - after the fingerprint prompt:

    tcp://100.64.13.7:9878 token=<the engine's token>

That line is the whole connection: one copy, one paste into an agent on a computer. Nothing is
logged by the forwarder, and the token is shown only by `Copy` (the app is the only place a token
can be handed over on a phone without root).

The switch is **off by default** and closing it stops every published listener - the engines keep
running.

## 3. Publishing a port by hand (older builds, or a harness on the phone)

If the app's switch is not in use - an older build, or a service that is not one of the three
engines - publish the loopback port from the phone's own shell:

    tailscale serve --bg --tcp 9878 127.0.0.1:9878
    tailscale serve status           # the rule, and the URL it answers on
    tailscale serve reset            # clear every rule

With a MagicDNS name this answers on `phone-node.<tailnet>.ts.net:9878` as well as the raw address.
The same trick is how a harness hosted on the phone would be reachable from a computer.

## 4. Using the line

The bridge speaks newline-delimited JSON-RPC 2.0 and every message carries the token (top level or
inside `params`). Probe before doing anything heavy - the four possible answers say four different
things:

| answer to `initialize` | meaning |
|---|---|
| `-32001 unauthorized: send the token …` | the bridge is up and reachable. Add the token. |
| `-32010 the in-app agent is driving this document right now; retry when its turn finishes` | the bridge is up but the phone's own agent is mid-turn. Wait for it; nothing is queued for you. |
| connection reset straight after accept | the forwarder answered but the **engine is not running** - open the CAD agent sheet and connect it. |
| timeout / connection refused | the network reach is not published: the switch is off, the phone is not on the tailnet, or the address changed. |

A dead engine is not a dead network: the line can be good while nothing listens behind it. The CAD
agent sheet says which - **app bridge up** or **engine not running**.

## 5. The other direction: the phone as a client

A harness or any web UI on a computer can be served on its own loopback and published the same way,
then opened from the phone's browser at the tailnet address:

    # on the computer
    tailscale serve --bg https / http://127.0.0.1:3080
    # from the phone: https://<computer>.<tailnet>.ts.net/

This keeps the harness's port off the LAN entirely - only tailnet peers can reach it.

## 6. Troubleshooting

- `tailscale status` on the phone: is the node up, and is the *other* machine listed as a peer?
- `tailscale ping <peer>`: proves the tailnet path before anything speaks HTTP.
- The address in the sheet is the phone's, so it changes when the phone moves between networks;
  re-copy the line rather than editing it.
- A stale entry in the sheet ("no network address to show") means the phone has neither a tailnet
  nor a private LAN IPv4 address: join one of them, do not start on serve rules first.
- The app's forwarder logs no message content. If you need to see traffic, point the client at a
  loopback forward on the machine that owns the logs, not at the tailnet address.
