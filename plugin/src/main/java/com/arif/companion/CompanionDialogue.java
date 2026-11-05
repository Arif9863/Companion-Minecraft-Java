package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The dialogue of Arif's six companions: Yui, Sandrone, Furina, Ei, Columbina and Escoffier.
 *
 * <p>Each of them speaks in her own voice, in her own colour, and with her own lines. The
 * categories mirror the ones the other companions use:</p>
 * <ul>
 *   <li><b>on</b> — the companion was just called and answers.</li>
 *   <li><b>idle</b> — nothing is going on, and she says something of her own.</li>
 *   <li><b>kill</b> — a mob she struck went down.</li>
 *   <li><b>assist</b> — she just used her support ability: heals, absorption, buffs.</li>
 *   <li><b>follow-up</b> — the fight is over; she adds one last remark.</li>
 *   <li><b>deny</b> — somebody who is not Arif tried to call her.</li>
 *   <li><b>off</b> — she was told to wait where she stands.</li>
 * </ul>
 *
 * <p>Three categories take turns on one channel, by priority, through
 * {@link CompanionVoiceArbiter}: assist and follow-up are first, kill second, idle last. A line
 * may cut off a lower one that is still playing; an equal or lower one is turned away. The
 * remaining three — <b>on</b>, <b>deny</b> and <b>off</b> (tunggu) — sit outside the queue:
 * they play at any time, they never cut anything off, and they may overlap with each other.</p>
 *
 * <p>Idle is shared rather than per companion. All six take their turn in the fixed order
 * Escoffier, Sandrone, Yui, Furina, Columbina, Ei, handing out one line of the same number in
 * each turn — so the group works through idle&nbsp;1 in that order, then idle&nbsp;2, and so on
 * up to whichever companion has the most lines, before starting the whole rotation again.</p>
 *
 * <p>Every line belongs to a sound event in the resource pack, named after the category and its
 * number: {@code furina.idle_01} for the first idle line, {@code furina.on} for the single
 * calling line. Yui and Sandrone keep the names their own script already used
 * ({@code yui.heal} for Yui's healing, {@code sandrone.regen} for Sandrone's absorption). The
 * voice files themselves are still being rendered, so the sound is played only when the client
 * actually has it — a missing sound is simply ignored by the game.</p>
 */
final class CompanionDialogue {

    /** How long before the group may speak again, so they do not talk over each other. */
    private static final long SPEECH_COOLDOWN_TICKS = 160L;
    /** How long before the idle rotation hands out its next line. */
    private static final long IDLE_TURN_TICKS = 1200L;
    /** How long before a companion may announce another kill. */
    private static final long KILL_COOLDOWN_TICKS = 200L;
    /** How long before a companion may announce another support trigger. */
    private static final long SUPPORT_COOLDOWN_TICKS = 400L;
    /** How long the follow-up line stays open after the support trigger. */
    private static final long FOLLOWUP_COOLDOWN_TICKS = 600L;

    /** How long a support or kill line claims the channel, in ticks. */
    private static final long ASSIST_HOLD_TICKS = 80L;
    /** How long an idle line claims the channel, in ticks. */
    private static final long IDLE_HOLD_TICKS = 100L;

    /** How far from the owner a companion may stand and still be heard. */
    private static final double VOICE_RADIUS = 256.0;

    /** Volume of a companion's own voice. */
    private static final float VOICE_VOLUME = 1.0f;

    /** The namespace the resource pack's sounds.json uses for the companion voices. */
    private static final String SOUND_NAMESPACE = "companion";

    /**
     * The order the six companions take their turn in for idle speech.
     *
     * <p>Escoffier opens, then Sandrone, Yui, Furina, Columbina and Ei. A character that is not
     * out right now, or is busy fighting, is stepped over and the next one takes the line, so
     * being absent never holds up the rotation.</p>
     */
    static final List<String> IDLE_ROTATION =
            List.of("escoffier", "sandrone", "yui", "furina", "columbina", "ei");

    /**
     * Looks up where a companion stands when she is out and has nothing to do.
     *
     * <p>Answers null when she is missing, busy or away, which is the caller's way of saying
     * that this turn does not belong to her.</p>
     */
    @FunctionalInterface
    interface IdleBody {
        /**
         * @param archetype which companion is being asked about
         * @return where she stands right now, or null when she cannot speak
         */
        Location of(String archetype);
    }

