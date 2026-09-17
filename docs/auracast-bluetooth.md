# Auracast Bluetooth — Research & Design Notes

Research notes on Auracast broadcast audio (the broadcast feature of Bluetooth
LE Audio), covering the protocol, the roles involved, security, and the
current state of open-source/platform support — as a starting point for
deciding what to build in this repo.

## 1. What Auracast is

Auracast is the public-facing brand name for **LE Audio broadcast audio**: a
single transmitter encodes audio once (with the LC3 codec) and broadcasts it
over Bluetooth Low Energy to an unlimited number of receivers, with no
pairing and no connection-count limit. It sits on top of capabilities
introduced in Bluetooth Core 5.2 (isochronous channels, LE Audio) and is
defined by ~20 SIG specifications rather than a single spec.

Typical use cases:

- **Public venues** — TVs in airports/gyms, PA systems, cinemas, conference
  rooms: anyone in range with a compatible receiver can tune in, like a radio
  station.
- **Assistive listening** — hearing aids joining a broadcast instead of a
  venue's legacy induction loop / FM assistive system.
- **Personal sharing** — one phone broadcasting to multiple nearby
  earbuds/speakers (e.g. two people sharing one video's audio).

## 2. Protocol stack

LE Audio/Auracast is a stack of profiles built on GATT + isochronous
channels, not one monolithic protocol:

| Profile | Role |
|---|---|
| **BAP** (Basic Audio Profile) | Foundation. Defines Unicast Server/Client, Broadcast Source/Sink, Broadcast Assistant, Scan Delegator roles; mandatory codec/QoS configs. |
| **PBP** (Public Broadcast Profile) | Adds the "Auracast-compliant" rules on top of BAP: mandates Standard Quality (16–24 kHz, always present) and optional High Quality (48 kHz) tiers, and the Public Broadcast Announcement (UUID `0x1856`) that marks a stream as publicly joinable. |
| **CAP** (Common Audio Profile) | Coordinates sets of devices (e.g. a pair of earbuds) — Acceptor/Initiator/Commander roles for synchronized volume/mute and unicast↔broadcast handover. |
| **TMAP** (Telephony & Media Audio Profile) | Consumer product roles: Broadcast Media Sender / Broadcast Media Receiver. |
| **HAP** (Hearing Access Profile) | Hearing-aid presets, remote control, alerts. |
| **GMAP** (Gaming Audio Profile) | Low-latency (20–30 ms) QoS profile for gaming. |

### Core mechanics

- **BIG / BIS** — A Broadcast Source creates a *Broadcast Isochronous Group*
  (BIG) containing one or more *Broadcast Isochronous Streams* (BIS), each
  carrying LC3-encoded audio (e.g. separate BIS per language track).
- **Extended Advertising** carries the *Broadcast Audio Announcement*
  (`0x1852`, BAP) and, for Auracast, the *Public Broadcast Announcement*
  (`0x1856`, PBP).
- **Periodic Advertising (PA)** carries the *Basic Audio Announcement*, which
  contains the **BASE** (Broadcast Audio Source Endpoint) structure —
  codec config, channel allocation, presentation delay, language, program
  info (e.g. "News", "Gate A12 announcements").
- Receivers scan for extended ads, then synchronize to the PA to read the
  BASE, then sync to the BIG to receive audio — all without a GATT
  connection or pairing.

### Roles

- **Broadcast Source** — transmitter (TV, PA system, phone).
- **Broadcast Sink** — receiver (earbud, hearing aid, speaker).
- **Scan Delegator** — runs on a sink; hosts the **BASS** (Broadcast Audio
  Scan Service) GATT service so a helper device can control what the sink
  syncs to. Mandatory on all Auracast receivers.
- **Broadcast Assistant** (a.k.a. Public Broadcast Assistant, PBA) — a helper
  device (typically a phone) that scans for nearby broadcasts and writes
  "sync to this stream" info into a sink's BASS *Broadcast Receive State*
  characteristic. This is what makes Auracast usable on constrained sinks
  (hearing aids with no display/UI) and is the basis of the "Auracast finder"
  apps used to discover broadcasts in a venue.

## 3. Security

Encryption is optional per broadcast, via a 16-octet **Broadcast_Code**:

- Unset → open broadcast, anyone can join (typical for public venues —
  universal accessibility is the point).
- Set → receivers need the code, obtained out-of-band (QR code, NFC tap, or
  entered manually via an Assistant app) before they can decrypt the BIS.

There's no pairing/bonding and no link-layer authentication beyond that
code, so Auracast's security model is closer to "shared secret for a radio
station" than to classic Bluetooth's link security — worth being explicit
about in any design that assumes confidentiality.

## 4. Security research: the broadcast model *is* the attack surface

This is the sharpest edge case in the spec, and it's not theoretical —
it's been demonstrated end-to-end by security researchers (ERNW/Insinuator,
presented at 38C3 as "Auracast: Breaking Broadcast LE Audio Before It Hits
the Shelves") with a public toolkit, plus several vendor CVEs. The root
cause: Auracast is a **connectionless, receive-only broadcast** by design —
a sink never talks back to a source at the link layer, so there is no
mechanism for a sink to verify *who* is sending, only *whether* a PDU
matches a key it already has.

### 4.1 Unencrypted broadcasts: no authenticity, period

With no Broadcast_Code, any PDU that fits the format and matching stream
parameters is accepted — a sink can't distinguish the real source from an
attacker with a stronger signal. The 2023 **BISON** paper demonstrated
hijacking an unencrypted broadcast by injecting forged control PDUs
(channel-map updates) that redirect a synced receiver mid-stream to
attacker-controlled audio, with no user-visible warning.

### 4.2 Encrypted broadcasts: "perimeter" auth only, and the perimeter is thin

Encryption doesn't fix the authenticity gap — it just moves it. Anyone
holding the Broadcast_Code can produce validly-encrypted PDUs, so
possession of the code is treated as identity ("perimeter authentication"),
not proof of being the original source. Worse, the code itself is often
weak in practice:

- The Group Session Key is derived from the Broadcast_Code via nested
  **AES-CMAC** (functions h6/h7/h8) — a good PRF, but explicitly *not* a
  key-stretching function (no iteration/cost factor like PBKDF2/Argon2).
  Short or guessable codes are therefore crackable **offline**, with no
  interaction with — or detection by — the source or sink.
- An attacker only needs **one captured BIS PDU + the BIGInfo packet**
  (broadcast in the clear as part of periodic advertising) to start
  cracking. Researchers' `biscrack` tool did ~10s/entry against
  `rockyou.txt` on a laptop for dictionary-style codes, and official SIG
  example material itself used weak codes like `"12345"` or
  `"PinotNoir"`.
- **No forward secrecy**: PDUs can be captured and stored now, and
  decrypted retroactively once the code is cracked later.
- Real-world defaults made this trivial rather than theoretical:
  - **CVE-2025-20908** — Samsung Galaxy S23/S24 + Galaxy Buds generated the
    default Broadcast_Code from `UUID.randomUUID().toString().substring(0, 4)`
    — 2 random bytes (16 bits) of entropy. `biscrack -m numeric -l 2`
    cracked it in **under one second**. (Samsung's fix adopted AOSP's
    approach: 12 hex chars / 6 bytes / 48 bits.)
  - **CVE-2025-32330** — Android's own `generateRandomPassword` in
    `LocalBluetoothLeBroadcast.java` used an insecure default, letting a
    proximal attacker intercept broadcast audio with zero user interaction
    (fixed in the September 2025 Android Security Bulletin).
  - **CVE-2025-21002** — improper access control (CWE-284) in Samsung's
    `LeAudioService` let a co-resident local app tamper with a device's
    Auracast broadcast session/parameters without authorization.

### 4.3 Availability: the hopping sequence is public too

The frequency-hopping sequence that's supposed to give Bluetooth its
jamming resilience is transmitted **in plaintext** inside BIGInfo, even for
encrypted broadcasts. That enables selective/efficient jamming, and the
same research describes a lightweight DoS ("BISQuit") that disconnects
encrypted-broadcast receivers using only a handful of crafted PDUs — no
brute force required.

### 4.4 Tooling used by researchers

The published **Auracast Hacker's Toolkit** (Zephyr-based, built for the
Nordic nRF52840 USB dongle) implements passive sniffing, `biscrack`
(Broadcast_Code cracking), active BIS hijacking (BISON), the BISQuit DoS,
and broadcast cloning — i.e. the full kill chain from "sniff a stream" to
"replace it with your own," end to end, on ~$10 hardware.

