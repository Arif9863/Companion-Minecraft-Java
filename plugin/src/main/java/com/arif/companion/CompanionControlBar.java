package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * The clickable control line for the companions.
 *
 * <p>The commands already exist — {@code /ikut}, {@code /kesini} and {@code /tunggu} — and this
 * line only puts them within reach: each label is a piece of text that runs the matching command
 * when it is clicked. Nothing here decides anything; the command handlers do the work, so the
 * click and the typed command cannot disagree.</p>
 *
 * <p>The line is sent once, right after the companions are called, and again from time to time
 * while the companions are out. It is not shown in the action bar: the action bar is a single
 * line above the hotbar that the companions already use for their own messages, and in Java
 * Edition its text cannot be clicked.</p>
 */
final class CompanionControlBar {

    /** How a clickable label looks: coloured, underlined, and it says what it does on hover. */
    private static final NamedTextColor LABEL_COLOR = NamedTextColor.AQUA;

    private CompanionControlBar() {
    }

    /**
     * The control line for Arif's own group of companions.
     *
     * @param target the companion to address, or {@code semua} for the whole group
     */
    static Component forGroup(String target) {
        return build(
                new Control("Ikut", "/ikut " + target, "Kembali ke formasi dan jarak normal."),
                new Control("Kesini", "/kesini " + target, "Mendekat ke Arif, untuk ruangan sempit."),
                new Control("Tunggu", "/tunggu " + target, "Berhenti di tempat dan menunggu."));
    }

    /**
     * The control line for Eris, who has her own commands.
     */
    static Component forEris() {
        return build(
                new Control("Ikut", "/eris ikut", "Kembali ke formasi dan jarak normal."),
                new Control("Kesini", "/eris kesini", "Mendekat ke Ferdi-Kun."),
                new Control("Tunggu", "/eris tunggu", "Berhenti di tempat dan menunggu."),
                new Control("Off", "/eris off", "Singkirkan Eris."));
    }

    /** Builds a control line out of its labels. */
    static Component build(Control... controls) {
        Component line = Component.empty();
        for (int index = 0; index < controls.length; index++) {
            if (index > 0) {
                line = line.append(Component.text(" ", NamedTextColor.GRAY));
            }
            line = line.append(label(controls[index]));
        }
        return line;
    }

    /** One clickable label. */
    record Control(String label, String command, String description) {
    }

    /** Builds one clickable label. */
    private static Component label(Control control) {
        return Component.text("[" + control.label() + "]")
                .color(LABEL_COLOR)
                .decorate(TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text(
                        control.description() + "  (" + control.command() + ")", NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand(control.command()));
    }

    /** Sends the control line to the player. */
    static void send(Player player, Component line) {
        if (player != null && line != null) {
            player.sendMessage(line);
        }
    }
}
