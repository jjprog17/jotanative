package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which gamepad is player 1 (GameActivity's input devices; pure Java, so PadOrderJvmTest checks it on the JVM). Android reports more than controllers as gamepads:
 * a phone's own input devices (Xiaomi and POCO phones have "uinput-xiaomi", which is neither virtual nor silent about gamepad sources) took player 1 by connecting
 * first, and a game that reads only player 1 ignored the real controller on player 2 (issues #42, #62). What Android reports decides, never the device's name:
 * <ul>
 * <li>A device's rank (rank()): the device chosen as player 1 (the in-game panel's Player 1, by its descriptor); then an external device (USB, Bluetooth:
 *     InputDevice.isExternal) with gamepad buttons and a stick; another external gamepad; a built-in one with gamepad buttons, a stick and a vendor ID; the rest.</li>
 * <li>External devices and the chosen one get a player when they connect, best rank first (at the start the order of ids within a rank). The others get one only
 *     from their first input (a gamepad button, or a stick or trigger pushed past half: wakes()), so a device that never sends any takes no player and does not hide
 *     the on-screen controls. A built-in controller (a handheld's) gets its player from its first press.</li>
 * <li>A device that gets a player takes player 1 when player 1 is free or held by a device of a worse rank (Host.first: they trade players); one that gets it by
 *     its input also when player 1's device is of the same rank and no controller (BUILT_IN, OTHER): the device the player uses wins over one that woke by itself.</li>
 * </ul>
 * JOTANATIVE: two more choices for several players on one screen (a handheld on a TV with external controllers). Player 2 (the in-game panel's Player 2, by
 * descriptor) is moved to player 2 once it and player 1 have players (placeSecond); it never takes player 1 while another device could. "Use the handheld's own
 * controls" off (builtInOff) gives a built-in device (not InputDevice.isExternal) no player at all, and takes back the one it has, so the external controllers
 * are players 1 and 2.
 */
final class PadOrder {
    /** What Android reports of an input device (InputDevice): its id, descriptor (stable across reconnects and restarts), name, isExternal, gamepad buttons plus X / Y axes, vendor ID */
    static final class Pad {
        final int id; final String key, name; final boolean external, full; final int vendor;
        Pad(int id, String key, String name, boolean external, boolean full, int vendor) {
            this.id = id; this.key = key == null ? "" : key; this.name = name == null ? "" : name; this.external = external; this.full = full; this.vendor = vendor;
        }
    }

    /** The host's players (Native.deviceAdded / deviceFirst / devicePlayer) */
    interface Host {
        int add(Pad p);     // the player it gets (0..7), -1 with every player taken
        int first(Pad p);   // it becomes player 1 (player 1's device takes its old player): 0, or -1
        int player(int id); // -1: none
        default void remove(Pad p) {}   // JOTANATIVE: it gives back its player (Native.deviceRemoved), as if it went away
    }

    // JOTANATIVE: SECOND, the Player 2 choice, joins at connect like an external device but ranks after every other external one for player 1
    static final int CHOSEN = 0, CONTROLLER = 1, EXTERNAL = 2, SECOND = 3, BUILT_IN = 4, OTHER = 5;

    static int rank(Pad p, String chosen) { return rank(p, chosen, ""); }

    static int rank(Pad p, String chosen, String second) {
        if (chosen != null && !chosen.isEmpty() && chosen.equals(p.key)) return CHOSEN;
        if (second != null && !second.isEmpty() && second.equals(p.key)) return SECOND;
        if (p.external) return p.full ? CONTROLLER : EXTERNAL;
        return p.full && p.vendor != 0 ? BUILT_IN : OTHER;
    }

    /** Gets a player when it connects (else from its first input) */
    static boolean atConnect(Pad p, String chosen) { return atConnect(p, chosen, ""); }
    static boolean atConnect(Pad p, String chosen, String second) { return rank(p, chosen, second) <= SECOND; }

    /** JOTANATIVE: a built-in device (not external) while the handheld's own controls are off: it gets no player */
    static boolean blocked(Pad p, boolean builtInOff) { return builtInOff && !p.external; }

    /** One joystick event's values: true when one is pushed past half (a stick, trigger or hat), not a resting stick's noise */
    static boolean wakes(float[] values, int n) {
        for (int i = 0; i < n; i++) if (Math.abs(values[i]) > 0.5f) return true;
        return false;
    }

    private final Host host;
    private final Map<Integer, Pad> pads = new LinkedHashMap<>();   // gamepad-like devices connected
    private final Set<Integer> playing = new LinkedHashSet<>();   // those that have a player
    private String chosen;
    private String second = "";   // JOTANATIVE: the Player 2 choice, a descriptor ("" = automatic)
    private boolean builtInOff;   // JOTANATIVE: the handheld's own controls get no player

    PadOrder(Host host, String chosen) { this(host, chosen, "", false); }

    PadOrder(Host host, String chosen, String second, boolean builtInOff) {
        this.host = host; this.chosen = chosen == null ? "" : chosen; this.second = second == null ? "" : second; this.builtInOff = builtInOff;
    }

    /** The devices connected at the start: best rank first, then by id */
    void start(List<Pad> list) {
        List<Pad> l = new ArrayList<>(list);
        l.sort(Comparator.<Pad>comparingInt(p -> rank(p, chosen, second)).thenComparingInt(p -> p.id));
        for (Pad p : l) connected(p);
        placeSecond();
    }

    /** A gamepad-like device connected; true when it has a player now */
    boolean connected(Pad p) {
        if (pads.containsKey(p.id) && playing.contains(p.id)) return true;
        pads.put(p.id, p);
        return !blocked(p, builtInOff) && atConnect(p, chosen, second) && join(p, false);
    }

    void removed(int id) { pads.remove(id); playing.remove(id); }

    /** Whether the device's events go to the host: it has a player */
    boolean plays(int id) { return playing.contains(id); }

    /** Input from a gamepad-like device (a pad button, or wakes()): it gets a player if it had none. True when it has one */
    boolean active(Pad p) {
        if (playing.contains(p.id)) return true;
        pads.put(p.id, p);
        return !blocked(p, builtInOff) && join(p, true);
    }

    /** JOTANATIVE: whether the device's input is held back: it is built in and the handheld's own controls are off */
    boolean blocked(int id) { Pad p = pads.get(id); return p != null && blocked(p, builtInOff); }

    private boolean join(Pad p, boolean byInput) {
        if (host.add(p) < 0) return false;
        playing.add(p.id);
        takeFirst(p, byInput);
        placeSecond();
        return true;
    }

    /** Player 1 for p when it is free or its device ranks worse; joining by its input, also when player 1's device is no controller (BUILT_IN or OTHER) */
    private void takeFirst(Pad p, boolean byInput) {
        if (host.player(p.id) == 0) return;
        Pad one = holderOfFirst();
        int r1 = one == null ? Integer.MAX_VALUE : rank(one, chosen, second), r = rank(p, chosen, second);
        if (r1 > r || byInput && r1 >= BUILT_IN && r1 == r) host.first(p);
    }

    private Pad holderOfFirst() { return holderOf(0); }

    private Pad holderOf(int player) {
        for (int id : playing) if (host.player(id) == player) return pads.get(id);
        return null;
    }

    /**
     * JOTANATIVE: the Player 2 choice onto player 2. The host only moves a device to player 1 (Host.first: player 1's device takes its old player), so three such
     * moves do it: player 2's device to 1 (player 1's device to 2), the chosen one to 1 (player 2's old device to its old player), player 1's device back to 1 (the
     * chosen one to 2). With nobody on player 2 yet nothing moves: the chosen one gets it when it joins, as the lowest free player after player 1
     */
    private void placeSecond() {
        if (second.isEmpty()) return;
        Pad s = null;
        for (int id : playing) { Pad p = pads.get(id); if (p != null && second.equals(p.key)) s = p; }
        if (s == null) return;
        int at = host.player(s.id);
        if (at <= 1) return;   // already player 2, or player 1 (the only device, or chosen as player 1 too)
        Pad one = holderOf(0), two = holderOf(1);
        if (one == null || two == null) return;
        host.first(two); host.first(s); host.first(one);
    }

    /** The gamepad-like devices connected, for the Player 1 choice */
    List<Pad> known() { return new ArrayList<>(pads.values()); }

    /** The device on player 1, null for none */
    Pad first() { return holderOfFirst(); }

    /** JOTANATIVE: the device on player 2, null for none */
    Pad two() { return holderOf(1); }

    /** JOTANATIVE: the Player 2 choice: a descriptor ("" = automatic). The chosen device, if it has a player, moves to player 2 */
    void chooseSecond(String key) {
        second = key == null ? "" : key;
        Pad s = null;
        for (Pad p : pads.values()) if (!second.isEmpty() && second.equals(p.key)) s = p;
        if (s != null && !playing.contains(s.id) && !blocked(s, builtInOff)) join(s, false); else placeSecond();
    }

    /** JOTANATIVE: the handheld's own controls on or off. Off: a built-in device gives back its player, and player 1 goes to the best device left */
    void builtInOff(boolean off) {
        builtInOff = off;
        if (!off) return;
        for (Integer id : new ArrayList<>(playing)) {
            Pad p = pads.get(id);
            if (p != null && blocked(p, true)) { playing.remove(id); host.remove(p); }
        }
        choose(chosen);
    }

    /** The Player 1 choice: a descriptor ("" = automatic). The chosen device, if it is connected, becomes player 1; automatic gives it to the best-ranked one */
    void choose(String key) {
        chosen = key == null ? "" : key;
        Pad best = null;
        for (Pad p : pads.values()) {
            if (blocked(p, builtInOff) || !playing.contains(p.id) && !atConnect(p, chosen, second)) continue;   // JOTANATIVE: never a blocked device
            if (best == null || rank(p, chosen, second) < rank(best, chosen, second)) best = p;
        }
        if (best == null) return;
        if (!playing.contains(best.id)) join(best, false); else { takeFirst(best, false); placeSecond(); }
    }
}
