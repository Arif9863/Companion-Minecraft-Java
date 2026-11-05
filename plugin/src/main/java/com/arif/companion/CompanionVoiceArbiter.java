package com.arif.companion;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Arbitrates only the interruptible dialogue classes for one owner's six companions. */
final class CompanionVoiceArbiter {
    static final int PRIORITY_ASSIST = 1;
    static final int PRIORITY_KILL = 2;
    static final int PRIORITY_IDLE = 3;
    private static final long SPEECH_COOLDOWN_TICKS = 160L;

    private static final class VoiceState {
        private long busyUntilTick = Long.MIN_VALUE;
        private int priority;
        private String sound;
        private long lastSpeechTick = Long.MIN_VALUE;
    }

    private final Map<UUID, VoiceState> states = new HashMap<>();

    /** Runs chat/sound only if this priority can claim the owner's voice channel. */
    boolean trySpeak(Player owner, int priority, long holdTicks, String sound, Runnable output) {
        if (owner == null || !owner.isOnline() || output == null) {
            return false;
        }
        VoiceState state = states.computeIfAbsent(owner.getUniqueId(), ignored -> new VoiceState());
        long now = owner.getServer().getCurrentTick();
        if (state.lastSpeechTick != Long.MIN_VALUE
                && now - state.lastSpeechTick < SPEECH_COOLDOWN_TICKS) {
            return false;
        }
        if (state.busyUntilTick > now) {
            if (priority >= state.priority) {
                return false;
            }
            stopCurrent(owner, state);
        }
        state.priority = priority;
        state.busyUntilTick = now + Math.max(1L, holdTicks);
        state.sound = sound;
        state.lastSpeechTick = now;
        output.run();
        return true;
    }

    /** Plays an exempt event; it neither claims the channel nor interrupts another sound. */
    void speakFree(Runnable output) {
        if (output != null) {
            output.run();
        }
    }

    private static void stopCurrent(Player owner, VoiceState state) {
        if (state.sound != null) {
            try {
                owner.stopSound(state.sound, SoundCategory.PLAYERS);
            } catch (RuntimeException ignored) {
                // A missing sound event should not prevent the higher-priority dialogue.
            }
        }
        state.sound = null;
        state.busyUntilTick = Long.MIN_VALUE;
        state.priority = 0;
    }

    void clear(UUID ownerId) {
        states.remove(ownerId);
    }

    void clear() {
        states.clear();
    }
}