### 4.5 Why this matters for any design here

This isn't a one-off bug, it's structural: Auracast trades connection
overhead for a security model with no sender authentication at the
protocol level, and vendors have repeatedly shipped weak
Broadcast_Code generation on top of that. Anything built in this repo
that touches Auracast should treat that as a given rather than an
implementation detail to patch later:

- A **scanner/assistant** only reads public announcement data — low risk,
  but should not assume a discovered stream's claimed identity (program
  info, source name) is trustworthy, since it's attacker-controllable.
- A **transmitter** should default to a properly random, full-length
  (16-byte) Broadcast_Code when encryption is requested, and should not
  reuse the weak "short numeric/dictionary code" patterns the spec's own
  examples model.
- A **receiver/sink** is the most exposed role (accepts and plays whatever
  matches the code/params) — any implementation here is itself a subject
  for the same hijacking/jamming testing described above before being
  trusted.

## 5. Platform / implementation landscape (as of 2026)

- **Android** — Auracast support landed as of Android 16 (broadcast
  source/assistant UI, "Nearby streams" style discovery).
- **iOS** — no public LE Audio broadcast source/sink support at this
  writing; treat as unsupported unless re-verified at implementation time.
- **Linux / BlueZ** — active, but not yet SIG-qualified:
  - Needs kernel ≥ 6.4 (ISO socket support; newer kernels recommended),
    a recent BlueZ, and PipeWire/WirePlumber (LE Audio is on by default
    there unless overridden).
  - BlueZ needs experimental features enabled in `/etc/bluetooth/main.conf`
    (`Experimental = true`, `KernelExperimental = ...,<ISO socket UUID>`).
  - Controller support varies by vendor even within one vendor's lineup
    (e.g. Intel BE200 supports Auracast, Intel AX110 does not) — check via
    `bluetoothctl`'s management menu for `iso-broadcaster`/`sync-receiver`
    (broadcast) vs `cis-central`/`cis-peripheral` (unicast) flags before
    assuming a given adapter works.
  - Demonstrated working: MediaTek Genio 700 (MT7921 controller), Sona
    IF573 (CYW55573) on Raspberry Pi 5 — both via Collabora's BlueZ +
    PipeWire work. Full SIG qualification of that stack is targeted within
    2026, not done yet.
