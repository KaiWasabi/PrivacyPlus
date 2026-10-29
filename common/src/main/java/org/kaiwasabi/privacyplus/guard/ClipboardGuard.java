package org.kaiwasabi.privacyplus.guard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

// Clicking chat text (or a book page, or a dialog button) can silently put anything a server likes on the
// clipboard: a command that runs something when pasted into a terminal or the Run box, a link, or text
// hiding extra lines or invisible characters. This describes such text before it is copied. Pure, so
// it is unit-tested.
public final class ClipboardGuard {
    public enum Kind { COMMAND, LINK, LINE_BREAKS, HIDDEN }

    public record Flag(Kind kind, String text) {
    }

    public static final int PREVIEW_CHARS = 400;
    public static final int PREVIEW_LINES = 8;

    // How a command for a terminal, the Windows Run box or PowerShell starts. Words that also start ordinary
    // sentences ("start", "open", "set") only count with the switches a command would have.
    private static final Pattern SHELL_START = Pattern.compile("(?i)^\\s*((sudo|curl|wget|powershell(\\.exe)?|pwsh|cmd(\\.exe)?|"
        + "bash|zsh|iex|iwr|irm|invoke-[a-z]+|start-process|msiexec|mshta|rundll32|regsvr32|certutil|"
        + "bitsadmin|osascript|chmod|chown|schtasks|netsh|whoami|wmic|xdg-open|ncat|telnet|ssh|npm|npx|pip3?|apt(-get)?)(\\s|$)|"
        + "(python3?|node|perl|ruby|php)\\s+-|rm\\s+-|sh\\s+-c\\b|del\\s+/|reg\\s+(add|delete)\\b|net\\s+user\\b|start\\s+/)");
    // Pieces that make text a command wherever they appear
    private static final Pattern SHELL_ANYWHERE = Pattern.compile("(?i)(\\|\\s*(ba|z|da)?sh\\b|\\|\\s*iex\\b|\\biex\\s*\\(|"
        + "invoke-expression|invoke-webrequest|downloadstring|frombase64string|-encodedcommand\\b|\\s-enc\\s|-windowstyle\\s+hidden|"
        + "-w\\s+hidden|cmd(\\.exe)?\\s+/c\\b|\\bpowershell\\b|\\bmshta\\b|\\brundll32\\b|\\bcurl\\s[^|\\n]{0,200}\\|)");
    private static final Pattern MINECRAFT_COMMAND = Pattern.compile("^\\s*/[A-Za-z]");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(https?://[^\\s\"'<>]+|www\\.[^\\s\"'<>]+|"
        + "[a-z0-9][a-z0-9.-]{0,252}\\.[a-z]{2,}/[^\\s\"'<>]*)");
    // The patterns only look at the start of very long text: a server picks the text, and scanning all of
    // a 64 KB value on the render thread could stall the game (and a pattern that recursed could crash it)
    private static final int INSPECT_LIMIT = 4096;

    private ClipboardGuard() {
    }

    public static List<Flag> inspect(String text) {
        try {
            return inspectUnchecked(text);
        } catch (StackOverflowError e) {
            // Never let server-chosen text take the game down; say it couldn't be checked instead
            return List.of(new Flag(Kind.COMMAND, "This text couldn't be checked. Don't paste it anywhere you don't trust."));
        }
    }

    private static List<Flag> inspectUnchecked(String text) {
        List<Flag> flags = new ArrayList<>();
        String start = text.length() > INSPECT_LIMIT ? text.substring(0, INSPECT_LIMIT) : text;
        if (SHELL_START.matcher(start).find() || SHELL_ANYWHERE.matcher(start).find()) {
            flags.add(new Flag(Kind.COMMAND, "Looks like a command for your computer. Pasted into a terminal, PowerShell or the "
                + "Run box it would run: never do that with text you didn't write."));
        } else if (MINECRAFT_COMMAND.matcher(start).find()) {
            flags.add(new Flag(Kind.COMMAND, "Looks like a Minecraft command: pasted into chat and sent, it runs as you."));
        }

        Matcher link = LINK.matcher(start);
        if (link.find()) {
            flags.add(new Flag(Kind.LINK, linkText(link.group(1))));
        }

        int lines = lineCount(text);
        if (lines > 1) {
            flags.add(new Flag(Kind.LINE_BREAKS, "Has " + lines + " lines. Where you paste it, only the first may show, and a line "
                + "break can send or run the text straight away."));
        }

        long hidden = text.codePoints().filter(ClipboardGuard::hidden).count();
        if (hidden > 0) {
            flags.add(new Flag(Kind.HIDDEN, "Contains " + hidden + (hidden == 1 ? " invisible character" : " invisible characters")
                + " (shown as [U+...] above), which can hide or reorder what you see."));
        }
        return List.copyOf(flags);
    }

    private static String linkText(String found) {
        String spec = found.toLowerCase(Locale.ROOT).startsWith("http") ? found : "https://" + found;
        try {
            LinkGuard.Analysis analysis = LinkGuard.analyze(new URI(spec));
            String text = "Contains a link to " + (analysis.host().isEmpty() ? "an unclear address" : analysis.host()) + ".";
            List<LinkGuard.Warning> warnings = analysis.warnings();
            return warnings.isEmpty() ? text : text + " " + warnings.getFirst().text();
        } catch (URISyntaxException e) {
            return "Contains a link.";
        }
    }

    // \r\n, \n and \r each end a line
    static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                lines++;
            }
        }
        return lines;
    }

    // Zero-width and direction-changing characters, and control characters other than line breaks and tabs
    static boolean hidden(int cp) {
        return (cp < 0x20 && cp != '\n' && cp != '\r' && cp != '\t') || cp == 0x7F || (cp >= 0x80 && cp < 0xA0) || cp == 0xAD
            || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x2064)
            || (cp >= 0x2066 && cp <= 0x2069) || cp == 0xFEFF || (cp >= 0xE0000 && cp <= 0xE007F);
    }

    // The text as it will be shown in the prompt: line breaks marked with ↵ (and kept), invisible characters
    // spelled out, and cut after PREVIEW_CHARS characters or PREVIEW_LINES lines. truncated says whether anything was cut.
    public record Preview(String text, boolean truncated) {
    }

    public static Preview preview(String text) {
        StringBuilder out = new StringBuilder();
        int lines = 1;
        int shown = 0;
        int i = 0;
        while (i < text.length()) {
            if (shown >= PREVIEW_CHARS || lines > PREVIEW_LINES) {
                return new Preview(trimEnd(out), true);
            }
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (cp == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                width = 2;
                cp = '\n';
            }
            if (cp == '\n' || cp == '\r') {
                out.append("↵\n");
                lines++;
            } else if (cp == '\t') {
                out.append("    ");
            } else if (hidden(cp)) {
                out.append(String.format(Locale.ROOT, "[U+%04X]", cp));
            } else {
                out.appendCodePoint(cp);
            }
            shown++;
            i += width;
        }
        return new Preview(out.toString(), false);
    }

    private static String trimEnd(StringBuilder out) {
        String text = out.toString();
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    // "1,234 characters"
    public static String length(@Nullable String text) {
        int count = text == null ? 0 : text.codePointCount(0, text.length());
        return String.format(Locale.ROOT, "%,d %s", count, count == 1 ? "character" : "characters");
    }
}
