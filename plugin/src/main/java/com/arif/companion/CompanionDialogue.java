package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dialogue of Arif's newer companions: Furina, Ei, Columbina, and Escoffier.
 *
 * <p>Each of them speaks in her own voice, in her own colour, and with her own lines. The
 * categories mirror the ones the other companions use:</p>
 * <ul>
 *   <li><b>on</b> — the companion was just called and answers.</li>
 *   <li><b>idle</b> — nothing is going on, and she says something of her own.</li>
 *   <li><b>kill</b> — a mob she struck went down.</li>
 *   <li><b>assist</b> — she just used her support ability.</li>
 *   <li><b>follow-up</b> — the fight is over; she adds one last remark.</li>
 *   <li><b>deny</b> — somebody who is not Arif tried to call her.</li>
 *   <li><b>off</b> — she was told to wait where she stands.</li>
 * </ul>
 *
 * <p>Every line belongs to a sound event in the resource pack, named after the category and its
 * number: {@code furina.idle_01} for the first idle line, {@code furina.on} for the single
 * calling line. The voice files themselves are still being rendered, so the sound is played
 * only when the client actually has it — a missing sound is simply ignored by the game.</p>
 */
final class CompanionDialogue {

    /** How long the whole group stays quiet after one line, so they do not talk over each other. */
    private static final long GLOBAL_COOLDOWN_TICKS = 100L;
    /** How long before a companion says something new while nothing is happening. */
    private static final long IDLE_COOLDOWN_TICKS = 1200L;
    /** How long before a companion may announce another kill. */
    private static final long KILL_COOLDOWN_TICKS = 200L;
    /** How long before a companion may announce another support trigger. */
    private static final long SUPPORT_COOLDOWN_TICKS = 400L;
    /** How long the follow-up line stays open after the support trigger. */
    private static final long FOLLOWUP_COOLDOWN_TICKS = 600L;

    /** Volume of a companion's own voice. */
    private static final float VOICE_VOLUME = 1.0f;

    /** The namespace the resource pack's sounds.json uses for the companion voices. */
    private static final String SOUND_NAMESPACE = "companion";

    /** One companion's lines. */
    private record Voice(
            String archetype,
            String label,
            NamedTextColor color,
            List<String> on,
            List<String> idle,
            List<String> kill,
            List<String> assist,
            List<String> followup,
            String deny,
            String off) {
    }

    private static final Voice FURINA = new Voice("furina", "Furina", NamedTextColor.AQUA,
            List.of("Kau memanggilku? Aku sudah siap-siap dari tadi!"),
            List.of(
                    "Arif-kun, kalau cuma berdiri di situ, penontonnya kabur!",
                    "Furina sedang menunggu, lho. Cepat!",
                    "Sudah waktunya tampil, Arif-kun!",
                    "Jangan lengah, panggung bisa berubah sewaktu-waktu.",
                    "Arif-kun, istirahat sebentar, lalu melangkah!",
                    "Aku punya firasat bagus hari ini!",
                    "Berdiri di sini saja... membosankan sekali.",
                    "Matahari belum terbenam. Masih ada waktu!"),
            List.of(
                    "Lihat itu! Adegan akhir yang brilian!",
                    "Hah! Bukan penonton yang menangis, tapi musuhnya!"),
            List.of(
                    "Tahan, Arif-kun! Aku datang!",
                    "Panggung ini milik kita!"),
            List.of("Jangan salah paham, aku cuma nggak mau pementasan ini gagal!"),
            "Bukan Arif-kun? Maaf, panggung ini hanya untuknya.",
            "Baiklah, aku menunggu di sini. Panggil aku saat sudah siap.");

    private static final Voice EI = new Voice("ei", "Ei", NamedTextColor.GOLD,
            List.of("Aku di sini. Katakan apa yang perlu."),
            List.of(
                    "Arif-kun, berhentilah sejenak. Ketenangan itu penting.",
                    "Bilang saja kalau ada yang mengganggu.",
                    "Aku menunggumu. Jangan buang waktu.",
                    "Pedang ini belum berkarat.",
                    "Kau lebih tangguh dari sebelumnya, Arif-kun.",
                    "Jangan tergesa. Kekal tidak pernah terburu-buru.",
                    "Angin ini... mengingatkanku pada sesuatu.",
                    "Berdiri di dekatku. Aku akan melindungimu."),
            List.of(
                    "Musuh telah tumbang. Begitulah seharusnya.",
                    "Layak. Kau layak berdiri di sisiku."),
            List.of(
                    "Arif-kun, mundur! Aku yang menghadapinya!",
                    "Tidak ada yang akan menyentuhmu."),
            List.of("Jangan salah paham, hanya karena kau berguna sedikit."),
            "Hanya Arif-kun yang boleh memerintahku.",
            "Aku menunggu. Tidak ada yang perlu diulang.");

