package com.arif.companion;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds {@link ResolvableProfile} values for companion mannequins.
 *
 * <p>Minecraft resolves a player skin from two independent places, and 26.3 lets us use either one:</p>
 *
 * <ul>
 *   <li><b>Mojang URL</b> — an unsigned {@code textures} property on the profile pointing at a
 *       hosted PNG. This is how third-party servers usually do it. The client downloads the skin
 *       from {@code textures.minecraft.net}, so the file must be publicly reachable and the skin
 *       shows as "slim/classic" per the property payload.</li>
 *   <li><b>Resource pack patch</b> — a {@code skinPatch} whose {@code body} is a namespaced
 *       {@link Key}. The client resolves that key as a texture inside its loaded resource packs
 *       ({@code assets/<namespace>/textures/<path>.png}) via {@code TextureManager#getTexture},
 *       which lazily loads any pack path. This lets the skin ship inside our own pack, with no
 *       hosting and no external fetch.</li>
 * </ul>
 *
 * <p>Both are honoured at render time: the client fetches the profile skin, then overlays the patch
 * body texture when a patch is present. So a pack patch wins over the profile skin.</p>
 */
final class SkinFactory {

    private SkinFactory() {
    }

    /**
     * Builds a profile for a companion archetype.
     *
     * @param archetype {@code yui} or {@code sandrone}
     * @param skinUrl   hosted skin URL, or empty/blank for none
     * @param packKey   resource pack texture key (for example {@code companion:yui}), or null
     * @param model     arm model for the pack patch
     * @param log       logger used for non-fatal skin problems
     */
    static ResolvableProfile build(String archetype, String skinUrl, Key packKey,
                                   PlayerTextures.SkinModel model, Logger log) {
        boolean slim = archetype.equalsIgnoreCase("yui");
        PlayerTextures.SkinModel chosen = model != null
                ? model
                : (slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC);

        String name = displayName(archetype);
        UUID uuid = stableUuid(archetype);

        ResolvableProfile.Builder builder = ResolvableProfile.resolvableProfile()
                .name(name)
                .uuid(uuid);

        String url = skinUrl == null ? "" : skinUrl.trim();
        if (!url.isEmpty()) {
            addTexturesProperty(builder, url, chosen, log);
        }

        if (packKey != null) {
            builder.skinPatch(patch -> patch.body(packKey).model(chosen));
        }

        if (url.isEmpty() && packKey == null) {
            log.fine("No skin configured for " + archetype + "; mannequin uses the default skin.");
        }

        return builder.build();
    }

    /** Adds an unsigned {@code textures} property encoding the hosted skin URL. */
    private static void addTexturesProperty(ResolvableProfile.Builder builder, String url,
                                            PlayerTextures.SkinModel model, Logger log) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            log.warning("Invalid skin URL for " + url + ": " + ex.getMessage());
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            log.warning("Skin URL must be http or https, got: " + url);
            return;
        }
        if (!isSkinHost(url)) {
            log.warning("Skin URL host is not a Mojang skin host; the client may reject it: " + url);
        }

        String slimFlag = model == PlayerTextures.SkinModel.SLIM ? "slim" : "classic";
        String json = "{\"timestamp\":0,\"profileId\":\"\",\"profileName\":\"\",\"textures\":"
                + "{\"SKIN\":{\"url\":\"" + escape(url) + "\",\"metadata\":{\"model\":\"" + slimFlag + "\"}}}}";
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        builder.addProperty(new ProfileProperty("textures", encoded));
    }

    /** Only Mojang's skin service may be trusted as a skin source by defaulting clients. */
    private static boolean isSkinHost(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://textures.minecraft.net/")
                || lower.startsWith("http://textures.minecraft.net/");
    }

    /** Minimal escaping for the URL embedded in the property JSON. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Deterministic UUID so a companion keeps one identity across restarts. */
    private static UUID stableUuid(String archetype) {
        return UUID.nameUUIDFromBytes(("companion_npc:" + archetype.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String displayName(String archetype) {
        return archetype.equalsIgnoreCase("yui") ? "Yui" : "Sandrone";
    }
}
