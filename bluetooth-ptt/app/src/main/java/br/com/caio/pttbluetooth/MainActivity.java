package br.com.caio.pttbluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 41;
    private static final UUID APP_UUID = UUID.fromString("9e7b1001-5f8d-4b72-a9a1-6dcb1000a001");
    private static final String SERVICE_NAME = "Caio PTT";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_BYTES = 640;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean talking = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private BluetoothAdapter adapter;
    private volatile BluetoothSocket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile AudioTrack speaker;
    private volatile AudioRecord microphone;
    private TextView status, peer;
    private Button talkButton;
    private volatile boolean connected = false;
    private volatile boolean destroyed = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("Este aparelho não possui Bluetooth Classic.");
            talkButton.setEnabled(false);
            return;
        }
        requestNeededPermissions();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("PTT  /  BLUETOOTH");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 64));

        status = new TextView(this);
        status.setText("Desconectado");
        status.setTextColor(0xFFFFC857);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, 52));

        peer = new TextView(this);
        peer.setText("Nenhum aparelho conectado");
        peer.setTextColor(0xFFB8C5D1);
        peer.setGravity(Gravity.CENTER);
        root.addView(peer, new LinearLayout.LayoutParams(-1, 48));

        Button host = new Button(this);
        host.setText("AGUARDAR CONEXÃO");
        root.addView(host, new LinearLayout.LayoutParams(-1, 58));
        host.setOnClickListener(v -> startServer());

        Button connect = new Button(this);
        connect.setText("CONECTAR A PAREADO");
        root.addView(connect, new LinearLayout.LayoutParams(-1, 58));
        connect.setOnClickListener(v -> choosePairedDevice());

        talkButton = new Button(this);
        talkButton.setText("PRESSIONE PARA FALAR");
        talkButton.setTextSize(18);
        talkButton.setAllCaps(false);
        LinearLayout.LayoutParams talkLp = new LinearLayout.LayoutParams(260, 260);
        talkLp.topMargin = 44;
        talkLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(talkButton, talkLp);
        talkButton.setEnabled(false);
        talkButton.setOnTouchListener((v, event) -> {
            if (!connected) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) { beginTalking(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                endTalking(); return true;
            }
            return true;
        });

        TextView note = new TextView(this);
        note.setText("Bluetooth Classic • Canal direto • Sem internet\nMantenha o aplicativo aberto nesta versão.");
        note.setTextColor(0xFF91A4B7);
        note.setGravity(Gravity.CENTER);
        note.setTextSize(12);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, 70);
        noteLp.topMargin = 22;
        root.addView(note, noteLp);
        setContentView(root);
    }

    private void requestNeededPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
        else enableBluetoothIfNeeded();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) {
            for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) {
                setStatus("Permissões necessárias não concedidas."); return;
            }
            enableBluetoothIfNeeded();
        }
    }

    private void enableBluetoothIfNeeded() {
        try {
            if (adapter != null && !adapter.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            else setStatus("Bluetooth pronto. Pareie os aparelhos nas configurações.");
        } catch (SecurityException e) { setStatus("Permissão Bluetooth negada."); }
    }

    private void startServer() {
        if (!hasPermissions()) { requestNeededPermissions(); return; }
        closeConnection();
        setStatus("Aguardando outro aparelho…");
        io.execute(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID);
                BluetoothSocket accepted = server.accept();
                if (!destroyed) attachSocket(accepted); else accepted.close();
            } catch (IOException | SecurityException e) {
                if (!destroyed) { showToast("Não foi possível aguardar conexão: " + e.getMessage()); setStatus("Falha ao aguardar conexão."); }
            } finally { if (server != null) try { server.close(); } catch (IOException ignored) {} }
        });
    }

    private void choosePairedDevice() {
        if (!hasPermissions()) { requestNeededPermissions(); return; }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            ArrayList<BluetoothDevice> devices = new ArrayList<>(bonded);
            if (devices.isEmpty()) { showToast("Nenhum aparelho pareado. Pareie nas configurações Bluetooth."); return; }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) labels[i] = safeName(devices.get(i)) + " • " + devices.get(i).getAddress();
            new android.app.AlertDialog.Builder(this).setTitle("Escolha o aparelho pareado")
                .setItems(labels, (dialog, which) -> connectTo(devices.get(which)))
                .setNegativeButton("Cancelar", null).show();
        } catch (SecurityException e) { setStatus("Sem permissão para listar aparelhos pareados."); }
    }

    private String safeName(BluetoothDevice device) {
        try { String n = device.getName(); return n == null ? "Dispositivo Bluetooth" : n; }
        catch (SecurityException e) { return "Dispositivo Bluetooth"; }
    }

    private void connectTo(BluetoothDevice device) {
        closeConnection();
        setStatus("Conectando a " + safeName(device) + "…");
        io.execute(() -> {
            try {
                BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(APP_UUID);
                candidate.connect();
                if (!destroyed) attachSocket(candidate); else candidate.close();
            } catch (IOException | SecurityException e) {
                setStatus("Falha na conexão. Confirme o pareamento e o aparelho aguardando.");
                showToast("Bluetooth: " + e.getMessage());
            }
        });
    }

    private void attachSocket(BluetoothSocket connectedSocket) {
        try {
            socket = connectedSocket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
            connected = true;
            String name = safeName(socket.getRemoteDevice());
            runOnUiThread(() -> {
                peer.setText("Conectado a: " + name);
                setStatus("CONECTADO • canal pronto");
                talkButton.setEnabled(true);
                talkButton.setText("SEGURE PARA FALAR");
            });
            startReceiver();
        } catch (IOException | SecurityException e) {
            setStatus("Não foi possível abrir o canal de áudio.");
            closeConnection();
        }
    }

    private void startReceiver() {
        io.execute(() -> {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, FRAME_BYTES * 4)).setTransferMode(AudioTrack.MODE_STREAM).build();
            speaker = track;
            track.play();
            try {
                DataInputStream in = new DataInputStream(input);
                while (connected && !destroyed) {
                    byte[] frame = PcmFrameCodec.read(in);
                    if (frame == null) break;
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.write(frame, 0, frame.length);
                }
            } catch (IOException e) {
                if (connected && !destroyed) setStatus("Conexão encerrada.");
                closeConnection();
            } finally {
                try { track.pause(); track.flush(); track.stop(); } catch (Exception ignored) {}
                track.release();
                if (speaker == track) speaker = null;
            }
        });
    }

    private void beginTalking() {
        if (!connected || !talking.compareAndSet(false, true)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            talking.set(false); requestNeededPermissions(); return;
        }
        talkButton.setText("TRANSMITINDO…");
        setStatus("VOCÊ ESTÁ FALANDO");
        io.execute(() -> {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 4));
            microphone = recorder;
            try {
                recorder.startRecording();
                byte[] frame = new byte[FRAME_BYTES];
                DataOutputStream out = new DataOutputStream(output);
                while (talking.get() && connected && !destroyed) {
                    int count = recorder.read(frame, 0, frame.length);
                    if (count > 0 && (count & 1) == 0) {
                        synchronized (writeLock) {
                            PcmFrameCodec.write(out, frame, count);
                            out.flush();
                        }
                    }
                }
            } catch (Exception e) {
                if (!destroyed) setStatus("Falha na captura ou transmissão de áudio.");
                talking.set(false);
            } finally {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
                if (microphone == recorder) microphone = null;
                runOnUiThread(() -> {
                    if (connected) { talkButton.setText("SEGURE PARA FALAR"); setStatus("CONECTADO • canal pronto"); }
                });
            }
        });
    }

    private void endTalking() { talking.set(false); }

    private boolean hasPermissions() {
        if (adapter == null) return false;
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void closeConnection() {
        talking.set(false);
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; input = null; output = null;
        runOnUiThread(() -> {
            if (talkButton != null) { talkButton.setEnabled(false); talkButton.setText("PRESSIONE PARA FALAR"); }
            if (peer != null) peer.setText("Nenhum aparelho conectado");
        });
    }

    private void setStatus(String text) { runOnUiThread(() -> { if (status != null) status.setText(text); }); }
    private void showToast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    @Override protected void onDestroy() {
        destroyed = true;
        endTalking();
        closeConnection();
        io.shutdownNow();
        super.onDestroy();
    }
}
