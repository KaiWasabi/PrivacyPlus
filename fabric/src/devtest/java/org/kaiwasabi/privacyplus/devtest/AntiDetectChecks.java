package org.kaiwasabi.privacyplus.devtest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.DiscardedQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.DiscardedQueryPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.stealth.ChannelShield;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.kaiwasabi.privacyplus.stealth.ProbeShield;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// In-game checks for mod-detection hardening; run near the end of SelfTest
final class AntiDetectChecks {
    private static final Logger LOG = LoggerFactory.getLogger("PrivacyPlus-SelfTest");
    private static final Identifier FAKE_CHANNEL = Identifier.fromNamespaceAndPath("privacyplus_selftest", "mod_channel");

    private AntiDetectChecks() {
    }

    static void run(SelfTest test, Minecraft mc) {
        boolean hideModChannels = PrivacyPlusConfig.hideModChannels;
        boolean spoofClientBrand = PrivacyPlusConfig.spoofClientBrand;
        boolean spoofLanguage = PrivacyPlusConfig.spoofLanguage;
        try {
            PrivacyPlusConfig.hideModChannels = true;
            PrivacyPlusConfig.spoofClientBrand = true;
            PrivacyPlusConfig.spoofLanguage = true;
            // Each part on its own, so one that crashes doesn't take the later checks (and other classes') with it
            guarded(test, "own world check", () -> ownWorldIsNotShielded(test, mc));
            guarded(test, "real server connection check", () -> realServerConnection(test));
            guarded(test, "login query check", () -> loginQuery(test, mc));
            guarded(test, "server pack translation check", () -> serverPackTranslations(test, mc));
        } finally {
            PrivacyPlusConfig.hideModChannels = hideModChannels;
            PrivacyPlusConfig.spoofClientBrand = spoofClientBrand;
            PrivacyPlusConfig.spoofLanguage = spoofLanguage;
        }
    }

    private interface Part {
        void run() throws Exception;
    }

    private static void guarded(SelfTest test, String name, Part part) {
        try {
            part.run();
        } catch (Exception e) {
            LOG.error("{} crashed", name, e);
            test.check(name + " crashed: " + e, false);
        }
    }

