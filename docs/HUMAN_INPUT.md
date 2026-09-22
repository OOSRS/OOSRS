# Human mouse

OpenOSRS can deliver API interactions in two ways:

- `PACKET` submits the interaction through the game's own menu-action pipeline.
  Nothing moves on screen. This is the default.
- `HUMAN_MOUSE` moves a cursor across the game canvas and clicks, the way a
  person would. It sends mouse, wheel and key events to the game canvas only;
  your desktop pointer is never moved.

Both modes use the same service methods, the same target validation and the same
result types, so a plugin does not need two code paths.

## For players

Open **Mouse settings** in the plugin list.

| Setting | Default | What it does |
| --- | --- | --- |
| Use the human mouse | off | Plugins that use the OpenOSRS API act through the cursor instead of directly. |
| Allow packet fallback (hybrid) | off | If the cursor cannot reach a target, act directly instead of failing. |
| Rotate to reveal targets | on | Drag the camera with the middle button when a target is off screen. |
| Zoom for awkward targets | on | Scroll out when a target is on screen but too small to click comfortably. |
| Movement profile | `default` | Which saved profile shapes the movement. |
| Speed | 5 | 1 is unhurried, 10 is brisk. Changes duration, not accuracy. |
| Idle behaviour | on | Small cursor movements during long pauses of an automation session. |
| Simulate losing focus | off | Occasionally report the window as unfocused during those pauses. |
| Ignore real mouse and keyboard | off | While an automated action runs, keep your input from interrupting it. |
| Near-miss chance | 1.5% | Sometimes settle just beside a target and correct before clicking. |
| Show pointer | off | Give the human mouse a pointer of its own, with a ripple where it clicks. It fades out a moment after the cursor stops. |
| Show trail, Mark the target, Show the path ahead | off | A fading trail, corner marks on the target, and dots along the next stretch of path. |
| Show status panel | off | What the cursor is doing, what it is aiming at, and why it stopped if it did. |
| Accent colour | gold | Colour of the ripple, trail, marks and path. |

Some behaviour holds regardless of settings:

- **Nothing moves unless a plugin asks.** With the human mouse on but no plugin
  acting, the cursor stays still.
- **Idle movement never runs while you play.** It only runs during an automation
  session (the cursor acted in the last three minutes), and only after the mouse
  and keyboard have been untouched for 30 seconds.
- **You always win against idle movement.** Touching the mouse or keyboard cancels
  any idle or preparatory movement immediately. Only a running automated action
  can hold input back, and only if you enabled *Ignore real mouse and keyboard*.
- **A near miss never clicks.** The cursor hovers beside the target, pauses, then
  corrects. The click happens only after the menu under the cursor has been checked
  against the requested action.

Profiles live in `~/.openosrs/mouse-profiles`. *Learn mode* records your own
mouse use to train a profile; recordings stay on your computer.

## For plugin authors

### Choosing a mode

Plugins normally follow the player's setting. To force a mode for one piece of
work, open a scope:

```java
try (InputScope scope = InputScope.packets())
{
    OpenOSRS.bank().open();
}
```

`InputScope.humanMouse()` forces the cursor instead. Scopes nest and restore the
previous mode when closed. A cursor scope never falls back to packets, even if
the player enabled fallback: asking for the cursor means the cursor.

A scope is thread-local. Open it inside the callback that does the work, or pass
the mode along explicitly. Work queued through `ClientActions` keeps the mode that
was active when it was queued.

### Acceptance, delivery and outcome

Call services on the client thread. Methods that return a `SubmissionResult`
report three separate things:

1. `isSubmitted()` / `requireSubmitted()`: the interaction was accepted.
2. `getDelivery()`: completes `true` once the click (or packet) was actually
   delivered, `false` if it failed or was cancelled.
3. The game outcome, which only the game state can confirm: check the inventory,
   location, interface or whatever the action should change.

```java
OpenOSRS.owner(this).whileActive(() -> {
    InventoryItem item = OpenOSRS.inventory().first(itemId);
    if (item == null)
    {
        return false;
    }
    SubmissionResult result = OpenOSRS.inventory().useAsync(item);
    result.requireSubmitted();
    result.getDelivery().thenAccept(delivered -> {
        // May run off the client thread; queue client reads through ClientThread.
    });
    return true;
}, false);
```

Never call `join()` or `get()` on the client or UI thread. `cancel()` cancels a
pending submission; it cannot undo one that was delivered.

Item and spell sequences (`InventoryService.useAsync/useOnAsync`,
`MagicService.selectAsync/castOnAsync`) return one handle for the whole sequence.
The target is checked again after the selection and before the second click. An
unrelated click, a session change or the plugin stopping cancels the sequence.

### What the cursor does for each kind of action

| Action | In mouse mode |
| --- | --- |
| NPCs, objects, players, ground items, widgets | Move to the target, verify the menu entry, click. Right-click and choose when the action is not the default. |
| Walking | Click the tile. `walkTo(target, true)` holds Ctrl for the click. |
| Off-screen targets | Rotate or zoom the camera first, if enabled; otherwise the request is rejected (or falls back, if the player allows it). |
| Numeric prompts (bank X, etc.) | Type the amount and press Enter. |
| Name prompts | Type the name and press Enter. |
| Item search prompts | Type the item's name, then click its result. |
| Camera | Middle-button drag for rotation, mouse wheel for zoom. |
| Minimap zoom | Applied directly in both modes; it is local rendering and sends nothing to the game. |

When the cursor cannot perform a request it says so through the result. It never
silently switches to packets unless fallback is enabled, and it never retries
through packets after a click may already have been delivered.

## Limits

- The cursor only acts while the game canvas is showing.
- A target covered by an interface panel cannot be clicked through the panel; use
  a packet scope for it.
- Movement looks like a person using a mouse, but that is a design goal, not a
  guarantee against detection.
