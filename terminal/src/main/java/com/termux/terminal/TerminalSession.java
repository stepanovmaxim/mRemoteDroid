package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session coupled to a remote connection (mRemoteDroid: SSH) instead of a
 * local process. Data from the connection is handed in with {@link #feed}, keystrokes
 * go out through the {@link Transport}. All terminal emulation and callbacks run on the
 * main thread.
 * <p>
 * Based on Termux's TerminalSession (Apache License 2.0); the local pty/subprocess
 * handling was replaced by the transport.
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote end of the terminal. Methods may block; they are called off the main thread. */
    public interface Transport {
        void send(byte[] data, int offset, int count) throws java.io.IOException;

        /** The visible size changed (also called once when emulation starts). */
        void resize(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_CLOSED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Written from the connection's reader thread, read by the main thread. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Written from the main thread due to user interaction, read by the writer thread. */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final Transport mTransport;
    private boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    public TerminalSession(Transport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the remote end of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
        }
        resizeRemote(columns, rows);
    }

    private void resizeRemote(final int columns, final int rows) {
        new Thread("TermSessionResize") {
            @Override
            public void run() {
                try {
                    mTransport.resize(columns, rows);
                } catch (Exception e) {
                    // the connection is going away; the session reports that separately
                }
            }
        }.start();
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Set the terminal emulator's window size and start forwarding keystrokes. */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        new Thread("TermSessionOutputWriter") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        mTransport.send(buffer, 0, bytesToWrite);
                    }
                } catch (Exception e) {
                    // Ignore: the connection reports its own end.
                }
            }
        }.start();
    }

    /** Output from the remote end; may be called from any thread. */
    public void feed(byte[] data, int offset, int count) {
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** The connection ended; shows [message] in the terminal. May be called from any thread. */
    public void onRemoteClosed(String message) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_CLOSED, message));
    }

    /** Write data to the remote end. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Stops forwarding keystrokes; the owner closes the connection itself. */
    public void finishIfRunning() {
        cleanupResources();
    }

    void cleanupResources() {
        synchronized (this) {
            mRunning = false;
        }
        mTerminalToProcessIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[16 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator == null) {
                // output before the view measured the terminal: retry once it is laid out
                sendMessageDelayed(obtainMessage(msg.what, msg.obj), 50);
                return;
            }
            int bytesRead;
            while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
            }
            notifyScreenUpdate();

            if (msg.what == MSG_CLOSED && isRunning()) {
                cleanupResources();
                mProcessToTerminalIOQueue.close();
                String message = msg.obj != null ? (String) msg.obj : "";
                byte[] bytesToWrite = ("\r\n[" + message + "]\r\n").getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