    /**
     * One companion's lines.
     *
     * @param assistSound the sound event of the support category, when it does not follow the
     *                    usual companion naming — Yui heals and Sandrone absorbs
     */
    record Voice(
            String archetype,
            String label,
            NamedTextColor color,
            List<String> on,
            List<String> idle,
            List<String> kill,
            List<String> assist,
            List<String> followup,
            String deny,
            String off,
            String assistSound) {

        /** A companion whose support sound is named after her archetype and category. */
        Voice(String archetype, String label, NamedTextColor color, List<String> on,
              List<String> idle, List<String> kill, List<String> assist, List<String> followup,
              String deny, String off) {
            this(archetype, label, color, on, idle, kill, assist, followup, deny, off, "assist");
        }
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

    /** Yui, who mends her owner when they get hurt. */
    private static final Voice YUI = new Voice("yui", "Yui", NamedTextColor.LIGHT_PURPLE,
            List.of("Asisten Yui siap membantu Arif....."),
            List.of(
                    "Arif, jangan lupa periksa jumlah amunisi mu....",
                    "Arif.....Yui siap membantumu kapan pun dan dimana pun....",
                    "Aku sudah menunggumu! Ayo, kita pergi bersama!",
                    "Tak perlu terburu-buru. Mari nikmati pemandangannya pelan-pelan!",
                    "Perjalanan itu bukan cuma soal tujuan, proses di tengah jalan juga penting!",
                    "Kalau capek bilang saja. Kita bisa istirahat kapan saja!",
                    "Kalau Arif menikmatinya, itu sudah jadi imbalan terbaik bagiku!",
                    "Kau sedang murung? Kalau aku bisa, cerita kan saja padaku!"),
            List.of(
                    "Hebat sekali Arif, nanti ajarin Yui ya....",
                    "Arif.....jangan lupa jaga dirimu ya...."),
            List.of("Sayang kamu terluka, biar Yui merawatmu...."),
            List.of("Perhatikan kondisi fisikmu, Yui khawatir sekali"),
            "Hati dan kasih Yui hanya untuk Arif",
            "Hubungi Yui Kapan saja ya... Arif......",
            "yui.heal");

    /** Sandrone, who takes the damage in her owner's place. */
    private static final Voice SANDRONE = new Voice("sandrone", "Sandrone", NamedTextColor.AQUA,
            List.of("Sandrone hanya membantu kali ini saja Arif...."),
            List.of(
                    "Sudah lama berjalan, Arif. Tetap waspada....",
                    "Arif, Sandrone masih terus melihatmu....",
                    "Lambat, Arif. Jangan membuatku menunggu....",
                    "Wajahmu mengantuk, Arif. Kau istirahat dengan benar?",
                    "Setidaknya tepatilah waktu janji....",
                    "Mengulang kesalahan yang sama dua kali itu perbuatan orang bodoh."),
            List.of(
                    "Bagus, Arif. Pertahankan ritmenya seperti yang Aku ajarkan....",
                    "Kalau cuma begitu, Sandrone pun bisa....",
                    "Kau tetap saja berisik, ya....",
                    "Aku tidak pernah bilang aku terkesan.",
                    "Lumayan. ...Tentu saja tidak sebagus aku.",
                    "Bakatmu kuakui. Tapi usahamu kurang."),
            List.of("Biar nona Sandrone yang melindungimu hmmpphh..."),
            List.of("Jangan salah paham, aku hanya membantumu kali ini ya Arif..."),
            "Cihhh...saya hanya membantu Arif saja!!!",
            "Jangan ceroboh loh....bilang aja kalau butuh bantuan Sandrone",
            "sandrone.regen");

    /** Every companion that has a voice, keyed by archetype. */
    static Voice voiceOf(String archetype) {
        if (archetype == null) {
            return null;
        }
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "yui" -> YUI;
            case "sandrone" -> SANDRONE;
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

    /** A line that was chosen, with the sound event that belongs to it. */
    private record Spoken(String line, String sound, int index) {
    }

    /** One companion's dialogue bookkeeping, per owner. */
    private static final class State {
        private long lastKillTick = Long.MIN_VALUE;
        private long lastSupportTick = Long.MIN_VALUE;
        private long lastFollowupTick = Long.MIN_VALUE;
        private long supportSequenceTick = Long.MIN_VALUE;
        private String lastKillLine;
        private String lastSupportLine;
        private String lastFollowupLine;
    }

    /** The shared idle turn-taking of one owner. */
    private static final class Idle {
        /** Index into {@link #IDLE_ROTATION}: whose turn it is. */
        private int slot;
        /** Which line number of the series is being handed out in this round. */
        private int line;
        /** Next tick at which anybody may take a line. */
        private long nextTurnTick = Long.MIN_VALUE;
    }

    /** Owner kill counter for DuoVoice-style kill lines (Yui / Sandrone). */
    private static final class OwnerKill {
        private int count;
    }

    private final Map<String, State> states = new HashMap<>();
    private final Map<UUID, Idle> idles = new HashMap<>();
    private final Map<UUID, OwnerKill> ownerKills = new HashMap<>();
    /** The channel the three interruptible categories queue up on. */
    private final CompanionVoiceArbiter arbiter = new CompanionVoiceArbiter();

    private static String key(Player owner, String archetype) {
        return owner.getUniqueId() + ":" + archetype.toLowerCase(Locale.ROOT);
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
    private static String soundOf(String archetype, String category, int index, int total) {
        if (total <= 1) {
            return SOUND_NAMESPACE + ":" + archetype + "." + category;
        }
        return SOUND_NAMESPACE + ":" + archetype + "." + category
                + "_" + String.format(Locale.ROOT, "%02d", index + 1);
    }

    /** Sound key for a line; assist may use a custom key ({@code yui.heal}, {@code sandrone.regen}). */
    private static String soundFor(Voice voice, String category, int index, int total) {
        if ("assist".equals(category) && voice.assistSound() != null
                && !"assist".equals(voice.assistSound())) {
            return SOUND_NAMESPACE + ":" + voice.assistSound();
        }
        return soundOf(voice.archetype(), category, index, total);
    }

    /**
     * Nearest mannequin of this archetype within {@link #VOICE_RADIUS}, or null when she is not
     * out — voice must not fall back to the owner's position.
     */
    static Location voiceSphere(Player owner, String archetype) {
        if (owner == null || archetype == null) {
            return null;
        }
        String tag = "companion_" + archetype.toLowerCase(Locale.ROOT);
        Entity nearest = null;
        double best = VOICE_RADIUS * VOICE_RADIUS;
        for (Entity entity : owner.getNearbyEntities(VOICE_RADIUS, VOICE_RADIUS, VOICE_RADIUS)) {
            if (!(entity instanceof Mannequin mannequin) || !mannequin.getScoreboardTags().contains(tag)) {
                continue;
            }
            double dist = entity.getLocation().distanceSquared(owner.getLocation());
            if (dist <= best) {
                best = dist;
                nearest = entity;
            }
        }
        return nearest == null ? null : nearest.getLocation().clone();
    }

    /**
     * Chooses the line to use for a category, avoiding an immediate repeat when it is picked
     * at random, and building the sound event that goes with it.
     *
     * @param lineIndex a caller that already knows which line it needs passes it, a rotation
     *                  passing its fixed turn; -1 means pick one at random
     */
    private static Spoken pick(Voice voice, String category, List<String> lines,
                               int lineIndex, String previous) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int index = lineIndex;
        if (index < 0 || index >= lines.size()) {
            index = (int) (Math.random() * lines.size());
            if (lines.size() > 1 && lines.get(index).equals(previous)) {
                index = (index + 1) % lines.size();
            }
        }
        return new Spoken(lines.get(index),
                soundFor(voice, category, index, lines.size()), index);
    }

    /** Sends a line in the companion's own voice. */
    private static void send(Player target, Voice voice, String line) {
        target.sendMessage(Component.text(
                "[" + voice.label() + "] " + line, voice.color()));
    }

    /** Plays a sound event at a position, for one player. */
    private static void play(Player target, String sound, Location at) {
        if (sound == null || at == null) {
            return;
        }
        try {
            target.playSound(at, sound, SoundCategory.PLAYERS, VOICE_VOLUME, 1.0f);
        } catch (RuntimeException ignored) {
            // A missing sound event is not worth interrupting the game for: the client simply
            // ignores it, and the text line has already been shown.
        }
    }

    /** One line, ready to be spoken: chat, sound and where the sound stands. */
    private Runnable utter(Player owner, Voice voice, Spoken chosen, Location at) {
        return () -> {
            send(owner, voice, chosen.line());
            play(owner, chosen.sound(), at);
        };
    }

    /**
     * Speaks one line by priority, if it is allowed to.
     *
     * @return the line spoken, or null when blocked
     */
    private Spoken speak(Player owner, Voice voice, String category, List<String> lines,
                         String previous, long categoryCooldown, long lastCategoryTick,
                         int categoryPriority, long holdTicks, Location at, int lineIndex) {
        if (owner == null || lines == null || lines.isEmpty()) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        if (lastCategoryTick != Long.MIN_VALUE && now - lastCategoryTick < categoryCooldown) {
            return null;
        }
        Spoken chosen = pick(voice, category, lines, lineIndex, previous);
        if (chosen == null) {
            return null;
        }
        Location playAt = at != null ? at : voiceSphere(owner, voice.archetype());
        boolean said = arbiter.trySpeak(owner, categoryPriority, holdTicks, chosen.sound(), () -> {
            send(owner, voice, chosen.line());
            if (playAt != null) {
                play(owner, chosen.sound(), playAt);
            }
        });
        return said ? chosen : null;
    }

    /** The companion was called: she answers, and the answer never joins a queue. */
    void onCall(Player owner, String archetype, Location at) {
        Voice voice = voiceOf(archetype);
        if (voice == null || at == null) {
            return;
        }
        Spoken chosen = pick(voice, "on", voice.on(), -1, null);
        if (chosen == null) {
            return;
        }
        arbiter.speakFree(utter(owner, voice, chosen, at));
    }

    /**
     * The companion was told to wait where she stands.
     *
     * <p>Like calling, this is the owner asking for an answer, so it plays at once and does not
     * cut anything else off.</p>
     */
    void onWait(Player owner, String archetype, Location at) {
        Voice voice = voiceOf(archetype);
        if (voice == null || at == null) {
            return;
        }
        Spoken chosen = pick(voice, "off", List.of(voice.off()), -1, null);
        if (chosen == null) {
            return;
        }
        arbiter.speakFree(utter(owner, voice, chosen, at));
    }

    /**
     * Somebody who is not Arif tried to call her.
     *
     * <p>The refusal is played at the hearing player's own position and outside the queue: what
     * they need is the message itself, and it must not wait behind anybody's idle line.</p>
     */
    void onDeny(Player player, String archetype) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        Spoken chosen = new Spoken(voice.deny(), SOUND_NAMESPACE + ":" + voice.archetype() + ".deny", 0);
        arbiter.speakFree(() -> {
            send(player, voice, chosen.line());
            play(player, chosen.sound(), player.getLocation());
        });
    }

    /**
     * Hands out the next idle line of the owner's shared rotation.
     *
     * <p>The whole group is asked to step forward at once, once a turn is due, so this is idempotent
     * within a tick: only the first of the owner's companions actually looks for a speaker.</p>
     *
     * @param currentTick the tick the companion work is running on
     * @param body        where each companion stands when she is out and has nothing to do
     */
    void playIdleTurn(Player owner, long currentTick, IdleBody body) {
        if (owner == null || !owner.isOnline() || body == null) {
            return;
        }
        Idle idle = idles.computeIfAbsent(owner.getUniqueId(), ignored -> new Idle());
        if (idle.nextTurnTick == Long.MIN_VALUE) {
            // The first turn is postponed rather than handed out at once, so the group does not
            // all talk on the tick they were summoned.
            idle.nextTurnTick = currentTick + IDLE_TURN_TICKS;
            return;
        }
        if (currentTick < idle.nextTurnTick) {
            return;
        }

        for (int step = 0; step < IDLE_ROTATION.size(); step++) {
            String archetype = IDLE_ROTATION.get(idle.slot);
            Voice voice = voiceOf(archetype);
            Location at = voice != null && idle.line < voice.idle().size()
                    ? body.of(archetype)
                    : null;
            if (at == null || voice == null || !hasActiveLine(owner, archetype)) {
                // Somebody is out, or busy: the turn goes to the next companion in the order
                // without waiting for them to reappear.
                advance(idle);
                continue;
            }
            Spoken chosen = pick(voice, "idle", voice.idle(), idle.line, null);
            if (chosen == null) {
                advance(idle);
                continue;
            }
            if (!arbiter.trySpeak(owner, CompanionVoiceArbiter.PRIORITY_IDLE, IDLE_HOLD_TICKS,
                    chosen.sound(), utter(owner, voice, chosen, at))) {
                // The channel is taken by a higher line. The turn is not spent: hand it out
                // again as soon as there is room, so the rotation keeps its order.
                idle.nextTurnTick = currentTick + ASSIST_HOLD_TICKS;
                return;
            }
            advance(idle);
            idle.nextTurnTick = currentTick + IDLE_TURN_TICKS;
            return;
        }
        // Nobody in the order had anything to say this round.
        idle.nextTurnTick = currentTick + IDLE_TURN_TICKS;
    }

    /** Takes one step around the order, moving on to the next line number after the last. */
    private static void advance(Idle idle) {
        idle.slot++;
        if (idle.slot >= IDLE_ROTATION.size()) {
            idle.slot = 0;
            // Idle 1 is done for all six; start on idle 2, and wrap back to the first once the
            // companion with the most lines has said hers.
            Voice longest = voiceOf(IDLE_ROTATION.get(0));
            int mostLines = 0;
            for (String archetype : IDLE_ROTATION) {
                Voice voice = voiceOf(archetype);
                mostLines = Math.max(mostLines, voice == null ? 0 : voice.idle().size());
            }
            idle.line = (idle.line + 1) % Math.max(1, mostLines);
            if (longest == null) {
                idle.line = 0;
            }
        }
    }

    /**
     * Whether the owner actually has this companion out right now.
     *
     * <p>The tag is written alongside spawning, so it is the same question the rest of the
     * plugin asks: is her mannequin on the world?</p>
     */
    private static boolean hasActiveLine(Player owner, String archetype) {
        return owner.getScoreboardTags().contains(CompanionManager.activeTag(archetype));
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
        Spoken chosen = speak(owner, voice, "assist", voice.assist(), state.lastSupportLine,
                SUPPORT_COOLDOWN_TICKS, state.lastSupportTick,
                CompanionVoiceArbiter.PRIORITY_ASSIST, ASSIST_HOLD_TICKS, at, -1);
        if (chosen != null) {
            state.lastSupportLine = chosen.line();
            state.lastSupportTick = currentTick;
            state.supportSequenceTick = currentTick;
        }
    }

    /**
     * The fight is over: the companion adds one last remark, if she said something during the
     * fight in the first place.
     */
    void onFollowUp(Player owner, String archetype, Location at, boolean fighting, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null || fighting || at == null) {
            return;
        }
        State state = state(owner, archetype);
        if (state.supportSequenceTick == Long.MIN_VALUE
                || currentTick - state.supportSequenceTick > FOLLOWUP_COOLDOWN_TICKS) {
            return;
        }
        Spoken chosen = speak(owner, voice, "followup", voice.followup(), state.lastFollowupLine,
                FOLLOWUP_COOLDOWN_TICKS, state.lastFollowupTick,
                CompanionVoiceArbiter.PRIORITY_ASSIST, ASSIST_HOLD_TICKS, at, -1);
        if (chosen != null) {
            state.lastFollowupTick = currentTick;
            state.lastFollowupLine = chosen.line();
            state.supportSequenceTick = Long.MIN_VALUE;
            if (at != null) {
                play(owner, "entity.experience_orb.pickup", at);
            }
        }
    }

