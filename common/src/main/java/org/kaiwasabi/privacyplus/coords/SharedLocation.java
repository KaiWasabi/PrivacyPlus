package org.kaiwasabi.privacyplus.coords;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

// A location shared with /coords, as carried in the body of the encrypted "coords" control message:
//   <x> <y> <z> <dimension>[ <label>]
// Block coordinates, the dimension id without "minecraft:" (overworld, the_nether, the_end, or a modded
// id such as aether:the_aether), then an optional short label. Bodies come from other players, so
// decoding is strict: anything out of range or odd is rejected rather than shown, and labels lose
// formatting codes, control characters and anything else that could change how chat renders.
public record SharedLocation(int x, int y, int z, String dimension, @Nullable String label) {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    // Just past the world border (29,999,984) and beyond any data pack's height limits
    static final int MAX_HORIZONTAL = 30_000_000;
    static final int MAX_VERTICAL = 4096;
    public static final int MAX_LABEL_CHARS = 32;
    // Keeps the whole message inside one encrypted chat token whatever the label's script
    static final int MAX_LABEL_BYTES = 48;
    static final int MAX_DIMENSION_CHARS = 48;
    // Longer than any body we would send; anything bigger isn't worth parsing
    private static final int MAX_BODY_CHARS = 512;
    // ASCII digits only: Integer.parseInt would also accept other scripts' digits
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,9}");

    public SharedLocation {
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
        dimension = normalizeDimension(dimension);
        if (dimension == null) {
            throw new IllegalArgumentException("Invalid dimension");
        }
        label = cleanLabel(label);
    }

    // The message body, e.g. "120 64 -300 overworld base"
    public String encode() {
        return this.x + " " + this.y + " " + this.z + " " + shortId(this.dimension) + (this.label == null ? "" : " " + this.label);
    }

    // Empty for anything that isn't a well-formed body
    public static Optional<SharedLocation> decode(String body) {
        if (body.length() > MAX_BODY_CHARS) {
            return Optional.empty();
        }
        String[] fields = body.strip().split(" +", 5);
        if (fields.length < 4 || !NUMBER.matcher(fields[0]).matches() || !NUMBER.matcher(fields[1]).matches()
            || !NUMBER.matcher(fields[2]).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SharedLocation(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                fields[3], fields.length == 5 ? fields[4] : null));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // "X Y Z", as copied to the clipboard
    public String coordinates() {
        return this.x + " " + this.y + " " + this.z;
    }

    public String dimensionName() {
        return dimensionName(this.dimension);
    }

    // Overworld, Nether and End by name; anything else by its id
    public static String dimensionName(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "End";
            default -> shortId(dimension);
        };
    }

    // "minecraft:overworld" or "overworld" -> "minecraft:overworld"; null if it isn't a valid id
    static @Nullable String normalizeDimension(@Nullable String dimension) {
        if (dimension == null || dimension.isEmpty() || dimension.length() > MAX_DIMENSION_CHARS) {
            return null;
        }
        Identifier id = Identifier.tryParse(dimension);
        return id == null || id.getPath().isEmpty() ? null : id.toString();
    }

    private static String shortId(String dimension) {
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }

    // The label as it may be shown: no § formatting codes, control or bidi characters, private-use glyphs
    // (resource packs draw those as images) or unpaired surrogates; whitespace runs become one space;
    // at most MAX_LABEL_CHARS characters and MAX_LABEL_BYTES bytes. Null when nothing is left.
    public static @Nullable String cleanLabel(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int chars = 0;
        int bytes = 0;
        boolean pendingSpace = false;
        int[] codePoints = raw.codePoints().limit(MAX_BODY_CHARS).toArray();
        for (int codePoint : codePoints) {
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (!isShowable(codePoint)) {
                continue;
            }
            String piece = (pendingSpace ? " " : "") + Character.toString(codePoint);
            int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
            int pieceChars = pendingSpace ? 2 : 1;
            if (chars + pieceChars > MAX_LABEL_CHARS || bytes + pieceBytes > MAX_LABEL_BYTES) {
                break;
            }
            out.append(piece);
            chars += pieceChars;
            bytes += pieceBytes;
            pendingSpace = false;
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static boolean isShowable(int codePoint) {
        if (codePoint == '§') {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
