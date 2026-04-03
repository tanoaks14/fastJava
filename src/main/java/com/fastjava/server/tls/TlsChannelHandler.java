package com.fastjava.server.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Wraps a {@link SocketChannel} with a {@link SSLEngine} to provide transparent
 * TLS encryption/decryption for NIO-based I/O.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>Construct with a blocking-mode {@link SocketChannel} and a server-mode
 * {@link SSLEngine} whose {@link javax.net.ssl.SSLParameters} have already been
 * configured (protocols, ALPN, etc.).</li>
 * <li>Call {@link #doHandshake()} on a worker thread (channel stays in blocking
 * mode).</li>
 * <li>Configure the channel to non-blocking mode and register it with the
 * selector.</li>
 * <li>Use {@link #read(ByteBuffer)} / {@link #write(ByteBuffer)} for data
 * transfer.</li>
 * <li>Call {@link #close()} before closing the channel to send a TLS
 * {@code close_notify}.</li>
 * </ol>
 *
 * <h2>Buffer state convention</h2>
 * {@code netIn} is kept in <em>read mode</em> between calls so that
 * {@code compact()}
 * at the start of {@link #read} correctly preserves any leftover bytes from a
 * previous
 * partial TLS record.
 */
public final class TlsChannelHandler {

    private static final Logger logger = LoggerFactory.getLogger(TlsChannelHandler.class);

    /** Empty buffer used as the plaintext source during handshake WRAP steps. */
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final SocketChannel channel;
    private final SSLEngine engine;

    /** Encrypted bytes received from the network (read mode between calls). */
    private final ByteBuffer netIn;

    /** Encrypted bytes to be written to the network. */
    private final ByteBuffer netOut;

    /** Scratch buffer for plaintext produced during the handshake unwrap steps. */
    private final ByteBuffer appIn;

    /**
     * Plaintext bytes produced during handshake unwrap that must be delivered to
     * the first application reads.
     */
    private byte[] prefetchedAppData = new byte[0];
    private int prefetchedAppOffset;

    private String negotiatedApplicationProtocol = "";

    public TlsChannelHandler(SocketChannel channel, SSLEngine engine) {
        this.channel = channel;
        this.engine = engine;
        SSLSession session = engine.getSession();
        int packetSize = session.getPacketBufferSize();
        int appSize = session.getApplicationBufferSize();
        // Allocate twice the packet size to comfortably hold multi-record reads.
        this.netIn = ByteBuffer.allocateDirect(packetSize * 2);
        this.netIn.flip(); // start in empty read-mode for compact()-first pattern
        this.netOut = ByteBuffer.allocateDirect(packetSize * 2);
        this.appIn = ByteBuffer.allocate(appSize);
    }

    /** Returns the underlying {@link SocketChannel}. */
    public SocketChannel channel() {
        return channel;
    }

    public String negotiatedApplicationProtocol() {
        return negotiatedApplicationProtocol;
    }

    /**
     * @return true when handshake/read buffers already contain data that should be
     *         consumed without waiting for another socket readability event.
     */
    public boolean hasBufferedInput() {
        return (prefetchedAppData.length - prefetchedAppOffset) > 0 || netIn.hasRemaining();
    }

    // -------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------

    /**
     * Performs the TLS handshake synchronously. The channel <b>must</b> be in
     * blocking mode. After this method returns the channel should be switched to
     * non-blocking mode and registered with the NIO selector.
     *
     * @throws IOException if the handshake fails
     */
    public void doHandshake() throws IOException {
        engine.beginHandshake();
        runHandshakeLoop();
        String alpn = engine.getApplicationProtocol();
        negotiatedApplicationProtocol = (alpn == null) ? "" : alpn;
        if (alpn != null && !alpn.isEmpty() && !"http/1.1".equals(alpn)) {
            logger.info("TLS client negotiated ALPN '{}'; protocol handler will be selected by the server", alpn);
        }
    }

    private void runHandshakeLoop() throws IOException {
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
        while (status != SSLEngineResult.HandshakeStatus.FINISHED
                && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            switch (status) {
                case NEED_WRAP -> {
                    netOut.clear();
                    SSLEngineResult result = engine.wrap(EMPTY, netOut);
                    netOut.flip();
                    while (netOut.hasRemaining()) {
                        channel.write(netOut);
                    }
                    status = result.getHandshakeStatus();
                    if (status == SSLEngineResult.HandshakeStatus.FINISHED) {
                        return;
                    }
                }
                case NEED_UNWRAP -> {
                    netIn.compact();
                    channel.read(netIn); // blocking read
                    netIn.flip();
                    appIn.clear();
                    SSLEngineResult result = engine.unwrap(netIn, appIn);
                    stashHandshakePlaintext();
                    status = result.getHandshakeStatus();
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // Need more network data — loop will read again.
                        status = SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
                    }
                    if (status == SSLEngineResult.HandshakeStatus.FINISHED) {
                        return;
                    }
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    status = engine.getHandshakeStatus();
                }
                default -> throw new IOException("Unexpected TLS handshake status: " + status);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data transfer (non-blocking)
    // -------------------------------------------------------------------------

    /**
     * Reads plaintext bytes into {@code appDst}. The channel must be in
     * non-blocking mode.
     *
     * @param appDst destination buffer (plaintext)
     * @return plaintext bytes written to {@code appDst}, {@code 0} if no data
     *         is available yet, or {@code -1} on EOF / TLS close
     * @throws IOException on I/O or TLS error
     */
    public int read(ByteBuffer appDst) throws IOException {
        int prefetchedCopied = drainPrefetchedPlaintext(appDst);
        if (prefetchedCopied > 0) {
            return prefetchedCopied;
        }

        // compact() shifts any leftover (partial TLS record) to the front
        // and switches to write mode so channel.read() can append new bytes.
        netIn.compact();
        int channelBytesRead = channel.read(netIn);
        netIn.flip(); // switch back to read mode

        if (!netIn.hasRemaining()) {
            return channelBytesRead < 0 ? -1 : 0;
        }

        int producedTotal = 0;
        while (netIn.hasRemaining() && appDst.hasRemaining()) {
            SSLEngineResult result = engine.unwrap(netIn, appDst);
            producedTotal += result.bytesProduced();
            switch (result.getStatus()) {
                case OK -> {
                    // Continue draining already-buffered TLS records if possible.
                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                        return producedTotal;
                    }
                }
                case CLOSED -> {
                    return producedTotal > 0 ? producedTotal : -1;
                }
                case BUFFER_UNDERFLOW -> {
                    return producedTotal > 0 ? producedTotal : (channelBytesRead < 0 ? -1 : 0);
                }
                case BUFFER_OVERFLOW ->
                    throw new IOException("TLS appDst buffer overflow — increase request buffer size");
            }
        }
        return producedTotal > 0 ? producedTotal : (channelBytesRead < 0 ? -1 : 0);
    }

    private void stashHandshakePlaintext() {
        if (appIn.position() <= 0) {
            return;
        }
        appIn.flip();
        int produced = appIn.remaining();
        int existing = prefetchedAppData.length - prefetchedAppOffset;
        byte[] merged = new byte[existing + produced];
        if (existing > 0) {
            System.arraycopy(prefetchedAppData, prefetchedAppOffset, merged, 0, existing);
        }
        appIn.get(merged, existing, produced);
        prefetchedAppData = merged;
        prefetchedAppOffset = 0;
    }

    private int drainPrefetchedPlaintext(ByteBuffer appDst) {
        int available = prefetchedAppData.length - prefetchedAppOffset;
        if (available <= 0 || !appDst.hasRemaining()) {
            return 0;
        }
        int toCopy = Math.min(available, appDst.remaining());
        appDst.put(prefetchedAppData, prefetchedAppOffset, toCopy);
        prefetchedAppOffset += toCopy;
        if (prefetchedAppOffset >= prefetchedAppData.length) {
            prefetchedAppData = new byte[0];
            prefetchedAppOffset = 0;
        }
        return toCopy;
    }

    /**
     * Encrypts {@code appSrc} and writes the ciphertext to the channel.
     *
     * @param appSrc plaintext source; its {@code position} is advanced by the
     *               number of consumed bytes
     * @return number of plaintext bytes consumed from {@code appSrc}
     * @throws IOException on I/O or TLS error
     */
    public int write(ByteBuffer appSrc) throws IOException {
        netOut.clear();
        SSLEngineResult result = engine.wrap(appSrc, netOut);
        netOut.flip();
        while (netOut.hasRemaining()) {
            channel.write(netOut);
        }
        return result.bytesConsumed();
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Sends a TLS {@code close_notify} alert (best-effort).
     * The caller is responsible for closing the underlying {@link SocketChannel}.
     */
    public void close() throws IOException {
        engine.closeOutbound();
        try {
            netOut.clear();
            engine.wrap(EMPTY, netOut);
            netOut.flip();
            channel.write(netOut);
        } catch (Exception ignored) {
            // Best-effort: the channel may already be closing.
        }
    }
}