    /** The companion struck a mob that died. */
    void onKill(Player owner, String archetype, Location at, long currentTick) {
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        State state = state(owner, archetype);
        Spoken chosen = speak(owner, voice, "kill", voice.kill(), state.lastKillLine,
                KILL_COOLDOWN_TICKS, state.lastKillTick,
                CompanionVoiceArbiter.PRIORITY_KILL, ASSIST_HOLD_TICKS, at, -1);
        if (chosen != null) {
            state.lastKillTick = currentTick;
            state.lastKillLine = chosen.line();
        }
    }

    /**
     * Yui heal: chat and action bar always; heal sound only if the claim succeeds.
     */
    void announceYuiHeal(Player owner, Location at) {
        if (owner == null || !owner.isOnline()) {
            return;
        }
        owner.sendActionBar(Component.text(
                "Sayang kamu terluka, biar Yui merawatmu....", NamedTextColor.LIGHT_PURPLE));
        owner.sendMessage(Component.text(
                "[Yui] Arif.....mundur, biar Yui merawatmu", NamedTextColor.LIGHT_PURPLE));
        Location playAt = at != null ? at : voiceSphere(owner, "yui");
        String sound = SOUND_NAMESPACE + ":yui.heal";
        arbiter.trySpeak(owner, CompanionVoiceArbiter.PRIORITY_ASSIST, ASSIST_HOLD_TICKS, sound,
                () -> {
                    if (playAt != null) {
                        play(owner, sound, playAt);
                    }
                });
    }