    private static final Voice COLUMBINA = new Voice("columbina", "Columbina",
            NamedTextColor.LIGHT_PURPLE,
            List.of("Ya, ya... aku mendengarmu, Arif-kun."),
            List.of(
                    "Arif-kun... sudah waktunya istirahat, lho.",
                    "Aku ingin menyanyi. Dengarkan, ya.",
                    "Dunia ini berisik sekali, ya.",
                    "Jangan bergerak tiba-tiba... aku kaget.",
                    "Arif-kun, tanganmu dingin.",
                    "Senyummu lebih menarik daripada pertempuran.",
                    "Ssst... ada yang mendengarkan, lho.",
                    "Aku menemani sampai kau tertidur."),
            List.of(
                    "Ahaha... sudah selesai?",
                    "Aku tidak suka darah... tapi ya sudahlah."),
            List.of(
                    "Arif-kun, jangan khawatir. Ada aku.",
                    "Pergilah... kalian hanya mengganggu nyanyian kami."),
            List.of("Aku hanya ingin melindungi suaramu."),
            "Maaf... hanya Arif-kun yang boleh mendekat.",
            "Baik... aku akan menunggu di sini, sendirian.");

    private static final Voice ESCOFFIER = new Voice("escoffier", "Escoffier", NamedTextColor.GRAY,
            List.of("Ada yang perlu dicicipi, Arif-kun?"),
            List.of(
                    "Arif-kun, berdiri diam saja? Menu hari ini belum jadi.",
                    "Bahan terbaik butuh kesabaran.",
                    "Aroma ini... menarik.",
                    "Jangan sentuh apa pun sebelum cuci tangan.",
                    "Kau tampak lelah. Duduklah sebentar.",
                    "Masakan buruk lebih menakutkan daripada naga.",
                    "Struktur yang rapi... seperti dapur yang baik.",
                    "Nanti aku buatkan sesuatu untukmu, Arif-kun."),
            List.of(
                    "Berisik sekali... pengganggu di dapur.",
                    "Cepat dan bersih. Itu seni."),
            List.of(
                    "Arif-kun, minggir! Pisau ini untukku!",
                    "Aku tidak membiarkan tamuku terluka."),
            List.of("Hanya karena kau termasuk dalam resep hari ini."),
            "Maaf, tamu malam ini hanya Arif-kun.",
            "Baik, aku tunggu. Jangan biarkan makananku menguap.");

