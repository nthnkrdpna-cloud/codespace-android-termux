package br.com.caio.pttbluetooth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PttService extends Service {
    public static final String ACTION_START = "br.com.caio.pttbluetooth.START";
    public static final String ACTION_HOST = "br.com.caio.pttbluetooth.HOST";
    private static final String CHANNEL_ID = "ptt_connection";
    private static final int NOTIFICATION_ID = 71;
    private static final UUID APP_UUID = UUID.fromString("9e7b1001-5f8d-4b72-a9a1-6dcb1000a001");
    private static final String SERVICE_NAME = "Caio PTT";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_BYTES = 640;

    public interface Listener {
        void onPttStatus(String status, boolean connected, String peerName);
    }

    public final class LocalBinder extends Binder {
        public PttService getService() { return PttService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicBoolean talking = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket socket;
    private volatile SecureChannel channel;
    private volatile AudioRecord microphone;
    private volatile boolean connected;
    private volatile boolean listening;
    private volatile boolean destroyed;
    private volatile String connectedPeer = "";
    private IdentityStore.Identity identity;

    @Override public void onCreate() {
        super.onCreate();
        try {
            identity = IdentityStore.load(this);
        } catch (Exception e) {
            publish("Não foi possível carregar a identidade local: " + e.getMessage(), false, "");
        }
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            promoteToForeground();
        } catch (Exception e) {
            publish("O Android recusou o serviço em primeiro plano: " + e.getMessage(), false, "");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HOST.equals(intent.getAction())) startHost();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onPttStatus(
                connected ? "CONECTADO • sessão criptografada" : "Serviço pronto",
                connected, connectedPeer);
    }

    public String getStatus() {
        return connected ? "CONECTADO • sessão criptografada" : (listening ? "Aguardando contato confiável…" : "Desconectado");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Comunicação PTT", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantém o canal Bluetooth Push-to-Talk ativo.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void promoteToForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("PTT Bluetooth ativo")
                .setContentText("O serviço local de comunicação está disponível.")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    public void startHost() {
        stopTransport();
        if (identity == null) {
            publish("Identidade local indisponível.", false, "");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            publish("Ative o Bluetooth antes de aguardar conexão.", false, "");
            return;
        }
        listening = true;
        publish("Aguardando aparelho da lista de contatos QR…", false, "");
        workers.execute(() -> {
            BluetoothServerSocket local = null;
            try {
                local = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID);
                serverSocket = local;
                while (listening && !destroyed) {
                    BluetoothSocket accepted = local.accept();
                    if (!listening || destroyed) {
                        accepted.close();
                        break;
                    }
                    try {
                        authenticateAndAttach(accepted, true, null);
                        listening = false;
                        try { local.close(); } catch (IOException ignored) {}
                        return;
                    } catch (Exception rejected) {
                        try { accepted.close(); } catch (IOException ignored) {}
                        publish("Tentativa rejeitada. Verifique o QR nos dois aparelhos.", false, "");
                    }
                }
            } catch (SecurityException e) {
                publish("Permissão Bluetooth Connect não concedida.", false, "");
            } catch (IOException e) {
                if (listening && !destroyed) publish("Falha no servidor Bluetooth: " + e.getMessage(), false, "");
            } finally {
                if (serverSocket == local) serverSocket = null;
                if (local != null) try { local.close(); } catch (IOException ignored) {}
            }
        });
    }

    public void connect(String address, String expectedPeerId) {
        stopTransport();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            publish("Ative o Bluetooth antes de conectar.", false, "");
            return;
        }
        publish("Conectando pelo Bluetooth…", false, "");
        workers.execute(() -> {
            BluetoothSocket candidate = null;
            try {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                candidate = device.createRfcommSocketToServiceRecord(APP_UUID);
                candidate.connect();
                IdentityStore.Peer expected = IdentityStore.findPeer(this, expectedPeerId);
                if (expected == null) throw new SecurityException("Contato QR não encontrado.");
                authenticateAndAttach(candidate, false, expected);
            } catch (SecurityException e) {
                publish("Conexão recusada: permissão ou identidade inválida.", false, "");
                if (candidate != null) try { candidate.close(); } catch (IOException ignored) {}
            } catch (Exception e) {
                publish("Não foi possível conectar: " + e.getMessage(), false, "");
                if (candidate != null) try { candidate.close(); } catch (IOException ignored) {}
            }
        });
    }

    private void authenticateAndAttach(BluetoothSocket candidate, boolean server,
                                       IdentityStore.Peer expected) throws Exception {
        if (identity == null) throw new IllegalStateException("Identidade local indisponível.");
        socket = candidate;
        SecureChannel secure;
        try {
            secure = SecureChannel.handshake(candidate, server, identity, expected, this);
        } catch (Exception e) {
            if (socket == candidate) socket = null;
            throw e;
        }
        if (destroyed || socket != candidate) {
            candidate.close();
            return;
        }
        channel = secure;
        connected = true;
        connectedPeer = expected == null ? "Contato verificado" : expected.name;
        publish("CONECTADO • sessão criptografada", true, connectedPeer);
        startReceiver(secure, candidate);
    }

    private void startReceiver(SecureChannel secure, BluetoothSocket activeSocket) {
        workers.execute(() -> {
            AudioTrack track = null;
            try {
                int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IOException("Buffer de reprodução indisponível.");
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min * 2, FRAME_BYTES * 8))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("AudioTrack não inicializado.");
                track.play();
                while (connected && !destroyed && socket == activeSocket) {
                    byte[] frame = secure.readFrame();
                    int offset = 0;
                    while (offset < frame.length && connected) {
                        int written = track.write(frame, offset, frame.length - offset);
                        if (written <= 0) throw new IOException("Falha na reprodução de áudio.");
                        offset += written;
                    }
                }
            } catch (Exception e) {
                if (connected && socket == activeSocket && !destroyed) {
                    publish("Canal encerrado: " + e.getMessage(), false, "");
                }
            } finally {
                if (track != null) {
                    try { track.pause(); } catch (Exception ignored) {}
                    try { track.flush(); } catch (Exception ignored) {}
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
                if (socket == activeSocket) stopTransport();
            }
        });
    }

    public void beginTalking() {
        if (!connected || channel == null || !talking.compareAndSet(false, true)) return;
        SecureChannel activeChannel = channel;
        workers.execute(() -> {
            AudioRecord recorder = null;
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IOException("Buffer de captura indisponível.");
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, FRAME_BYTES * 8));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microfone não inicializado.");
                microphone = recorder;
                recorder.startRecording();
                byte[] frame = new byte[FRAME_BYTES];
                while (talking.get() && connected && channel == activeChannel) {
                    int count = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                    if (count == AudioRecord.ERROR_INVALID_OPERATION || count == AudioRecord.ERROR_BAD_VALUE
                            || count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IOException("Falha de captura do microfone (" + count + ").");
                    }
                    if (count > 0 && (count & 1) == 0) activeChannel.writeFrame(frame, count);
                }
            } catch (Exception e) {
                if (connected && talking.get()) publish("Falha ao transmitir áudio: " + e.getMessage(), true, connectedPeer);
            } finally {
                talking.set(false);
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    recorder.release();
                }
                if (microphone == recorder) microphone = null;
                if (connected) publish("CONECTADO • sessão criptografada", true, connectedPeer);
            }
        });
    }

    public void endTalking() {
        talking.set(false);
        AudioRecord current = microphone;
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) {}
        }
    }

    public void stopSession() {
        stopTransport();
        stopForeground(true);
        stopSelf();
        publish("Sessão encerrada.", false, "");
    }

    private synchronized void stopTransport() {
        listening = false;
        talking.set(false);
        AudioRecord currentMic = microphone;
        microphone = null;
        if (currentMic != null) {
            // stop() desbloqueia AudioRecord.read(); o worker libera o recurso no finally.
            try { currentMic.stop(); } catch (Exception ignored) {}
        }
        BluetoothServerSocket currentServer = serverSocket;
        serverSocket = null;
        if (currentServer != null) try { currentServer.close(); } catch (IOException ignored) {}
        BluetoothSocket currentSocket = socket;
        socket = null;
        channel = null;
        connected = false;
        connectedPeer = "";
        if (currentSocket != null) try { currentSocket.close(); } catch (IOException ignored) {}
        publish("Desconectado.", false, "");
    }

    private void publish(String status, boolean isConnected, String peerName) {
        Listener current = listener;
        if (current != null) {
            android.os.Handler main = new android.os.Handler(getMainLooper());
            main.post(() -> {
                Listener latest = listener;
                if (latest != null) latest.onPttStatus(status, isConnected, peerName);
            });
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopTransport();
        workers.shutdownNow();
        super.onDestroy();
    }
}