    /**
     * Sandrone absorption voice: chat and action bar always; regen sound only when claimed.
     */
    void announceSandroneAbsorption(Player owner, Location at) {
        Voice voice = SANDRONE;
        if (owner == null || !owner.isOnline()) {
            return;
        }
        owner.sendActionBar(Component.text(
                "Biar nona Sandrone yang melindungimu hmmpphh...", NamedTextColor.AQUA));
        send(owner, voice, "Mundurlah Arif.....biar nona Sandrone yang menolongmu");
        Location playAt = at != null ? at : voiceSphere(owner, "sandrone");
        String sound = SOUND_NAMESPACE + ":sandrone.regen";
        arbiter.trySpeak(owner, CompanionVoiceArbiter.PRIORITY_ASSIST, ASSIST_HOLD_TICKS, sound,
                () -> {
                    if (playAt != null) {
                        play(owner, sound, playAt);
                    }
                });
    }

    /** Scheduled follow-up after Yui heal or Sandrone absorption (Skript prio idle → assist here). */
    void playScheduledFollowUp(Player owner, String archetype, Location at) {
        Voice voice = voiceOf(archetype);
        if (voice == null || voice.followup().isEmpty()) {
            return;
        }
        Spoken chosen = pick(voice, "followup", voice.followup(), -1, null);
        if (chosen == null) {
            return;
        }
        Location playAt = at != null ? at : voiceSphere(owner, archetype);
        arbiter.trySpeak(owner, CompanionVoiceArbiter.PRIORITY_ASSIST, IDLE_HOLD_TICKS,
                chosen.sound(), () -> {
                    send(owner, voice, chosen.line());
                    if (playAt != null) {
                        play(owner, chosen.sound(), playAt);
                        play(owner, "entity.experience_orb.pickup", playAt);
                    }
                });
    }

