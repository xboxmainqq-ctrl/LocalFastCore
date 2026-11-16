package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetPacket;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Adapts Cloudburst's authenticated NetherNet child channel to NEXORA's
 * transport-neutral connection contract.
 */
final class CloudburstNetherNetTransportConnection extends ChannelInboundHandlerAdapter
        implements TransportConnection, NetherNetTransportIdentityBinding {
    private static final int MAX_UNRELIABLE_PAYLOAD_BYTES = 16_383;

    private final NetherNetChildChannel channel;
    private final String id;
    private final PlayerInfo transportIdentity;
    private final ECPublicKey transportPublicKey;
    private final AtomicBoolean locallyClosed = new AtomicBoolean();
    private final AtomicBoolean closeNotified = new AtomicBoolean();

    private volatile Consumer<TransportMessage> messageListener;
    private volatile Consumer<TransportCloseEvent> closeListener;
    private volatile TransportCloseEvent closeEvent;
    private volatile DeliveryMode nextDeliveryMode = DeliveryMode.RELIABLE_ORDERED;

    CloudburstNetherNetTransportConnection(NetherNetChildChannel channel, PlayerInfo transportIdentity) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.transportIdentity = Objects.requireNonNull(transportIdentity, "transportIdentity");
        String connectionId = channel.attr(NetherNetChildChannel.CONNECTION_ID).get();
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("NetherNet child channel has no signaling connection ID");
        }
        this.id = connectionId;
        try {
            if (!(transportIdentity.clientPublicKey() instanceof ECPublicKey key)
                    || key.getParams() == null
                    || key.getParams().getCurve().getField().getFieldSize() != 384
                    || key.getParams().getOrder().bitLength() != 384) {
                throw new IllegalArgumentException("NetherNet identity must contain a P-384 client key");
            }
            this.transportPublicKey = key;
        } catch (java.security.GeneralSecurityException invalidIdentity) {
            throw new IllegalArgumentException("NetherNet transport identity has no valid client key",
                    invalidIdentity);
        }
        channel.closeFuture().addListener(ignored -> recordClosed());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void setMessageListener(Consumer<TransportMessage> listener) {
        this.messageListener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void setCloseListener(Consumer<TransportCloseEvent> listener) {
        this.closeListener = Objects.requireNonNull(listener, "listener");
        notifyCloseListenerIfReady();
    }

    @Override
    public TransportSendResult send(ByteBuffer payload, DeliveryMode deliveryMode) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deliveryMode, "deliveryMode");
        if (!channel.isOpen()) {
            return TransportSendResult.CLOSED;
        }
        if (!channel.isActive()) {
            return TransportSendResult.BACKPRESSURE;
        }

        ByteBuffer source = payload.asReadOnlyBuffer();
        int length = source.remaining();
        if (length > channel.maxMessageSize()) {
            throw new IllegalArgumentException("NetherNet payload exceeds the channel message limit");
        }
        if (deliveryMode == DeliveryMode.UNRELIABLE && length > MAX_UNRELIABLE_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Unreliable NetherNet payload exceeds one SCTP segment");
        }
        if (!channel.isWritable()) {
            return TransportSendResult.BACKPRESSURE;
        }

        ByteBuf bytes = Unpooled.buffer(length, length);
        bytes.writeBytes(source);
        Object outbound = deliveryMode == DeliveryMode.UNRELIABLE
                ? new NetherNetPacket(bytes, false) : bytes;
        try {
            ChannelFuture write = channel.writeAndFlush(outbound);
            write.addListener(future -> {
                if (!future.isSuccess()) {
                    close();
                }
            });
            return TransportSendResult.ACCEPTED;
        } catch (RuntimeException failure) {
            ReferenceCountUtil.release(outbound);
            return channel.isOpen() ? TransportSendResult.BACKPRESSURE : TransportSendResult.CLOSED;
        }
    }

    @Override
    public boolean matchesAuthenticatedPlayer(BedrockAuthenticatedPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!Objects.equals(transportIdentity.xuid(), player.identityId())
                || !Objects.equals(transportIdentity.displayName(), player.displayName())) {
            return false;
        }
        return MessageDigest.isEqual(
                transportPublicKey.getEncoded(),
                player.clientIdentityPublicKey().getEncoded());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof NetherNetPacket.Delivery delivery) {
            nextDeliveryMode = delivery.reliable()
                    ? DeliveryMode.RELIABLE_ORDERED : DeliveryMode.UNRELIABLE;
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof ByteBuf bytes)) {
            context.fireChannelRead(message);
            return;
        }

        try {
            byte[] copy = new byte[bytes.readableBytes()];
            bytes.getBytes(bytes.readerIndex(), copy);
            DeliveryMode mode = nextDeliveryMode;
            nextDeliveryMode = DeliveryMode.RELIABLE_ORDERED;
            Consumer<TransportMessage> listener = messageListener;
            if (listener == null) {
                close();
                return;
            }
            listener.accept(new TransportMessage(ByteBuffer.wrap(copy), mode));
        } catch (RuntimeException malformedMessage) {
            close();
        } finally {
            ReferenceCountUtil.release(bytes);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        recordClosed();
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        close();
    }

    @Override
    public void close() {
        locallyClosed.set(true);
        channel.close();
    }

    private void recordClosed() {
        closeEvent = new TransportCloseEvent("NetherNet connection closed", !locallyClosed.get());
        notifyCloseListenerIfReady();
    }

    private void notifyCloseListenerIfReady() {
        TransportCloseEvent event = closeEvent;
        Consumer<TransportCloseEvent> listener = closeListener;
        if (event != null && listener != null && closeNotified.compareAndSet(false, true)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // Transport teardown must not be blocked by an observer.
            }
        }
    }
}
