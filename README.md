# Chokehold Mod

![License](https://img.shields.io/badge/License-MIT-blue)
![Build](https://img.shields.io/badge/Build-Passing-brightgreen)

A mod that adds a player-vs-player chokehold / restrain mechanic with a timing-based duel minigame.

## 🎮 Concept

- One player (the **chokeholder**) sneaks + right-clicks another player with an empty hand.
- The two enter a **ChokeholdState** and play a wheel-pressing minigame:
  - Each round, both players press **Space** once.
  - The restricted player with the higher wheel value wins the round.
  - Repeatedly winning rounds builds a streak; reaching the streak threshold lets the restrained player escape.
- If the restrained player's air reaches 0, they enter **FaintedState** — they lie down, can't move/attack, are chat-muted (and voice-muted if Simple Voice Chat is installed), and get a modal **"YOU ARE K.O."** screen with a rescue countdown. Their health is set to half a heart and stays there, and they take full damage — anyone can finish them off while they're down.

## ⚙️ Configuration

Config file: `config/chokehold-common.toml`. All options are documented in the file itself. Highlights:

| Setting | Description | Default |
|---------|-------------|---------|
| `airMax` | Maximum air for restrained player | `100` |
| `airLossPerRound` | Air lost when chokeholder wins a round | `10` |
| `airGainPerGasp` | Air gained from successful gasp QTE | `15` |
| `streakToEscape` | Consecutive wins needed to escape | `5` |
| `wheelRotationTicks` | Base wheel rotation speed (ticks/revolution) | `50` |
| `zone1Points` .. `zone4Points` | Point values for each zone | `1, 2, 3, 5` |
| `zone1Arc` .. `zone4Arc` | Arc degrees for each zone | `20, 30, 40, 30` |
| `zoneShrinkDegrees` | Gap between zones (miss band) | `5` |
| `faintedInvulnerable` | Whether fainted players are invulnerable | `false` |
| `svcEnabled` | Enable Simple Voice Chat integration | `true` |
| `maxChokeholdRange` | Max distance to initiate chokehold (blocks) | `1.0` |

**Note:** Zone arcs need *not* sum to 360 — leftover degrees become a miss band, so a small total arc = a precision gauge.

## 🎤 Simple Voice Chat Integration

If `voicechat` is on the classpath *and* `svcEnabled = true`, the mod registers a `VoicechatPlugin` that cancels outgoing microphone packets for restrained / fainted players.

If SVC isn't installed, the `META-INF/services` entry simply doesn't resolve to anything SVC looks up — the mod loads normally and voice muting is silently skipped. Text chat muting works whether or not SVC is present.

## 📁 File Layout

```
src/main/java/com/chokehold/chokehold/
├── ChokeholdMod.java                 # @Mod entry point
├── config/ChokeholdConfig.java       # ForgeConfigSpec
├── item/ModItems.java                # Item registry
├── capability/
│   ├── ChokeholdState.java           # State object
│   ├── ChokeholdStateProvider.java   # Capability provider
│   ├── FaintedState.java             # Faint state
│   ├── FaintedStateProvider.java     # Capability provider
│   └── CapabilityAttachers.java      # @SubscribeEvent attacher
├── network/
│   ├── ModNetworking.java            # All packets + dispatch
│   └── PacketHelper.java             # Wheel math
├── event/ChokeholdEventHandlers.java # Server-side game logic
├── client/
│   ├── ClientChokeholdCache.java     # Local cache for QTE / K.O. screens
│   ├── ClientInputHandler.java       # Modal-screen lifecycle safety net + particle cue
│   ├── ChokeholdQTEScreen.java       # Modal QTE screen (locks input, draws wheel + hit markers)
│   ├── ChokeholdFaintedScreen.java   # Modal K.O. screen (locks input while fainted)
│   └── ClientSetup.java              # Creative tab
├── entity/
│   ├── ModEntities.java              # Entity registry
│   └── TestDummyEntity.java          # Test dummies for solo play
├── command/ChokeholdCommand.java     # /chokehold summon commands
└── voice/ChokeholdVoicechatPlugin.java  # SVC plugin (optional)
```

## 🧠 Technical Notes

- **Server-authoritative wheel**: The needle angle is computed server-side from a per-round start tick and rotation speed, so clients can render the needle locally without per-tick packets. Search for `S2CWheelSyncPacket` for the protocol.
- **Accelerating difficulty**: The wheel speeds up +75% per round (100% → 175% → 250% → 325% → 400% cap), resetting on double-miss.
- **Per-partner cooldown**: 10s cooldown against a specific player after a chokehold ends, separate from the 1s global anti-spam cooldown.
- **Timing**: All Space-press timing is server-authoritative. Client timestamps are used for display only; the server compares them to its own game time before computing zone values.
- **Movement/attack/item-use** is cancelled for fainted players and for the restrained player during a chokehold.
- **Chat muting**: In-game chat is muted for the restrained player via `ServerChatEvent`; voice chat is muted via the SVC plugin.
- **Faint rendering**: Uses vanilla `startSleeping()` (sets `SLEEPING_POS` so `isSleeping()` is true) — a free lying-down render without custom model work.
- **Visual feedback**: After each round, both players' hits are pinned on the wheel (green = you, red = opponent) and shown in the result banner.

## 📝 Commands

| Command | Description |
|---------|-------------|
| `/chokehold summon easy [pos]` | Spawn easy dummy (random presses) |
| `/chokehold summon normal [pos]` | Spawn normal dummy (never misses) |
| `/chokehold summon hard [pos]` | Spawn hard dummy (always max zone) |
| `/chokehold summon chokeholder [pos]` | Spawn chokeholder dummy (seeks players) |

Requires cheats (permission level 2). Intended for single-player testing.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

*Built with NeoForge for Minecraft 1.20.1+*