    // The single-player world's integrated server is your own: mods that need their server side keep
    // working there, so a mod payload sent through the client's (in-memory) connection must arrive.
    // Counted by a handler in the server's end of the connection, just before the packet handler.
    private static void ownWorldIsNotShielded(SelfTest test, Minecraft mc) throws Exception {
        Connection serverSide = (Connection) SelfTest.field(ServerCommonPacketListenerImpl.class, "connection")
            .get(mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).connection);
        Channel serverChannel = (Channel) SelfTest.field(Connection.class, "channel").get(serverSide);
        List<Identifier> received = new CopyOnWriteArrayList<>();
        serverChannel.pipeline().addBefore("packet_handler", "privacyplus_selftest_counter", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ServerboundCustomPayloadPacket packet) {
                    received.add(packet.payload().type().id());
                }
                super.channelRead(ctx, msg);
            }
        });
        try {
            int changedBefore = ChannelShield.changedCount();
            test.check("sanity: the client's connection to its own world is in memory",
                mc.getConnection().getConnection().isMemoryConnection());
            mc.getConnection().send(new ServerboundCustomPayloadPacket(new DiscardedPayload(FAKE_CHANNEL)));
            long deadline = System.currentTimeMillis() + 5000;
            while (!received.contains(FAKE_CHANNEL) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            test.check("a mod payload reaches your own single-player world (received " + received + ")", received.contains(FAKE_CHANNEL));
            test.check("the shield left the single-player connection alone", ChannelShield.changedCount() == changedBefore);
        } finally {
            serverChannel.pipeline().remove("privacyplus_selftest_counter");
        }
    }

    // A client connection that isn't in memory, like one to a real server. Without encoders in the
    // pipeline, whatever the connection writes lands in the channel's outbound queue as packet objects.
    private static void realServerConnection(SelfTest test) {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        try {
            test.check("sanity: the test connection is up and not in memory", connection.isConnected() && !connection.isMemoryConnection());
            int changedBefore = ChannelShield.changedCount();
            boolean[] listenerCalled = {false};
            connection.send(new ServerboundCustomPayloadPacket(new DiscardedPayload(FAKE_CHANNEL)),
                future -> listenerCalled[0] = future.isSuccess());
            connection.send(new ServerboundCustomPayloadPacket(new DiscardedPayload(Identifier.withDefaultNamespace("register"))));
            test.check("mod payloads never reach a real server: " + outbound(channel), outbound(channel).isEmpty());
            test.check("the sender of a held-back packet still hears it went out", listenerCalled[0]);
            test.check("held-back packets were counted", ChannelShield.changedCount() == changedBefore + 2);

            ServerboundCustomPayloadPacket vanillaBrand = new ServerboundCustomPayloadPacket(new BrandPayload("vanilla"));
            connection.send(vanillaBrand);
            List<Object> sent = outbound(channel);
            test.check("the vanilla brand goes through unchanged", sent.size() == 1 && sent.getFirst() == vanillaBrand);
            connection.send(new ServerboundCustomPayloadPacket(new BrandPayload("fabric")));
            test.check("a modded brand sent by anything else goes out as vanilla", outbound(channel).equals(List.of(vanillaBrand)));

            connection.send(new ServerboundCustomQueryAnswerPacket(3, DiscardedQueryAnswerPayload.INSTANCE));
            test.check("a login answer to a query the server never sent is held back", outbound(channel).isEmpty());

            PrivacyPlusConfig.hideModChannels = false;
            connection.send(new ServerboundCustomPayloadPacket(new DiscardedPayload(FAKE_CHANNEL)));
            test.check("with hideModChannels off, mod payloads go out", outbound(channel).size() == 1);
        } finally {
            PrivacyPlusConfig.hideModChannels = true;
            channel.finishAndReleaseAll();
        }
    }

    private static List<Object> outbound(EmbeddedChannel channel) {
        List<Object> sent = new ArrayList<>();
        for (Object message = channel.readOutbound(); message != null; message = channel.readOutbound()) {
            sent.add(message);
        }
        return sent;
    }

    // Vanilla servers never send login queries; mod loaders and anti-cheat plugins use them to ask about
    // mods. The query arrives like a real one (through the connection to the login listener), is answered
    // like vanilla, the alarm notes it, and a mod answering it as well is held back.
    private static void loginQuery(SelfTest test, Minecraft mc) throws Exception {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        try {
            ServerData server = new ServerData("anti-detect test", "antidetect.selftest.invalid", ServerData.Type.OTHER);
            List<Component> status = new ArrayList<>();
            ClientHandshakePacketListenerImpl login = new ClientHandshakePacketListenerImpl(
                connection, mc, server, null, false, null, status::add, new LevelLoadTracker(), null);
            // setupInboundProtocol would also reconfigure the (here missing) decoder
            SelfTest.field(Connection.class, "packetListener").set(connection, login);
            Identifier channelId = Identifier.fromNamespaceAndPath("fml", "loginwrapper");
            channel.writeInbound(new ClientboundCustomQueryPacket(5, new DiscardedQueryPayload(channelId)));
            // Read once: reading drains the channel
            List<Object> answers = outbound(channel);
            test.check("a login query is answered \"not understood\": " + answers,
                answers.equals(List.of(new ServerboundCustomQueryAnswerPacket(5, null))));
            test.check("the connect screen shows vanilla's negotiating status", status.size() == 1
                && status.getFirst().getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("connect.negotiating"));
            test.check("alarm noticed the login query", ProbeAlarm.events().stream().anyMatch(e -> e.kind() == ProbeAlarm.Kind.LOGIN_QUERY
                && e.server().equals(server.ip) && e.detail().equals("login query " + channelId)));
            connection.send(new ServerboundCustomQueryAnswerPacket(5, DiscardedQueryAnswerPayload.INSTANCE));
            test.check("a mod answering the same query again is held back", outbound(channel).isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // A server pack's own translation keys must come back translated, like on a vanilla client, while mod
    // keys still don't. The pack is pushed the way a server's is (PackSource.SERVER), from a local file.
    private static void serverPackTranslations(SelfTest test, Minecraft mc) throws Exception {
        Path zip = mc.gameDirectory.toPath().resolve("selftest-server-pack.zip");
        writePack(zip);
        UUID packId = UUID.randomUUID();
        DownloadedPackSource packs = mc.getDownloadedPackSource();
        Component welcome = Component.translatable("devtest.server.welcome", "Steve");
        try {
            CompletableFuture<?>[] applied = new CompletableFuture<?>[1];
            SelfTest.onRenderThread(mc, () -> {
                packs.configureForServerControl(mc.getConnection().getConnection(), ServerPackManager.PackPromptStatus.ALLOWED);
                applied[0] = packs.waitForPackFeedback(packId);
                packs.pushLocalPack(packId, zip);
            });
            applied[0].get(60, TimeUnit.SECONDS);
            waitUntil(() -> reloadOverlayGone(mc), 30_000);

            // The client runs in German; a vanilla client reporting en_us uses the pack's English
            test.check("server pack key resolves like vanilla (" + ProbeShield.resolve(welcome) + ")",
                ProbeShield.resolve(welcome).equals("Welcome Steve"));
            test.check("server pack's override of a vanilla key applies",
                ProbeShield.resolve(Component.translatable("gui.cancel")).equals("Server Cancel"));
            test.check("vanilla keys still resolve", ProbeShield.resolve(Component.translatable("gui.done")).equals("Done"));
            test.check("server pack keys are not reported as probes", ProbeShield.probeKeys(welcome).isEmpty());
            PrivacyPlusConfig.spoofLanguage = false;
            boolean german = mc.getLanguageManager().getSelected().equals("de_de");
            test.check("in the real language the pack's " + (german ? "German" : "English") + " is used (" + ProbeShield.resolve(welcome) + ")",
                ProbeShield.resolve(welcome).equals(german ? "Willkommen Steve" : "Welcome Steve"));
            test.check("and its English where it has no translation",
                ProbeShield.resolve(Component.translatable("devtest.server.english_only")).equals("Only in English"));
            PrivacyPlusConfig.spoofLanguage = true;

            // Through the sign editor, the way a server probes
            List<Component> lines = List.of(welcome, Component.translatable("gui.cancel"), Component.translatable("devtest.mod.secret"),
                Component.literal("plain"));
            String[][] sent = new String[1][];
            SelfTest.onRenderThread(mc, () -> {
                try {
                    SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
                    SelfTest.field(SignBlockEntity.class, "frontText").set(sign, new SignText(lines, lines, DyeColor.BLACK, false));
                    sent[0] = (String[]) SelfTest.field(AbstractSignEditScreen.class, "messages")
                        .get(new SignEditScreen(sign, SignTextSlot.FRONT, false));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            List<String> expected = List.of("Welcome Steve", "Server Cancel", "devtest.mod.secret", "plain");
            test.check("sign editor sends " + Arrays.toString(sent[0]) + ", expected " + expected, Arrays.asList(sent[0]).equals(expected));
            test.check("alarm noticed the unknown key but not the server pack's",
                SelfTest.noticed(ProbeAlarm.Kind.SIGN, "unknown key devtest.mod.secret")
                && !SelfTest.noticed(ProbeAlarm.Kind.SIGN, "unknown key devtest.server.welcome"));

            SelfTest.onRenderThread(mc, () -> packs.popPack(packId));
            boolean forgotten = waitUntil(
                () -> ProbeShield.resolve(welcome).equals("devtest.server.welcome") && reloadOverlayGone(mc), 60_000);
            test.check("once the pack is removed its keys are unknown again", forgotten
                && ProbeShield.resolve(Component.translatable("gui.cancel")).equals("Cancel"));
        } finally {
            PrivacyPlusConfig.spoofLanguage = true;
            // Back to how leaving the earlier test server left it: no packs, not allowed, nothing sent anywhere
            SelfTest.onRenderThread(mc, packs::cleanupAfterDisconnect);
            waitUntil(() -> ProbeShield.resolve(welcome).equals("devtest.server.welcome") && reloadOverlayGone(mc), 60_000);
            Files.deleteIfExists(zip);
        }
    }

    // Applying or removing a server pack reloads all resources behind a loading overlay
    private static boolean reloadOverlayGone(Minecraft mc) {
        boolean[] gone = {false};
        SelfTest.onRenderThread(mc, () -> gone[0] = mc.gui.overlay() == null);
        return gone[0];
    }

    private static boolean waitUntil(Supplier<Boolean> condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    // English and German translations in the pack's own namespace, and an override of a vanilla key in
    // minecraft's (within one namespace the server pack, being on top, wins over vanilla)
    private static void writePack(Path zip) throws Exception {
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        Map<String, String> files = Map.of(
            "pack.mcmeta", "{\"pack\": {\"description\": \"PrivacyPlus self-test server pack\", \"min_format\": " + format
                + ", \"max_format\": " + format + "}}",
            "assets/devtest_server/lang/en_us.json",
            "{\"devtest.server.welcome\": \"Welcome %s\", \"devtest.server.english_only\": \"Only in English\"}",
            "assets/devtest_server/lang/de_de.json", "{\"devtest.server.welcome\": \"Willkommen %s\"}",
            "assets/minecraft/lang/en_us.json", "{\"gui.cancel\": \"Server Cancel\"}"
        );
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zipOut.putNextEntry(new ZipEntry(file.getKey()));
                zipOut.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }
}
