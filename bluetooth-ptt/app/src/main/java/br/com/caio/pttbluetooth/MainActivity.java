package br.com.caio.pttbluetooth;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements PttService.Listener {
    private static final int REQ_PERMISSIONS = 41;
    private static final int REQ_CAMERA = 42;
    private static final String TERMS_VERSION = "2026-11-04-v1";
    private static final String CONSENT_PREFS = "prisma_local_consent";
    private boolean initialized;
    private TextView deviceText;
    private PttService service;
    private boolean bound;
    private Runnable pendingServiceAction;
    private TextView status;
    private TextView identityText;
    private TextView contactsText;
    private Button talkButton;
    private Button hostButton;
    private Button connectButton;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PttService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            Runnable action = pendingServiceAction;
            pendingServiceAction = null;
            if (action != null) action.run();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            setStatus("Serviço PTT desconectado.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String acceptedVersion = getSharedPreferences(CONSENT_PREFS, MODE_PRIVATE)
                .getString("accepted_version", "");
        if (!TERMS_VERSION.equals(acceptedVersion)) showTermsDialog(true);
        else initializeAfterConsent();
    }

    private void initializeAfterConsent() {
        if (initialized || isFinishing()) return;
        initialized = true;
        buildUi();
        refreshDeviceSummary();
        try {
            IdentityStore.Identity identity = IdentityStore.load(this);
            identityText.setText("IDENTIDADE LOCAL\n" + identity.name + "\n" + identity.id.toUpperCase());
            refreshContacts();
        } catch (Exception e) {
            setStatus("Falha ao preparar identidade: " + e.getMessage());
        }
        requestCorePermissions();
    }

    private void showTermsDialog(boolean required) {
        TextView body = new TextView(this);
        body.setText(
                "PRISMA HUB + PTT\nTERMO DE USO E PRIVACIDADE — versão 1.0\n\n" +
                "1. FINALIDADE\n" +
                "O aplicativo reúne os módulos do Hub e o comunicador PTT Bluetooth. A navegação do Hub usa internet e os serviços associados à sua conta. O PTT transmite voz diretamente entre dois aparelhos pareados por Bluetooth Classic.\n\n" +
                "2. MICROFONE E ÁUDIO\n" +
                "O microfone é ativado somente enquanto você mantém o botão PTT pressionado. O áudio é enviado ao outro aparelho pela conexão Bluetooth local; não é enviado ao Hub, Supabase, Cloudflare ou outro servidor. O aplicativo não grava áudio continuamente.\n\n" +
                "3. IDENTIFICAÇÃO DO APARELHO\n" +
                "Após este aceite, o aplicativo consulta localmente fabricante, modelo e versão do Android para selecionar e apresentar um perfil de compatibilidade. Esses dados de modelo não são enviados ao Hub. O aplicativo não consulta IMEI, número de série, contatos, chamadas ou localização. O nome padrão divulgado no QR do PTT é genérico.\n\n" +
                "4. QR E IDENTIDADE\n" +
                "A câmera só será solicitada quando você escolher ler um QR. O QR contém uma identidade pública temporária e dados necessários à confiança entre os dois aparelhos; nunca contém a chave privada. Confira os fingerprints por um canal confiável. O QR não substitui o pareamento Bluetooth feito nas configurações do Android.\n\n" +
                "5. PERMISSÕES E CONFIGURAÇÕES\n" +
                "Depois do aceite, o aplicativo poderá solicitar ao Android acesso a Dispositivos próximos/Bluetooth e ao microfone. A câmera é solicitada apenas para leitura de QR. O Android exibirá suas próprias telas de autorização. O aplicativo não ativa silenciosamente rádios, não altera configurações globais, não usa root, Acessibilidade, administrador do dispositivo ou permissões especiais. Você pode negar permissões, revogá-las nas Configurações ou interromper o uso; os recursos dependentes delas deixarão de funcionar.\n\n" +
                "6. DADOS DO HUB\n" +
                "Ao entrar nos módulos online, autenticação, permissões e dados seguem as regras do Hub e dos serviços que o hospedam. O aceite deste termo não concede acesso a contas, não muda papéis e não compartilha espaços privados. Os módulos online não funcionam integralmente sem conexão.\n\n" +
                "7. LIMITES E RESPONSABILIDADE\n" +
                "O PTT é ponto a ponto e requer testes físicos em dois aparelhos. O desenho criptográfico ainda não passou por auditoria independente; não o use como canal de alta garantia, emergência ou para informações cuja exposição possa causar dano. O Terminal é informativo, pode ter atrasos e não executa ordens nem constitui recomendação de investimento. A reprodução de TV depende das fontes disponíveis e pode abrir um player externo.\n\n" +
                "8. ACEITE\n" +
                "A aceitação é registrada localmente neste aparelho, com a versão e a data, para evitar exibir o termo a cada abertura. Ela não é enviada como telemetria. Ao limpar os dados do aplicativo ou instalar uma versão com termo atualizado, o aceite poderá ser solicitado novamente.\n\n" +
                "Ao continuar, você declara que leu estas condições e autoriza o aplicativo a solicitar, separadamente, as permissões necessárias às funções que escolher usar."
        );
        body.setTextColor(0xFF202A34);
        body.setTextSize(14);
        body.setPadding(24, 16, 24, 16);
        body.setLineSpacing(4f, 1.08f);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(required ? "Antes de configurar o aparelho" : "Termo de uso e privacidade")
                .setView(scroll);
        if (required) {
            builder.setCancelable(false)
                    .setPositiveButton("Li e aceito — continuar", (dialog, which) -> {
                        getSharedPreferences(CONSENT_PREFS, MODE_PRIVATE).edit()
                                .putString("accepted_version", TERMS_VERSION)
                                .putLong("accepted_at_epoch_ms", System.currentTimeMillis())
                                .apply();
                        initializeAfterConsent();
                    })
                    .setNegativeButton("Não aceito", (dialog, which) -> finish());
        } else {
            builder.setPositiveButton("Fechar", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> { if (required) finish(); });
        dialog.show();
    }

    private void refreshDeviceSummary() {
        if (deviceText == null) return;
        String maker = Build.MANUFACTURER == null ? "Fabricante não informado" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Modelo não informado" : Build.MODEL;
        String release = Build.VERSION.RELEASE == null ? "versão não informada" : Build.VERSION.RELEASE;
        boolean bluetooth = getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        boolean camera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        String btState = bluetooth ? "disponível; aguardando permissão/estado do sistema" : "não detectado";
        if (bluetooth && (Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            btState = adapter != null && adapter.isEnabled() ? "disponível e ligado" : "disponível, mas desligado";
        }
        deviceText.setText(
                "APARELHO RECONHECIDO LOCALMENTE\n" + maker + " " + model
                + " • Android " + release + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nBluetooth Classic: " + btState
                + "\nCâmera para QR: " + (camera ? "disponível; será solicitada quando necessária" : "não detectada")
                + "\nPerfil interno: áudio PCM mono 16 kHz / 16-bit; conexão RFCOMM"
                + "\nFabricante/modelo são usados só para compatibilidade neste aparelho."
        );
    }

    private void openAppPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("PRISMA HUB  +  PTT BLUETOOTH");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 60));

        Button hubButton = new Button(this);
        hubButton.setText("ABRIR HUB COMPLETO");
        root.addView(hubButton, new LinearLayout.LayoutParams(-1, 48));
        hubButton.setOnClickListener(v -> startActivity(new Intent(this, HubActivity.class)));

        status = new TextView(this);
        status.setText("Preparando…");
        status.setTextColor(0xFFFFC857);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, 54));

        identityText = new TextView(this);
        identityText.setTextColor(0xFFB8C5D1);
        identityText.setTextSize(12);
        identityText.setGravity(Gravity.CENTER);
        root.addView(identityText, new LinearLayout.LayoutParams(-1, 76));

        deviceText = new TextView(this);
        deviceText.setTextColor(0xFF91A4B7);
        deviceText.setTextSize(12);
        deviceText.setGravity(Gravity.CENTER);
        deviceText.setPadding(8, 8, 8, 8);
        root.addView(deviceText, new LinearLayout.LayoutParams(-1, -2));

        Button showQr = new Button(this);
        showQr.setText("GERAR MEU QR DE IDENTIDADE");
        root.addView(showQr, new LinearLayout.LayoutParams(-1, 52));
        showQr.setOnClickListener(v -> showIdentityQr());

        Button scanQr = new Button(this);
        scanQr.setText("LER QR DO OUTRO APARELHO");
        root.addView(scanQr, new LinearLayout.LayoutParams(-1, 52));
        scanQr.setOnClickListener(v -> scanIdentityQr());

        contactsText = new TextView(this);
        contactsText.setTextColor(0xFFB8C5D1);
        contactsText.setTextSize(13);
        contactsText.setGravity(Gravity.CENTER);
        root.addView(contactsText, new LinearLayout.LayoutParams(-1, 70));

        hostButton = new Button(this);
        hostButton.setText("AGUARDAR CONEXÃO BLUETOOTH");
        root.addView(hostButton, new LinearLayout.LayoutParams(-1, 54));
        hostButton.setOnClickListener(v -> runWithService(() -> service.startHost()));

        connectButton = new Button(this);
        connectButton.setText("CONECTAR CONTATO QR PAREADO");
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, 54));
        connectButton.setOnClickListener(v -> chooseTrustedContact());

        talkButton = new Button(this);
        talkButton.setText("SEGURE PARA FALAR");
        talkButton.setTextSize(18);
        talkButton.setAllCaps(false);
        LinearLayout.LayoutParams talkParams = new LinearLayout.LayoutParams(240, 180);
        talkParams.topMargin = 18;
        talkParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(talkButton, talkParams);
        talkButton.setEnabled(false);
        talkButton.setOnTouchListener((v, event) -> {
            if (service == null) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                service.beginTalking();
                talkButton.setText("TRANSMITINDO…");
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                service.endTalking();
                talkButton.setText("SEGURE PARA FALAR");
                return true;
            }
            return true;
        });

        Button stopButton = new Button(this);
        stopButton.setText("ENCERRAR SESSÃO");
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, 48));
        stopButton.setOnClickListener(v -> {
            if (service != null) service.stopSession();
            talkButton.setEnabled(false);
            talkButton.setText("SEGURE PARA FALAR");
            if (bound) {
                unbindService(serviceConnection);
                bound = false;
            }
            service = null;
        });

        Button bluetoothSettings = new Button(this);
        bluetoothSettings.setText("ABRIR CONFIGURAÇÕES BLUETOOTH");
        root.addView(bluetoothSettings, new LinearLayout.LayoutParams(-1, 46));
        bluetoothSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        Button permissionsButton = new Button(this);
        permissionsButton.setText("REVISAR PERMISSÕES DO APLICATIVO");
        root.addView(permissionsButton, new LinearLayout.LayoutParams(-1, 46));
        permissionsButton.setOnClickListener(v -> openAppPermissionSettings());

        Button termsButton = new Button(this);
        termsButton.setText("LER TERMO DE USO E PRIVACIDADE");
        root.addView(termsButton, new LinearLayout.LayoutParams(-1, 42));
        termsButton.setOnClickListener(v -> showTermsDialog(false));

        TextView note = new TextView(this);
        note.setText("Offline • Bluetooth Classic • QR de identidade • ECDH + AES-GCM\nLeia o QR do outro aparelho nos dois sentidos. Depois, pareie os celulares nas configurações Bluetooth.");
        note.setTextColor(0xFF91A4B7);
        note.setGravity(Gravity.CENTER);
        note.setTextSize(11);
        root.addView(note, new LinearLayout.LayoutParams(-1, 64));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void requestCorePermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        else enableBluetoothIfNeeded();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSIONS) {
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    setStatus("Conceda Bluetooth e microfone para usar o PTT.");
                    return;
                }
            }
            enableBluetoothIfNeeded();
        } else if (requestCode == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) launchScanner();
            else setStatus("Permissão de câmera negada.");
        }
    }

    private boolean hasCorePermissions() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void enableBluetoothIfNeeded() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("Este aparelho não oferece Bluetooth Classic.");
            hostButton.setEnabled(false);
            connectButton.setEnabled(false);
            return;
        }
        try {
            if (!adapter.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            else setStatus("Bluetooth pronto. Troque os QR e pareie os aparelhos.");
        } catch (SecurityException e) {
            setStatus("Permissão Bluetooth não concedida.");
        }
    }

    private void showIdentityQr() {
        try {
            String payload = IdentityStore.createOffer(this);
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600);
            Bitmap bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 600; x++) {
                for (int y = 0; y < 600; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            LinearLayout content = new LinearLayout(this);
            content.setPadding(16, 12, 16, 4);
            content.setOrientation(LinearLayout.VERTICAL);
            TextView instructions = new TextView(this);
            instructions.setText("Convite assinado • válido por 5 minutos\nA outra pessoa deve ler este QR e você também deve ler o QR dela.");
            content.addView(instructions);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            content.addView(image, new LinearLayout.LayoutParams(-1, 320));
            new AlertDialog.Builder(this).setTitle("Minha identidade PTT")
                    .setView(content).setPositiveButton("Fechar", null).show();
        } catch (Exception e) {
            showMessage("Não foi possível gerar o QR: " + e.getMessage());
        }
    }

    private void scanIdentityQr() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Aponte para o QR de identidade PTT");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) confirmScannedPeer(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void confirmScannedPeer(String payload) {
        try {
            IdentityStore.Peer peer = IdentityStore.parseOffer(payload);
            new AlertDialog.Builder(this)
                    .setTitle("Confirmar identidade")
                    .setMessage("Contato: " + peer.name + "\n\nFingerprint:\n"
                            + peer.fingerprint()
                            + "\n\nCompare o código com a outra pessoa por um canal confiável. Só salve se os códigos coincidirem.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Salvar contato", (dialog, which) -> {
                        try {
                            IdentityStore.trust(this, peer);
                            refreshContacts();
                            showMessage("Contato QR salvo. Faça o mesmo no outro aparelho.");
                        } catch (Exception e) {
                            showMessage("Não foi possível salvar: " + e.getMessage());
                        }
                    }).show();
        } catch (Exception e) {
            showMessage("QR recusado: " + e.getMessage());
        }
    }

    private void refreshContacts() {
        try {
            List<IdentityStore.Peer> peers = IdentityStore.peers(this);
            if (peers.isEmpty()) contactsText.setText("CONTATOS CONFIÁVEIS: nenhum");
            else {
                StringBuilder text = new StringBuilder("CONTATOS QR CONFIÁVEIS:\n");
                for (IdentityStore.Peer peer : peers) {
                    text.append(peer.name).append(" • ").append(peer.id.substring(0, 8).toUpperCase()).append("\n");
                }
                contactsText.setText(text.toString().trim());
            }
        } catch (Exception e) {
            contactsText.setText("Falha ao carregar contatos.");
        }
    }

    private void chooseTrustedContact() {
        try {
            List<IdentityStore.Peer> peers = IdentityStore.peers(this);
            if (peers.isEmpty()) {
                showMessage("Leia e salve o QR do outro aparelho primeiro.");
                return;
            }
            String[] labels = new String[peers.size()];
            for (int i = 0; i < peers.size(); i++) {
                IdentityStore.Peer peer = peers.get(i);
                labels[i] = peer.name + " • " + peer.id.substring(0, 8).toUpperCase();
            }
            new AlertDialog.Builder(this).setTitle("Escolha o contato QR")
                    .setItems(labels, (dialog, which) -> chooseBondedDevice(peers.get(which)))
                    .setNegativeButton("Cancelar", null).show();
        } catch (Exception e) {
            showMessage("Falha ao carregar contatos: " + e.getMessage());
        }
    }

    private void chooseBondedDevice(IdentityStore.Peer peer) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                showMessage("Ative o Bluetooth primeiro.");
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            ArrayList<BluetoothDevice> devices = new ArrayList<>(bonded);
            if (devices.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setMessage("Nenhum aparelho pareado pelo Android. Faça o pareamento nas configurações Bluetooth e volte.")
                        .setPositiveButton("Abrir configurações", (d, w) ->
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                        .setNegativeButton("Cancelar", null).show();
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = "Dispositivo Bluetooth"; }
                labels[i] = (name == null ? "Dispositivo Bluetooth" : name) + " • " + device.getAddress();
            }
            new AlertDialog.Builder(this).setTitle("Escolha o Bluetooth de " + peer.name)
                    .setItems(labels, (dialog, which) -> {
                        String address = devices.get(which).getAddress();
                        runWithService(() -> service.connect(address, peer.id));
                    }).setNegativeButton("Cancelar", null).show();
        } catch (SecurityException e) {
            showMessage("Conceda a permissão Dispositivos próximos.");
        }
    }

    private void runWithService(Runnable action) {
        if (!hasCorePermissions()) {
            requestCorePermissions();
            showMessage("Conceda Bluetooth e microfone antes de iniciar o PTT.");
            return;
        }
        if (service != null) {
            action.run();
            return;
        }
        pendingServiceAction = action;
        Intent intent = new Intent(this, PttService.class).setAction(PttService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            if (!bound) bound = bindService(new Intent(this, PttService.class),
                    serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            pendingServiceAction = null;
            showMessage("Não foi possível iniciar o serviço: " + e.getMessage());
        }
    }

    @Override public void onPttStatus(String message, boolean connected, String peerName) {
        runOnUiThread(() -> {
            status.setText(message);
            status.setTextColor(connected ? 0xFF74D99F : 0xFFFFC857);
            talkButton.setEnabled(connected);
            if (!connected) talkButton.setText("SEGURE PARA FALAR");
        });
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onPause() {
        if (service != null) service.endTalking();
        if (talkButton != null) talkButton.setText("SEGURE PARA FALAR");
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (service != null) service.setListener(null);
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }
}