    /** Every companion that has a voice, keyed by archetype. */
    private static Voice voiceOf(String archetype) {
        if (archetype == null) {
            return null;
        }
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "furina" -> FURINA;
            case "ei" -> EI;
            case "columbina" -> COLUMBINA;
            case "escoffier" -> ESCOFFIER;
            default -> null;
        };
    }

    /** Whether this archetype has a voice of its own. */
    static boolean hasVoice(String archetype) {
        return voiceOf(archetype) != null;
    }

    /** A line that was spoken, and the sound event that belongs to it. */
    private record Spoken(String line, String sound) {
    }

    /** One companion's dialogue bookkeeping, per owner. */
    private static final class State {
        private long lastDialogueTick = Long.MIN_VALUE;
        private long lastIdleTick = Long.MIN_VALUE;
        private long lastKillTick = Long.MIN_VALUE;
        private long lastSupportTick = Long.MIN_VALUE;
        private long lastFollowupTick = Long.MIN_VALUE;
        private long supportSequenceTick = Long.MIN_VALUE;
        private String lastIdleLine;
        private String lastKillLine;
        private String lastSupportLine;
        private String lastFollowupLine;
    }

    private final Map<String, State> states = new HashMap<>();

    private static String key(Player owner, String archetype) {
        return owner.getUniqueId() + ":" + archetype;
    }

    private State state(Player owner, String archetype) {
        return states.computeIfAbsent(key(owner, archetype), k -> new State());
    }

    /**
     * The sound event of a line.
     *
     * <p>A category with a single line has one sound: {@code ei.on}. A category with several
     * lines has one sound per line, numbered: {@code furina.idle_01} up to
     * {@code furina.idle_08}. The name is namespaced with {@code companion}, which is the
     * namespace the resource pack's {@code sounds.json} uses.</p>
     */
    private static String soundOf(Voice voice, String category, int index, int total) {
        if (total <= 1) {
            return SOUND_NAMESPACE + ":" + voice.archetype() + "." + category;
        }
        return SOUND_NAMESPACE + ":" + voice.archetype() + "." + category
                + "_" + String.format(Locale.ROOT, "%02d", index + 1);
    }

    /**
     * Speaks a line of the given category.
     *
     * <p>Both the category's own cooldown and the shared anti-spam cooldown have to pass. When
     * the category holds more than one line, the previous one is avoided so the same sentence
     * does not come back twice in a row.</p>
     *
     * @return the line that was spoken, or null when a cooldown blocked it
     */
    private Spoken speak(Player owner, Voice voice, String category, List<String> lines,
                         String previous, long categoryCooldown, long lastCategoryTick) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        if (lastCategoryTick != Long.MIN_VALUE && now - lastCategoryTick < categoryCooldown) {
            return null;
        }
        State state = state(owner, voice.archetype());
        if (state.lastDialogueTick != Long.MIN_VALUE
                && now - state.lastDialogueTick < GLOBAL_COOLDOWN_TICKS) {
            return null;
        }
        int index = (int) (Math.random() * lines.size());
        if (lines.size() > 1 && lines.get(index).equals(previous)) {
            index = (index + 1) % lines.size();
        }
        String selected = lines.get(index);
        state.lastDialogueTick = now;
        owner.sendMessage(Component.text("[" + voice.label + "] " + selected, voice.color));
        return new Spoken(selected, soundOf(voice, category, index, lines.size()));
    }

    /** Plays the companion's voice where it stands, for its owner only. */
    private static void playVoice(Player owner, Location at, String sound) {
        if (sound == null || at == null) {
            return;
        }
        try {
            owner.playSound(at, sound, SoundCategory.PLAYERS, VOICE_VOLUME, 1.0f);
        } catch (RuntimeException ignored) {
            // A missing sound event is not worth interrupting the game for: the client simply
            // ignores it, and the text line has already been shown.
        }
    }

    /** The companion was called: she answers. */
    void onCall(Player owner, String archetype, Location at) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        Spoken spoken = speak(owner, voice, "on", voice.on, null, 0L, Long.MIN_VALUE);
        if (spoken != null) {
            playVoice(owner, at, spoken.sound());
        }
    }

    /** The companion was told to wait where she stands. */
    void onWait(Player owner, String archetype, Location at) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        Spoken spoken = speak(owner, voice, "off", List.of(voice.off), null, 0L, Long.MIN_VALUE);
        if (spoken != null) {
            playVoice(owner, at, spoken.sound());
        }
    }

    /** Somebody who is not Arif tried to call her. */
    void onDeny(Player player, String archetype) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        player.sendMessage(Component.text("[" + voice.label + "] " + voice.deny, voice.color));
    }

    /**
     * A tick of idle life: when nothing is going on for a while, she says something.
     *
     * @param idle whether the companion has nothing to do right now
     */
    void tick(Player owner, String archetype, Location at, boolean idle, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null || !idle) {
            return;
        }
        State state = state(owner, archetype);
        if (state.lastIdleTick != Long.MIN_VALUE
                && currentTick - state.lastIdleTick < IDLE_COOLDOWN_TICKS) {
            return;
        }
        Spoken spoken = speak(owner, voice, "idle", voice.idle, state.lastIdleLine,
                IDLE_COOLDOWN_TICKS, state.lastIdleTick);
        if (spoken != null) {
            state.lastIdleTick = currentTick;
            state.lastIdleLine = spoken.line();
            playVoice(owner, at, spoken.sound());
        }
    }

    /**
     * The companion just used her support ability.
     *
     * <p>The follow-up line follows shortly after the fight is over, the way a companion says
     * one last thing once the danger has passed.</p>
     */
    void onSupport(Player owner, String archetype, Location at, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        State state = state(owner, archetype);
        Spoken spoken = speak(owner, voice, "assist", voice.assist, state.lastSupportLine,
                SUPPORT_COOLDOWN_TICKS, state.lastSupportTick);
        if (spoken != null) {
            state.lastSupportTick = currentTick;
            state.lastSupportLine = spoken.line();
            state.supportSequenceTick = currentTick;
            playVoice(owner, at, spoken.sound());
        }
    }

    /**
     * The fight is over: the companion adds one last remark, if she said something during the
     * fight in the first place.
     */
    void onFollowUp(Player owner, String archetype, Location at, boolean fighting, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null || fighting) {
            return;
        }
        State state = state(owner, archetype);
        if (state.supportSequenceTick == Long.MIN_VALUE
                || currentTick - state.supportSequenceTick > FOLLOWUP_COOLDOWN_TICKS) {
            return;
        }
        Spoken spoken = speak(owner, voice, "followup", voice.followup, state.lastFollowupLine,
                FOLLOWUP_COOLDOWN_TICKS, state.lastFollowupTick);
        if (spoken != null) {
            state.lastFollowupTick = currentTick;
            state.lastFollowupLine = spoken.line();
            state.supportSequenceTick = Long.MIN_VALUE;
            playVoice(owner, at, spoken.sound());
        }
    }

    /** The companion struck a mob that died. */
    void onKill(Player owner, String archetype, Location at, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        State state = state(owner, archetype);
        Spoken spoken = speak(owner, voice, "kill", voice.kill, state.lastKillLine,
                KILL_COOLDOWN_TICKS, state.lastKillTick);
        if (spoken != null) {
            state.lastKillTick = currentTick;
            state.lastKillLine = spoken.line();
            playVoice(owner, at, spoken.sound());
        }
    }

    /** Forgets every owner's dialogue state, so a fresh session starts clean. */
    void clear() {
        states.clear();
    }
}