    /**
     * Counts the owner's monster kills toward a shared kill line (Yui or Sandrone at random).
     */
    void onOwnerKill(Player owner, long currentTick) {
        if (owner == null || !owner.isOnline()) {
            return;
        }
        boolean yui = owner.getScoreboardTags().contains(CompanionManager.activeTag("yui"));
        boolean sand = owner.getScoreboardTags().contains(CompanionManager.activeTag("sandrone"));
        if (!yui && !sand) {
            return;
        }
        OwnerKill counter = ownerKills.computeIfAbsent(owner.getUniqueId(), ignored -> new OwnerKill());
        counter.count++;
        if (counter.count < 3) {
            return;
        }
        counter.count = 0;
        String archetype;
        if (yui && sand) {
            archetype = Math.random() < 0.5 ? "yui" : "sandrone";
        } else {
            archetype = yui ? "yui" : "sandrone";
        }
        Voice voice = voiceOf(archetype);
        if (voice == null) {
            return;
        }
        State state = state(owner, archetype);
        Spoken chosen = speak(owner, voice, "kill", voice.kill(), state.lastKillLine,
                KILL_COOLDOWN_TICKS, state.lastKillTick,
                CompanionVoiceArbiter.PRIORITY_KILL, ASSIST_HOLD_TICKS, null, -1);
        if (chosen != null) {
            state.lastKillTick = currentTick;
            state.lastKillLine = chosen.line();
        }
    }

    /** Forgets every owner's dialogue state, so a fresh session starts clean. */
    void clear() {
        states.clear();
        idles.clear();
        ownerKills.clear();
        arbiter.clear();
    }

    void clear(Player owner) {
        if (owner == null) {
            return;
        }
        UUID id = owner.getUniqueId();
        states.keySet().removeIf(k -> k.startsWith(id + ":"));
        idles.remove(id);
        ownerKills.remove(id);
        arbiter.clear(id);
    }
}