- **Embedded / chipset SDKs** — Nordic nRF5340 Audio DK + nRF Connect SDK is
  the most mature dedicated dev platform; NXP IW612 and ST STM32WBA also
  have LE Audio stacks.

## 6. Implications for this repo

Given the above, "Auracast" isn't a single thing to implement — it's a
choice of role(s) and platform. Before writing code, the project needs to
pick a lane; broad options and their trade-offs:

1. **Broadcast source/transmitter** (e.g. turn a Linux box or embedded
   board into an Auracast PA system) — needs BlueZ's broadcast APIs
   (unqualified stack, real hardware caveats above), lowest-level and most
   hardware-dependent option.
2. **Broadcast Assistant / scanner app** (e.g. a "find nearby Auracast
   streams" tool) — mainly BLE scanning for the announcement UUIDs
   (`0x1852`/`0x1856`) + reading BASE off the PA; doesn't require acting as
   a sink itself, so it's the lightest-weight option and works from a
   normal BLE central role.
3. **Full receiver/sink** — heaviest option: needs isochronous receive
   support in the controller and OS stack, currently the least mature on
   Linux.
4. **Documentation/spec-study only** — no code, just deeper spec work (e.g.
   BASE parsing reference, a from-scratch encoder for announcements) as
   prep for one of the above.
5. **Security research / auditing tooling** (given §4) — e.g. a
   Broadcast_Code-strength checker, a scanner that flags weak/default
   codes on nearby broadcasts, or reproducing the published
   sniff/crack/hijack chain against *own* hardware for defensive testing.
   Highest research value, but scope and authorization boundaries (own
   devices/lab only, no targeting third-party broadcasts) need to be
   explicit before writing anything here.

None of this is committed to yet — this file is the research baseline.
Next step is deciding which of the above (or another direction) `bluebro`
should actually build toward.

## Sources

- [An overview of Auracast™ broadcast audio — Bluetooth SIG](https://www.bluetooth.com/wp-content/uploads/2024/05/2505_Paper_An-Overview-of-Auracast.pdf)
- [Bluetooth LE Audio, Auracast™ broadcast audio, and the future of Bluetooth audio](https://www.bluetooth.com/blog/le-audio-auracast-broadcast-audio-and-the-future-of-bluetooth-audio/)
- [How Auracast™ broadcast audio is expanding audio streaming — 2026 outlook](https://www.bluetooth.com/blog/how-auracast-broadcast-audio-is-expanding-audio-streaming-and-a-look-at-the-market-impact-it-could-have-in-2026-and-beyond/)
- [Bluetooth LE Audio and Auracast: The Profile Stack Explained — Novel Bits](https://novelbits.io/bluetooth-le-audio-auracast-profiles/)
- [Auracast — Wikipedia](https://en.wikipedia.org/wiki/Auracast)
- [How to build an Auracast™ transmitter — Bluetooth SIG](https://www.bluetooth.com/wp-content/uploads/2024/05/2403_How_To_Auracast_Transmitter.pdf)
- [Developing Auracast™ receivers with an assistant application — Bluetooth SIG](https://www.bluetooth.com/wp-content/uploads/2023/03/Developing_Auracast_Receivers-Legacy_Smartphones.pdf)
- [Implementing Bluetooth on embedded Linux: BlueZ vs proprietary stacks — Collabora](https://www.collabora.com/news-and-blog/blog/2025/02/27/implementing-bluetooth-on-embedded-linux-with-open-source-bluez-vs-proprietary-stacks/)
- [Implementing Bluetooth LE Audio & Auracast on Linux systems — Collabora](https://www.collabora.com/news-and-blog/blog/2025/11/24/implementing-bluetooth-le-audio-and-auracast-on-linux-systems/)
- [BlueZ-powered Auracast broadcasting on Genio 700 — Collabora](https://www.collabora.com/news-and-blog/blog/2026/05/05/bluez-powered-auracast-broadcasting-on-genio-700/)
- [Part I: Bluetooth Auracast from a Security Researcher's Perspective — Insinuator.net](https://insinuator.net/2025/01/auracast-part1/)
- [Auracast: Breaking Broadcast LE Audio Before It Hits the Shelves — 38C3 talk](https://media.ccc.de/v/38c3-auracast-breaking-broadcast-le-audio-before-it-hits-the-shelves)
- [CVE-2025-20908: Use of insufficiently random values in Samsung's Auracast implementation — Insinuator.net](https://insinuator.net/2025/03/cve-2025-20908-use-of-insufficiently-random-values-in-samsungs-auracast-implementation/)
- [CVE-2025-32330 — Wiz Vulnerability Database](https://www.wiz.io/vulnerability-database/cve/cve-2025-32330)
- [CVE-2025-21002 — Wiz Vulnerability Database](https://www.wiz.io/vulnerability-database/cve/cve-2025-21002)
- [auracast-research/auracast-hackers-toolkit — GitHub](https://github.com/auracast-research/auracast-hackers-toolkit)
