package com.example.jetsonbtmonitor;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BluetoothWheelchairClient {
    private static final UUID SERIAL_PORT_PROFILE_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int[] DIRECT_RFCOMM_CHANNELS = {1, 2, 3, 4, 5};

    interface Callback {
        void onConnected(BluetoothDevice device);

        void onDisconnected();

        void onLineReceived(String line);

        void onError(String message, Throwable throwable);
    }

    private final Callback callback;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object socketLock = new Object();
    private final Object writeLock = new Object();

    private BluetoothSocket socket;
    private volatile boolean running;

    BluetoothWheelchairClient(Callback callback) {
        this.callback = callback;
    }

    void connect(BluetoothDevice device) {
        disconnect();
        executor.execute(() -> {
            try {
                BluetoothSocket nextSocket = connectSocket(device);
                synchronized (socketLock) {
                    socket = nextSocket;
                    running = true;
                }
                callback.onConnected(device);
                readLoop(nextSocket);
            } catch (IOException | SecurityException exception) {
                closeCurrentSocket();
                callback.onError("Bluetooth 연결에 실패했습니다.", exception);
            }
        });
    }

    private BluetoothSocket connectSocket(BluetoothDevice device) throws IOException {
        Method method;
        try {
            method = device.getClass().getMethod("createRfcommSocket", int.class);
        } catch (NoSuchMethodException exception) {
            method = null;
        }

        IOException directChannelException = null;
        if (method != null) {
            for (int channel : DIRECT_RFCOMM_CHANNELS) {
                BluetoothSocket directChannelSocket = null;
                try {
                    directChannelSocket = (BluetoothSocket) method.invoke(device, channel);
                    directChannelSocket.connect();
                    return directChannelSocket;
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    IOException wrappedException = new IOException("RFCOMM direct channel connection failed.", exception);
                    if (directChannelException != null) {
                        wrappedException.addSuppressed(directChannelException);
                    }
                    closeQuietly(directChannelSocket);
                    throw wrappedException;
                } catch (IOException exception) {
                    if (directChannelException != null) {
                        exception.addSuppressed(directChannelException);
                    }
                    directChannelException = exception;
                    closeQuietly(directChannelSocket);
                }
            }
        }

        BluetoothSocket uuidSocket = null;
        try {
            uuidSocket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID);
            uuidSocket.connect();
            return uuidSocket;
        } catch (IOException exception) {
            if (directChannelException != null) {
                exception.addSuppressed(directChannelException);
            }
            closeQuietly(uuidSocket);
            throw exception;
        }
    }

    void sendLine(String line) {
        executor.execute(() -> {
            BluetoothSocket currentSocket;
            synchronized (socketLock) {
                currentSocket = socket;
            }
            if (currentSocket == null || !currentSocket.isConnected()) {
                callback.onError("Jetson에 연결되어 있지 않습니다.", null);
                return;
            }

            try {
                synchronized (writeLock) {
                    OutputStream outputStream = currentSocket.getOutputStream();
                    outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (IOException exception) {
                callback.onError("명령 전송에 실패했습니다.", exception);
                disconnect();
            }
        });
    }

    void disconnect() {
        running = false;
        closeCurrentSocket();
    }

    void shutdown() {
        disconnect();
        executor.shutdownNow();
    }

    private void readLoop(BluetoothSocket activeSocket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    callback.onLineReceived(line.trim());
                }
            }
        } catch (IOException exception) {
            if (running) {
                callback.onError("수신 중 연결이 끊겼습니다.", exception);
            }
        } finally {
            closeCurrentSocket();
            callback.onDisconnected();
        }
    }

    private void closeCurrentSocket() {
        BluetoothSocket currentSocket;
        synchronized (socketLock) {
            currentSocket = socket;
            socket = null;
        }

        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (IOException ignored) {
                // Closing a socket can fail after the remote side has already gone away.
            }
        }
    }

    private void closeQuietly(BluetoothSocket socketToClose) {
        if (socketToClose == null) {
            return;
        }
        try {
            socketToClose.close();
        } catch (IOException ignored) {
            // Failed connection attempts can leave sockets that are already closed.
        }
    }
}
