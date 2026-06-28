package dev.anonymity;

import org.bukkit.ChatColor;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Produces the "unintelligible letters" name. A random alphabetic token is
 * generated per session and displayed with the Minecraft magic/obfuscated
 * formatting code ({@link ChatColor#MAGIC}) so the glyphs constantly cycle.
 */
final class NameObfuscator {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private NameObfuscator() {
    }

    /** A random token of {@code [min, max]} letters, e.g. {@code "kQxmVap"}. */
    static String newToken(int min, int max) {
        int lo = Math.max(1, Math.min(min, max));
        int hi = Math.max(lo, max);
        int length = ThreadLocalRandom.current().nextInt(lo, hi + 1);

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[ThreadLocalRandom.current().nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Wraps a token so it renders as ever-changing unintelligible letters.
     * The trailing reset stops the magic code from bleeding into following text.
     */
    static String scramble(String token) {
        return ChatColor.MAGIC + token + ChatColor.RESET;
    }
}
