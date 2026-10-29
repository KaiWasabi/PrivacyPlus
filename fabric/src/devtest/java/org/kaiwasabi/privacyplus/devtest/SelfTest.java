package org.kaiwasabi.privacyplus.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import org.kaiwasabi.privacyplus.chat.GearButton;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import net.minecraft.network.chat.Style;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.suggestion.Suggestions;
import com.sun.net.httpserver.HttpServer;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.ChatCrypto;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.chat.LockButton;
import org.kaiwasabi.privacyplus.dns.PrivateDnsToggle;
import org.kaiwasabi.privacyplus.guard.TransferPromptScreen;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.kaiwasabi.privacyplus.stealth.ServerPingGate;
import org.kaiwasabi.privacyplus.telemetry.TelemetryBlocker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

// Dev-only end-to-end check, run with ./gradlew :fabric:runSelftest (never part of the release jar).
// Waits for the title screen, exercises each feature inside the real client, logs
// "SELFTEST RESULT: PASS|FAIL" and quits. The server-list checks need a fake server at
// -Dprivacyplus.selftest.server that answers status pings.
public final class SelfTest implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PrivacyPlus-SelfTest");
    private final List<String> failures = new ArrayList<>();
    private int checks;

    @Override
    public void onInitializeClient() {
        Thread thread = new Thread(this::run, "PrivacyPlus-SelfTest");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        Minecraft mc = Minecraft.getInstance();
        try {
            // Client entrypoints run while Minecraft is still being constructed, before gui exists
            waitFor(() -> mc.gui != null && mc.gui.screen() instanceof TitleScreen, 120_000);
            onRenderThread(mc, this::telemetry);
            Thread.sleep(1500); // let the title screen finish fading in
            onRenderThread(mc, () -> Screenshot.grab(mc.gameDirectory, "selftest-title.png", mc.gameRenderer.mainRenderTarget(), 1, message -> { }));
            onRenderThread(mc, () -> language(mc));
            onRenderThread(mc, this::signProbe);
            check("alarm noticed the sign's mod key and keybinds, not its vanilla text",
                noticed(ProbeAlarm.Kind.SIGN, "unknown key devtest.secret") && noticed(ProbeAlarm.Kind.SIGN, "keybind key.devtest.open")
                    && noticed(ProbeAlarm.Kind.SIGN, "keybind key.jump")
                    && ProbeAlarm.events().stream().noneMatch(e -> e.detail().contains("gui.done")));
            serverList(mc);
            privateDns(mc);
            packHosts(mc);
            onRenderThread(mc, () -> MixinEnvironment.getCurrentEnvironment().audit());
            check("mixin audit applied every mixin without errors", true);
            encryptedChat(mc);
            onRenderThread(mc, () -> commandSuggestions(mc));
            incognito(mc);
            onRenderThread(mc, () -> {
                ChatScreen chat = new ChatScreen("", false);
                mc.gui.setScreen(chat);
                chat.handleChatInput("/pp probes", true);
                check("/pp probes lists the transfer", chatLines(mc).stream().anyMatch(l -> l.endsWith("sent you to another server: 127.0.0.1:25602")));
                chat.handleChatInput("/pp dns quad9", true);
                check("/pp dns quad9 switches private DNS to Quad9", PrivacyPlusConfig.privateDns.equals("quad9")
                    && chatLines(mc).stream().anyMatch(l -> l.contains("Servers are looked up with encrypted DNS at Quad9")));
                chat.handleChatInput("/pp dns http://dns.example/dns-query", true);
                check("/pp dns refuses an unencrypted service", PrivacyPlusConfig.privateDns.equals("quad9"));
                chat.handleChatInput("/pp dns off", true);
                check("/pp dns off switches it off", PrivacyPlusConfig.privateDns.equals("off"));
                mc.gui.setScreen(null);
            });
            // Per-feature checks, each in its own class. They start in the single-player world created by
            // encryptedChat, with the channel "selftest" set up and no screen open.
            GuardChecks.run(this, mc);
            LogChecks.run(this, mc);
            AntiDetectChecks.run(this, mc);
            ChatChecks.run(this, mc);
            CoordsChecks.run(this, mc);
            GuiChecks.run(this, mc);
        } catch (Throwable t) {
            LOG.error("Self-test crashed", t);
            failures.add("crashed: " + t);
        }
        LOG.info("SELFTEST RESULT: {} ({} checks, {} failed)", failures.isEmpty() ? "PASS" : "FAIL", checks, failures.size());
        failures.forEach(f -> LOG.info("SELFTEST FAILED: {}", f));
        mc.execute(mc::stop);
    }

    private void telemetry() {
        check("telemetry events were blocked (" + TelemetryBlocker.blockedCount() + ")", TelemetryBlocker.blockedCount() > 0);
    }

    // selftest-all.sh runs the client in German, so a leak of the real language would show
    private void language(Minecraft mc) {
        check("sanity: the client really runs in German (gui.done = \"" + Component.translatable("gui.done").getString() + "\")",
            Component.translatable("gui.done").getString().equals("Fertig"));
        check("client information sent to servers reports en_us", mc.options.buildPlayerInformation().language().equals("en_us"));
    }

    // Opens a sign editor on a sign whose lines are a vanilla key, a vanilla keybind, and a mod key and
    // keybind that the current language does resolve, then reads the strings the editor would send back.
    // With the language spoofed, the vanilla ones must come back in English whatever the game language.
    private void signProbe() {
        Language original = Language.getInstance();
        Map<String, String> modKeys = Map.of("devtest.secret", "Secret Mod Text", "key.devtest.open", "Open Devtest");
        Language.inject(new Language() {
            @Override
            public String getOrDefault(String key, String defaultValue) {
                return modKeys.getOrDefault(key, original.getOrDefault(key, defaultValue));
            }

            @Override
            public boolean has(String key) {
                return modKeys.containsKey(key) || original.has(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return original.isDefaultRightToLeft();
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return original.getVisualOrder(text);
            }
        });
        try {
            List<Component> lines = List.of(
                Component.translatable("gui.done"),
                Component.keybind("key.jump"),
                Component.translatable("devtest.secret"),
                Component.keybind("key.devtest.open")
            );
            check("sanity: mod key resolves without the shield", lines.get(2).getString().equals("Secret Mod Text"));

            SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
            // setText() would notify the (nonexistent) world, so set the field directly
            field(SignBlockEntity.class, "frontText").set(sign, new SignText(lines, lines, DyeColor.BLACK, false));
            String[] sent = (String[]) field(AbstractSignEditScreen.class, "messages").get(new SignEditScreen(sign, SignTextSlot.FRONT, false));
            List<String> expected = List.of("Done", "Space", "devtest.secret", "key.devtest.open");
            check("sign editor sends " + Arrays.toString(sent) + ", expected " + expected, Arrays.asList(sent).equals(expected));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        } finally {
            Language.inject(original);
        }
    }

    private void serverList(Minecraft mc) throws Exception {
        String address = System.getProperty("privacyplus.selftest.server");
        if (address == null) {
            check("server list test skipped: no -Dprivacyplus.selftest.server", false);
            return;
        }
        onRenderThread(mc, () -> {
            ServerList servers = new ServerList(mc);
            servers.load();
            if (servers.size() == 0) {
                servers.add(new ServerData("PrivacyPlus fake server", address, ServerData.Type.OTHER), false);
                servers.save();
            }
            mc.gui.setScreen(new JoinMultiplayerScreen(mc.gui.screen()));
        });
        Thread.sleep(4000);
        onRenderThread(mc, () -> {
            ServerData server = firstServer(mc);
            check("unselected server not pinged (state " + server.state() + ")", server.state() == ServerData.State.INITIAL);
            check("unselected server shows the not-pinged text", server.motd.getString().equals(ServerPingGate.NOT_PINGED.getString()));
            selectFirstServer(mc);
        });
        // Deselect as soon as the ping has started: with an entry selected, a stray Enter (the window
        // takes keyboard focus when it opens) would join the server and derail the test
        for (int i = 0; i < 40; i++) {
            boolean[] started = {false};
            onRenderThread(mc, () -> started[0] = firstServer(mc).state() != ServerData.State.INITIAL);
            if (started[0]) {
                break;
            }
            Thread.sleep(50);
        }
        onRenderThread(mc, () -> serverSelectionList(mc).setSelected(null));
        Thread.sleep(4000);
        onRenderThread(mc, () -> check("selected server was pinged (state " + firstServer(mc).state() + ")",
            firstServer(mc).state() == ServerData.State.SUCCESSFUL));
        onRenderThread(mc, () -> invoke(JoinMultiplayerScreen.class, "refreshServerList", mc.gui.screen()));
        Thread.sleep(4000);
        onRenderThread(mc, () -> check("Refresh pinged the server again (state " + firstServer(mc).state() + ")",
            firstServer(mc).state() == ServerData.State.SUCCESSFUL));
        onRenderThread(mc, () -> mc.gui.setScreen(new TitleScreen()));
    }

    // Private DNS against a local DNS-over-HTTPS service for the made-up zone privacyplus.example, where
    // dns.privacyplus.example has an SRV record pointing (through a CNAME) at the fake server
    private void privateDns(Minecraft mc) throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        HttpServer doh = dohServer(25603, asked, sizes);
        ServerData named = new ServerData("Private DNS test", "dns.privacyplus.example", ServerData.Type.OTHER);
        try {
            PrivacyPlusConfig.setPrivateDns("http://127.0.0.1:25603/dns-query");
            InetSocketAddress resolved = ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString("dns.privacyplus.example"))
                .map(ResolvedServerAddress::asInetSocketAddress).orElse(null);
            check("private DNS followed the SRV record and CNAME to the fake server (" + resolved + ")", resolved != null
                && resolved.getAddress().getHostAddress().equals("127.0.0.1") && resolved.getPort() == 25599);
            check("handshake name is the SRV target with its trailing dot, as vanilla sends it",
                resolved != null && resolved.getHostName().equals("target.privacyplus.example."));
            check("asked in vanilla's order: name, SRV record, target " + asked, asked.equals(List.of(
                "dns.privacyplus.example A", "_minecraft._tcp.dns.privacyplus.example SRV", "target.privacyplus.example A")));
            check("queries were padded to 128-byte blocks " + sizes, !sizes.isEmpty() && sizes.stream().allMatch(n -> n % 128 == 0));

            // The server list pings through the same lookup
            onRenderThread(mc, () -> {
                ServerList servers = new ServerList(mc);
                servers.load();
                servers.add(named, false);
                servers.save();
                mc.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
            });
            Thread.sleep(1000);
            onRenderThread(mc, () -> {
                serverSelectionList(mc).setSelected(entryFor(mc, named.ip));
                Screenshot.grab(mc.gameDirectory, "selftest-multiplayer.png", mc.gameRenderer.mainRenderTarget(), 1, message -> { });
            });
            waitFor(() -> {
                ServerData[] data = {null};
                onRenderThread(mc, () -> data[0] = entryFor(mc, named.ip).getServerData());
                return data[0].state() == ServerData.State.SUCCESSFUL;
            }, 10_000);
            onRenderThread(mc, () -> {
                serverSelectionList(mc).setSelected(null);
                check("server-list ping reached the server through private DNS", entryFor(mc, named.ip).getServerData().state() == ServerData.State.SUCCESSFUL);
                PrivateDnsToggle toggle = mc.gui.screen().children().stream().filter(PrivateDnsToggle.class::isInstance)
                    .map(PrivateDnsToggle.class::cast).findFirst().orElse(null);
                check("multiplayer screen has the private DNS switch", toggle != null);
                click(toggle, InputConstants.MOUSE_BUTTON_LEFT);
                check("clicking the switch moves on to off", PrivacyPlusConfig.privateDns.equals("off"));
                click(toggle, InputConstants.MOUSE_BUTTON_LEFT);
                check("and then to Cloudflare", PrivacyPlusConfig.privateDns.equals("cloudflare"));
            });

            // Nobody listens on 25604: the lookup fails instead of quietly using the system DNS
            PrivacyPlusConfig.setPrivateDns("http://127.0.0.1:25604/dns-query");
            check("unreachable private DNS fails closed",
                ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString("dns.privacyplus.example")).isEmpty());
            check("local names still resolve on this computer", ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString("localhost:25599"))
                .map(r -> r.asInetSocketAddress().getAddress().isLoopbackAddress()).orElse(false));
            check("IP addresses need no lookup, not even a reverse one", ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString("127.0.0.1:25599"))
                .map(r -> r.getHostName().equals("127.0.0.1")).orElse(false));
        } finally {
            PrivacyPlusConfig.setPrivateDns("off");
            doh.stop(0);
            onRenderThread(mc, () -> {
                ServerList servers = new ServerList(mc);
                servers.load();
                for (int i = servers.size() - 1; i >= 0; i--) {
                    if (servers.get(i).ip.equals(named.ip)) {
                        servers.remove(servers.get(i));
                    }
                }
                servers.save();
                mc.gui.setScreen(new TitleScreen());
            });
        }
    }

    private static HttpServer dohServer(int port, List<String> asked, List<Integer> sizes) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        http.createContext("/dns-query", exchange -> {
            byte[] query = exchange.getRequestBody().readAllBytes();
            sizes.add(query.length);
            int end = 12;
            List<String> labels = new ArrayList<>();
            while (query[end] != 0) {
                labels.add(new String(query, end + 1, query[end], StandardCharsets.US_ASCII));
                end += 1 + query[end];
            }
            String name = String.join(".", labels);
            int type = (query[end + 1] & 0xFF) << 8 | query[end + 2] & 0xFF;
            asked.add(name + " " + (type == 1 ? "A" : type == 33 ? "SRV" : type == 28 ? "AAAA" : String.valueOf(type)));
            ByteArrayOutputStream answers = new ByteArrayOutputStream();
            int count = 0;
            if (name.equals("dns.privacyplus.example") && type == 1) {
                count += dnsRecord(answers, name, 1, new byte[] {127, 0, 0, 2});
            } else if (name.equals("_minecraft._tcp.dns.privacyplus.example") && type == 33) {
                ByteArrayOutputStream srv = new ByteArrayOutputStream();
                srv.writeBytes(new byte[] {0, 0, 0, 5, (byte) (25599 >> 8), (byte) 25599});
                srv.writeBytes(dnsName("target.privacyplus.example"));
                count += dnsRecord(answers, name, 33, srv.toByteArray());
            } else if (name.equals("target.privacyplus.example") && type == 1) {
                count += dnsRecord(answers, name, 5, dnsName("edge.privacyplus.example"));
                count += dnsRecord(answers, "edge.privacyplus.example", 1, new byte[] {127, 0, 0, 1});
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            response.writeBytes(new byte[] {0, 0, (byte) 0x81, (byte) (count == 0 ? 0x83 : 0x80), 0, 1, 0, (byte) count, 0, 0, 0, 0});
            response.write(query, 12, end + 5 - 12);
            response.writeBytes(answers.toByteArray());
            byte[] body = response.toByteArray();
            exchange.getResponseHeaders().add("Content-Type", "application/dns-message");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        return http;
    }

    private static int dnsRecord(ByteArrayOutputStream out, String name, int type, byte[] data) {
        out.writeBytes(dnsName(name));
        out.writeBytes(new byte[] {0, (byte) type, 0, 1, 0, 0, 0, 60, (byte) (data.length >> 8), (byte) data.length});
        out.writeBytes(data);
        return 1;
    }

    private static byte[] dnsName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        out.write(0);
        return out.toByteArray();
    }

    static ServerSelectionList.OnlineServerEntry entryFor(Minecraft mc, String ip) {
        return serverSelectionList(mc).children().stream()
            .filter(ServerSelectionList.OnlineServerEntry.class::isInstance)
            .map(ServerSelectionList.OnlineServerEntry.class::cast)
            .filter(e -> e.getServerData().ip.equals(ip))
            .findFirst()
            .orElseThrow();
    }

    // Joins the fake server as a saved server whose packs were accepted before. The fake server pushes one
    // pack from its own host (localhost) and one from another (127.0.0.1); a local HTTP server on port
    // 25600 records the headers each download carried.
    private void packHosts(Minecraft mc) throws Exception {
        Map<String, Map<String, List<String>>> requests = new ConcurrentHashMap<>();
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25600), 0);
        http.createContext("/", exchange -> {
            requests.put(exchange.getRequestURI().getPath(), new HashMap<>(exchange.getRequestHeaders()));
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        http.start();
        try {
            onRenderThread(mc, () -> {
                ServerData server = new ServerData("pack test", "localhost:25599", ServerData.Type.OTHER);
                server.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server.ip), server, false, null);
            });
            // Read and answer the prompt in the same render tick it is seen, so nothing (like a click in
            // the window) can answer or replace it in between
            boolean[] prompted = {false};
            long deadline = System.currentTimeMillis() + 30_000;
            while (!prompted[0] && System.currentTimeMillis() < deadline) {
                onRenderThread(mc, () -> prompted[0] = answerPackPrompt(mc));
                Thread.sleep(50);
            }
            check("the pack prompt appeared", prompted[0]);
            waitFor(() -> requests.size() >= 2, 20_000);
        } finally {
            http.stop(0);
        }
        Map<String, List<String>> own = requests.getOrDefault("/first-party.zip", Map.of());
        Map<String, List<String>> other = requests.getOrDefault("/third-party.zip", Map.of());
        check("pack from the server's own host carried the vanilla username header", hasHeader(own, "X-Minecraft-Username"));
        check("pack from another host had no username or UUID header",
            hasHeader(other, "X-Minecraft-Version") && !hasHeader(other, "X-Minecraft-Username") && !hasHeader(other, "X-Minecraft-UUID"));

        // The fake server also probes (cookies at login and in configuration), then transfers the client to
        // a port nobody listens on. transferGuard=ASK asks first; Go connects there, which ends on the
        // disconnected screen.
        boolean[] asked = {false};
        long transferDeadline = System.currentTimeMillis() + 30_000;
        while (!asked[0] && System.currentTimeMillis() < transferDeadline) {
            onRenderThread(mc, () -> asked[0] = answerTransferPrompt(mc));
            Thread.sleep(50);
        }
        check("the transfer prompt appeared", asked[0]);
        waitFor(() -> mc.gui.screen() instanceof DisconnectedScreen || mc.gui.screen() instanceof TitleScreen, 30_000);
        onRenderThread(mc, () -> Screenshot.grab(mc.gameDirectory, "selftest-probe-toasts.png", mc.gameRenderer.mainRenderTarget(), 1, message -> { }));
        check("alarm noticed the resource pack from another site", noticed(ProbeAlarm.Kind.PACK, "127.0.0.1"));
        check("alarm noticed the cookie request during login", noticed(ProbeAlarm.Kind.COOKIE_REQUEST, "privacyplus:login, none stored"));
        check("alarm noticed the stored cookie", noticed(ProbeAlarm.Kind.COOKIE_STORE, "privacyplus:tracking, 11 bytes"));
        check("alarm noticed the server asking for its cookie back",
            noticed(ProbeAlarm.Kind.COOKIE_REQUEST, "privacyplus:tracking, answered with a stored cookie"));
        check("alarm noticed the transfer", noticed(ProbeAlarm.Kind.TRANSFER, "127.0.0.1:25602"));
        check("alarm named the server for probes sent while joining", ProbeAlarm.events().stream()
            .filter(e -> e.kind() != ProbeAlarm.Kind.SIGN && e.kind() != ProbeAlarm.Kind.ANVIL)
            .allMatch(e -> e.server().equals("localhost:25599")));
        onRenderThread(mc, () -> mc.gui.setScreen(new TitleScreen()));
    }

    // On the render thread: if the transfer prompt is open, check what it says and press Go
    private boolean answerTransferPrompt(Minecraft mc) {
        if (!(mc.gui.screen() instanceof TransferPromptScreen prompt)) {
            return false;
        }
        try {
            String message = ((Component) field(ConfirmScreen.class, "message").get(prompt)).getString();
            check("transfer prompt names the server and where it sends you",
                message.contains("localhost:25599 wants to send you to 127.0.0.1:25602."));
            check("transfer prompt says 127.0.0.1 is another site and its cookies stay behind",
                message.contains("different site") && message.contains(" behind (cookieGuard=SAME_SITE)"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        check("cookies are dropped for a transfer to another site (cookieGuard=SAME_SITE)",
            PrivacyPlusConfig.cookieGuard == PrivacyPlusConfig.CookieGuard.SAME_SITE && prompt.transferState().cookies().isEmpty());
        Button go = prompt.children().stream()
            .filter(c -> c instanceof Button b && b.getMessage().getString().equals("Go"))
            .map(Button.class::cast).findFirst().orElse(null);
        check("transfer prompt has a Go button", go != null);
        if (go != null) {
            click(go, InputConstants.MOUSE_BUTTON_LEFT);
            check("Go connects on to the transferred-to server", mc.gui.screen() instanceof ConnectScreen);
        } else {
            mc.gui.setScreen(new TitleScreen());
        }
        return true;
    }

    // On the render thread: if the pack prompt is open, check what it says and accept it
    private boolean answerPackPrompt(Minecraft mc) {
        if (!(mc.gui.screen() instanceof ConfirmScreen prompt) || prompt instanceof TransferPromptScreen) {
            return false;
        }
        try {
            String message = ((Component) field(ConfirmScreen.class, "message").get(prompt)).getString();
            check("accepted-packs server still prompts for the other host's pack, naming only it",
                message.contains("Downloaded from 127.0.0.1, not from this server") && !message.contains("localhost"));
            ((BooleanConsumer) field(ConfirmScreen.class, "callback").get(prompt)).accept(true);
            return true;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean noticed(ProbeAlarm.Kind kind, String detail) {
        return ProbeAlarm.events().stream().anyMatch(e -> e.kind() == kind && e.detail().equals(detail));
    }

    // com.sun.net.httpserver normalizes header name case
    static boolean hasHeader(Map<String, List<String>> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    // Creates a singleplayer world and chats through its integrated server, which echoes every message
    // back: covers sending, receiving and what ends up on disk.
    private void encryptedChat(Minecraft mc) throws Exception {
        // Only ever runs in the throwaway runs/selftest directory
        Files.writeString(mc.gameDirectory.toPath().resolve("config/privacyplus-chat.properties"),
            "channel.selftest=privacyplus self-test passphrase, not a secret\n");
        ChatSettings.reload();
        ChatSettings.Channel channel = ChatSettings.get("selftest");
        waitFor(() -> channel.readyKey() != null, 30_000);
        onRenderThread(mc, () -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
        waitFor(() -> mc.gui.screen() instanceof CreateWorldScreen, 60_000);
        onRenderThread(mc, () -> invoke(CreateWorldScreen.class, "onCreate", mc.gui.screen()));
        waitFor(() -> mc.player != null && mc.level != null && mc.gui.screen() == null, 180_000);

        // Unique strings, so finding one anywhere on disk can only mean a leak
        String secret = "meet at the hidden base " + UUID.randomUUID();
        String whisper = "whispered secret " + UUID.randomUUID();
        String commandsOnly = "public chat in commands-only mode " + UUID.randomUUID();
        String plain = "plain text sanity check " + UUID.randomUUID();
        String longMessage = "z".repeat(256);
        String me = mc.getUser().getName();
        int expectedParts = ChatCrypto.encrypt(channel.readyKey(), me, longMessage, 256, Instant.now()).size();
        ChatScreen[] chat = new ChatScreen[1];
        onRenderThread(mc, () -> {
            chat[0] = new ChatScreen("", false);
            mc.gui.setScreen(chat[0]);
            check("chat box has the lock, and it starts off", lockButton(chat[0]) != null && ChatSettings.active() == null);
            click(lockButton(chat[0]), InputConstants.MOUSE_BUTTON_LEFT);
            check("left-clicking the lock turns encryption on", ChatSettings.active() != null && "selftest".equals(ChatSettings.active().name()));
            chat[0].handleChatInput(secret, true);
            chat[0].handleChatInput(longMessage, true);
            chat[0].handleChatInput("/msg " + me + " " + whisper, true);
        });
        Thread.sleep(3000);
        onRenderThread(mc, () -> Screenshot.grab(mc.gameDirectory, "selftest-chat-locked.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {}));
        onRenderThread(mc, () -> {
            ChatSettings.setEncryptPublicChat(false);
            chat[0].handleChatInput(commandsOnly, true);
            ChatSettings.setEncryptPublicChat(true);
            click(lockButton(chat[0]), InputConstants.MOUSE_BUTTON_LEFT);
            check("clicking the lock again turns encryption off", ChatSettings.active() == null);
            chat[0].handleChatInput(plain, true);
        });
        Thread.sleep(3000);

        String lock = EncryptedChat.LOCK + " ";
        onRenderThread(mc, () -> {
            List<String> shown = chatLines(mc);
            check("server echo of our encrypted message is shown decrypted, with a lock in front",
                shown.stream().anyMatch(l -> l.equals(lock + "<" + me + "> " + secret)));
            check("256-character message arrived as " + expectedParts + " labelled parts",
                shown.stream().anyMatch(l -> l.startsWith(lock) && l.contains("[1/" + expectedParts + "] "))
                    && shown.stream().anyMatch(l -> l.startsWith(lock) && l.contains("[" + expectedParts + "/" + expectedParts + "] ")));
            check("encrypted /msg arrived decrypted, with the recipient readable",
                shown.stream().anyMatch(l -> l.startsWith(lock) && l.contains(me) && l.endsWith(whisper)));
            check("commands-only mode sends public chat as plain text", shown.stream().anyMatch(l -> l.endsWith(commandsOnly) && !l.startsWith(lock)));
            check("message after turning the lock off went out as plain text", shown.stream().anyMatch(l -> l.endsWith(plain) && !l.startsWith(lock)));
            click(lockButton(chat[0]), InputConstants.MOUSE_BUTTON_RIGHT);
            check("right-clicking the lock opens the settings", mc.gui.screen() instanceof ChatSettingsScreen);
        });
        Thread.sleep(1000);
        onRenderThread(mc, () -> Screenshot.grab(mc.gameDirectory, "selftest-settings.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {}));
        onRenderThread(mc, () -> {
            mc.gui.screen().onClose();
            check("closing the settings returns to the chat box", mc.gui.screen() instanceof ChatScreen);
            ChatScreen box = (ChatScreen) mc.gui.screen();

            GearButton gear = box.children().stream().filter(GearButton.class::isInstance).map(GearButton.class::cast).findFirst().orElse(null);
            check("chat box has the gear button", gear != null);
            gear.mouseClicked(new MouseButtonEvent(gear.getX() + 6, gear.getY() + 6, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            check("clicking the gear opens the settings", mc.gui.screen() instanceof ChatSettingsScreen);
            // The lock is off here; the settings turn it on and off from the channel list
            click(settingsButton(mc, "Use"), InputConstants.MOUSE_BUTTON_LEFT);
            check("Use in the settings turns encryption on", ChatSettings.active() != null);
            click(settingsButton(mc, "Turn off"), InputConstants.MOUSE_BUTTON_LEFT);
            check("Turn off in the settings turns encryption off", ChatSettings.active() == null);
            mc.gui.screen().onClose();

            // Click the 🔒 in front of a decrypted line, through the chat screen's own click handling
            Style lockStyle = chatComponents(mc).stream()
                .filter(c -> c.getString().startsWith(lock))
                .map(c -> c.getSiblings().getFirst().getStyle())
                .findFirst().orElseThrow();
            invoke(ChatScreen.class, "handleComponentClicked", mc.gui.screen(), lockStyle, false);
            check("clicking the lock mark in chat opens the settings", mc.gui.screen() instanceof ChatSettingsScreen);
            mc.gui.screen().onClose();

            EditBox input = chatInput((ChatScreen) mc.gui.screen());
            input.setValue("/pp sett");
            check("/pp shows its own completions instead of vanilla's unknown-command error", ppCompletesLocally((ChatScreen) mc.gui.screen()));
            input.setValue("");
        });

        String log = Files.readString(mc.gameDirectory.toPath().resolve("logs/latest.log"));
        check("plaintext never reached latest.log (client or integrated server)", !log.contains(secret) && !log.contains(whisper));
        check("sanity: latest.log does record chat", log.contains(plain));
        Path history = mc.gameDirectory.toPath().resolve("command_history.txt");
        check("encrypted /msg kept out of command_history.txt", !Files.exists(history) || !Files.readString(history).contains(whisper));
        onRenderThread(mc, () -> mc.gui.setScreen(null));
    }

    // Types into a real chat box. /trigger's objective argument is completed by the server, like most
    // plugin commands' arguments; the client's request counter shows whether anything was sent.
    private void commandSuggestions(Minecraft mc) {
        ChatScreen chat = new ChatScreen("", false);
        mc.gui.setScreen(chat);
        EditBox input = chatInput(chat);
        KeyEvent tab = new KeyEvent(InputConstants.KEY_TAB, 0, 0);
        int before = suggestionRequests(mc);

        PrivacyPlusConfig.commandSuggestionsOnTab = false;
        input.setValue("/trigger z");
        check("sanity: vanilla behaviour sends a request while typing", suggestionRequests(mc) == before + 1);
        PrivacyPlusConfig.commandSuggestionsOnTab = true;

        for (String typed : List.of("/trigger a", "/trigger ab", "/trigger abc")) {
            input.setValue(typed);
        }
        check("typing a command sent nothing to the server", suggestionRequests(mc) == before + 1);
        chat.keyPressed(tab);
        check("Tab sent exactly one suggestion request", suggestionRequests(mc) == before + 2);

        String rules = ChatSettings.encryptCommandsText();
        ChatSettings.setEncryptCommands(rules + " trigger:0");
        ChatSettings.setActive(ChatSettings.get("selftest"));
        input.setValue("/trigger top secret");
        chat.keyPressed(tab);
        check("with the lock on, text it would encrypt isn't sent even on Tab", suggestionRequests(mc) == before + 2);
        ChatSettings.setEncryptCommands(rules);
        ChatSettings.setActive(null);
        input.setValue("");
        mc.gui.setScreen(null);
    }

    // Switches incognito on with /pp in a real chat box, chats, runs a command and uses Direct Connect,
    // then checks none of it reached the disk; switched off again, the same things must be written.
    private void incognito(Minecraft mc) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String hiddenChat = "incognito chat " + id;
        String hiddenCommand = "trigger incognito_" + id;
        String hiddenServer = "incognito-" + id + ".example.net";
        String visibleChat = "visible chat " + id;
        String visibleCommand = "trigger visible_" + id;
        String lastServerBefore = mc.options.lastMpIp;
        onRenderThread(mc, () -> {
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            chat.handleChatInput("/pp incognito on", true);
            check("/pp incognito on switches it on", PrivacyPlusConfig.incognito);
            chat.handleChatInput(hiddenChat, true);
            chat.handleChatInput("/" + hiddenCommand, true);
            // Direct Connect remembers the typed address when its screen closes
            DirectJoinServerScreen direct = new DirectJoinServerScreen(chat, ok -> { }, new ServerData("", "", ServerData.Type.OTHER));
            mc.gui.setScreen(direct);
            try {
                ((EditBox) field(DirectJoinServerScreen.class, "ipEdit").get(direct)).setValue(hiddenServer);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            mc.gui.setScreen(null);
        });
        Thread.sleep(2000);
        onRenderThread(mc, () -> {
            check("incognito chat still shows on screen", chatLines(mc).stream().anyMatch(l -> l.endsWith(hiddenChat)));
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            chat.handleChatInput("/pp incognito off", true);
            check("/pp incognito off switches it off", !PrivacyPlusConfig.incognito);
            chat.handleChatInput(visibleChat, true);
            chat.handleChatInput("/" + visibleCommand, true);
            mc.gui.setScreen(null);
        });
        Thread.sleep(2000);

        Path dir = mc.gameDirectory.toPath();
        String log = Files.readString(dir.resolve("logs/latest.log"));
        String history = Files.readString(dir.resolve("command_history.txt"));
        check("incognito chat never reached latest.log (client or integrated server)", !log.contains(hiddenChat));
        check("sanity: chat is logged again once incognito is off", log.contains(visibleChat));
        check("incognito command kept out of command_history.txt", !history.contains(hiddenCommand));
        check("sanity: commands are saved again once incognito is off", history.contains(visibleCommand));
        check("Direct Connect address wasn't remembered",
            mc.options.lastMpIp.equals(lastServerBefore) && !Files.readString(dir.resolve("options.txt")).contains(hiddenServer));
    }

    private static int suggestionRequests(Minecraft mc) {
        try {
            return (int) field(ClientSuggestionProvider.class, "pendingSuggestionsId").get(mc.player.connection.getSuggestionsProvider());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static EditBox chatInput(ChatScreen chat) {
        try {
            return (EditBox) field(ChatScreen.class, "input").get(chat);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static LockButton lockButton(Screen screen) {
        return screen.children().stream().filter(LockButton.class::isInstance).map(LockButton.class::cast).findFirst().orElse(null);
    }

    // A real click in the middle of the widget, so button filtering and hit-testing are exercised too
    static void click(AbstractWidget widget, int button) {
        double x = widget.getX() + widget.getWidth() / 2.0;
        double y = widget.getY() + widget.getHeight() / 2.0;
        widget.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
    }

    // A button on the open screen by its label, also inside scrolling lists (the settings' channel rows)
    static Button settingsButton(Minecraft mc, String label) {
        return findButton(mc.gui.screen().children(), label).orElseThrow(() -> new AssertionError("no " + label + " button"));
    }

    private static java.util.Optional<Button> findButton(List<? extends GuiEventListener> children, String label) {
        for (GuiEventListener child : children) {
            if (child instanceof Button b && b.getMessage().getString().equals(label)) {
                return java.util.Optional.of(b);
            }
            if (child instanceof ContainerEventHandler container) {
                java.util.Optional<Button> found = findButton(container.children(), label);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return java.util.Optional.empty();
    }

    static List<String> chatLines(Minecraft mc) {
        return chatComponents(mc).stream().map(Component::getString).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Component> chatComponents(Minecraft mc) {
        try {
            List<GuiMessage> messages = (List<GuiMessage>) field(ChatComponent.class, "allMessages").get(mc.gui.hud.getChat());
            return messages.stream().map(GuiMessage::content).toList();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // After typing "/pp sett": no red usage/error lines, and "settings" offered
    @SuppressWarnings("unchecked")
    private static boolean ppCompletesLocally(ChatScreen chat) {
        try {
            Object suggestions = field(ChatScreen.class, "commandSuggestions").get(chat);
            List<?> usage = (List<?>) field(suggestions.getClass(), "commandUsage").get(suggestions);
            CompletableFuture<Suggestions> pending = (CompletableFuture<Suggestions>) field(suggestions.getClass(), "pendingSuggestions").get(suggestions);
            return usage.isEmpty() && pending != null && pending.isDone()
                && pending.join().getList().stream().anyMatch(s -> s.getText().equals("settings"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static ServerSelectionList serverSelectionList(Minecraft mc) {
        try {
            return (ServerSelectionList) field(JoinMultiplayerScreen.class, "serverSelectionList").get(mc.gui.screen());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static ServerData firstServer(Minecraft mc) {
        return firstEntry(mc).getServerData();
    }

    private static void selectFirstServer(Minecraft mc) {
        serverSelectionList(mc).setSelected(firstEntry(mc));
    }

    private static ServerSelectionList.OnlineServerEntry firstEntry(Minecraft mc) {
        return serverSelectionList(mc).children().stream()
            .filter(ServerSelectionList.OnlineServerEntry.class::isInstance)
            .map(ServerSelectionList.OnlineServerEntry.class::cast)
            .findFirst()
            .orElseThrow();
    }

    void check(String description, boolean passed) {
        checks++;
        LOG.info("SELFTEST {}: {}", passed ? "ok  " : "FAIL", description);
        if (!passed) {
            failures.add(description);
        }
    }

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // Calls a (private) method by name and argument count
    static void invoke(Class<?> owner, String name, Object target, Object... args) {
        try {
            Method method = Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
                .findFirst().orElseThrow(() -> new NoSuchMethodException(name));
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static void onRenderThread(Minecraft mc, Runnable task) {
        mc.submit(task).join();
    }

    static void waitFor(Supplier<Boolean> condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out waiting for the title screen");
            }
            Thread.sleep(250);
        }
        Thread.sleep(2000);
    }
}
