package com.samet.windowstik;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.PackageManager;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class Main extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private EditText ip, mac;
    private volatile int generation;
    private boolean busy;
    private BluetoothControls bluetooth;
    private LinearLayout devicePanel;
    private Button refreshDevices;
    private FrameLayout touchpadArea;
    private FrameLayout devicePanelArea;
    private LinearLayout deviceRows;
    private Runnable bluetoothPermissionAction;
    private boolean scanStarted;
    private boolean chooserDismissed;
    private boolean enableRequested;
    private boolean choosingDevice;
    private TextView deviceStatus;
    private BluetoothDevice pairingDevice;
    private boolean showSavedDevices;
    private boolean wasConnected;
    private boolean connectionLost;
    private final Runnable scanAfterConnectionLoss = this::showDevicesAfterConnectionLoss;

    private void showDevicesAfterConnectionLoss() {
        if (!visible || !connectionLost || bluetooth == null || bluetooth.connected()) return;
        showSavedDevices = true;
        refreshBluetooth();
    }
    private ScrollView startPanel;
    private boolean visible;
    private View keyboardInput;

    private static class Target {
        final String ip, mac;
        Target(String ip, String mac) { this.ip = ip; this.mac = mac; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        home();
        checkForUpdate();
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                java.net.URL endpoint = new java.net.URL(
                        "https://api.github.com/repos/sametyamanxx/WINDOWS-TIK/releases/latest");
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                StringBuilder json = new StringBuilder();
                try (java.io.Reader reader = new java.io.InputStreamReader(
                        connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        json.append(buffer, 0, count);
                        if (json.length() > 262144) return;
                    }
                } finally {
                    connection.disconnect();
                }
                org.json.JSONObject release = new org.json.JSONObject(json.toString());
                String tag = release.getString("tag_name");
                if (!tag.matches("v[0-9]{1,8}(\\.[0-9]{1,8}){0,2}")
                        || !isNewer(tag.substring(1), installedVersion())) return;
                String filename = "Windows-Tik-" + tag + ".apk";
                String download = "https://github.com/sametyamanxx/WINDOWS-TIK/releases/download/"
                        + tag + "/" + filename;
                org.json.JSONArray assets = release.getJSONArray("assets");
                for (int index = 0; index < assets.length(); index++) {
                    org.json.JSONObject asset = assets.getJSONObject(index);
                    if (download.equals(asset.optString("browser_download_url"))) {
                        String digest = asset.optString("digest");
                        runOnUiThread(() -> showUpdate(tag, download, digest));
                        return;
                    }
                }
            } catch (java.io.IOException | org.json.JSONException | NumberFormatException ignored) {
                // Offline or unavailable release data must not interrupt the touchpad.
            }
        }, "release-check").start();
    }

    private static boolean isNewer(String remote, String installed) {
        if (installed == null || installed.isEmpty()) return false;
        String[] latest = remote.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < Math.max(latest.length, current.length); i++) {
            int next = i < latest.length ? Integer.parseInt(latest[i]) : 0;
            int old = i < current.length ? Integer.parseInt(current[i]) : 0;
            if (next != old) return next > old;
        }
        return false;
    }

    private static final int UPDATE_INSTALL_PERMISSION = 46;
    private String pendingUpdateUrl;
    private String pendingUpdateDigest;

    private void showUpdate(String tag, String download, String digest) {
        if (isFinishing() || isDestroyed() || !visible) return;
        new AlertDialog.Builder(this)
            .setTitle("Yeni sürüm " + tag)
            .setMessage("Güncelleme uygulama içinde indirilir. Kurulumu Android ekranında onayla.")
            .setNegativeButton("Daha sonra", null)
            .setPositiveButton("Güncelle", (dialog, which) -> {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    pendingUpdateUrl = download;
                    pendingUpdateDigest = digest;
                    Toast.makeText(this, "Güncelleme için bu kaynaktan kuruluma izin ver.", Toast.LENGTH_LONG).show();
                    try {
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName())), UPDATE_INSTALL_PERMISSION);
                    } catch (ActivityNotFoundException ignored) { }
                    return;
                }
                AppUpdate.download(this, download, digest);
            }).show();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        page.post(() -> { if (visible) withBluetooth(this::startBluetooth); });
    }

    private void startBluetooth() {
        if (bluetooth == null) return;
        if (!bluetooth.enabled()) {
            if (!enableRequested) {
                enableRequested = true;
                bluetooth.start();
            }
            return;
        }
        bluetooth.resume();
        refreshBluetooth();
    }

    private BluetoothControls dragConnection;

    private void releaseDrag() {
        if (dragConnection != null) {
            dragConnection.move(0, 0, 0, 0);
            dragConnection = null;
        }
    }

    @Override protected void onPause() {
        releaseDrag();
        super.onPause();
    }

    @Override protected void onStop() {
        visible = false;
        ui.removeCallbacks(scanAfterConnectionLoss);
        if (pairingDevice != null) {
            super.onStop();
            return;
        }
        scanStarted = false;
        showSavedDevices = false;
        wasConnected = false;
        connectionLost = false;
        chooserDismissed = false;
        choosingDevice = false;
        closeDevicePanel();
        closeWindowsMenu();
        if (keyboardInput != null) keyboardInput.clearFocus();
        if (bluetooth != null) {
            bluetooth.close();
            bluetooth = null;
        }
        super.onStop();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private GradientDrawable panel(int fill, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(fill));
        background.setCornerRadius(dp(radius));
        if (fill == R.color.surface || fill == R.color.pad || fill == R.color.chooser) background.setStroke(dp(1), getColor(R.color.line));
        return background;
    }

    private void styleButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setLetterSpacing(0);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int fill = primary ? R.color.accent : R.color.surface;
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{getColor(R.color.secondary), getColor(primary ? R.color.on_accent : R.color.ink)}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), panel(fill, 6), null));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getColor(R.color.ink));
        text.setPadding(0, dp(6), 0, dp(6));
        if (size >= 24) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (size <= 14) text.setTextColor(getColor(R.color.secondary));
        return text;
    }

    private Target savedTarget() {
        SharedPreferences saved = getSharedPreferences("pc", MODE_PRIVATE);
        return new Target(saved.getString("ip", ""),
            saved.getString("mac", "").replace(":", "").replace("-", ""));
    }

    private void home() { trackpad(); }

    private void chooseDevice(BluetoothDevice device) {
        connectionLost = false;
        ui.removeCallbacks(scanAfterConnectionLoss);
        choosingDevice = true;
        if (deviceStatus != null) deviceStatus.setText("Eşleştirme başlatılıyor…");
        if (device.getBondState() == BluetoothDevice.BOND_NONE
                && (Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == PackageManager.PERMISSION_GRANTED)) {
            pairingDevice = device;
            bluetooth.pauseDiscovery();
            Intent request = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            request.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 90);
            startActivityForResult(request, 45);
        } else {
            bluetooth.connect(device);
        }
    }

    private void ensureBluetooth() {
        if (bluetooth == null) bluetooth = new BluetoothControls(this, this::refreshBluetooth);
    }

    private void withBluetooth(Runnable action) {
        if (!bluetoothPermissionsGranted()) {
            bluetoothPermissionAction = action;
            requestPermissions(bluetoothPermissions(), 43);
            return;
        }
        ensureBluetooth();
        action.run();
    }

    private void refreshBluetooth() {
        if (!visible || bluetooth == null) return;
        boolean connected = bluetooth.connected();
        if (wasConnected && !connected) {
            connectionLost = true;
            showSavedDevices = false;
            chooserDismissed = false;
            choosingDevice = false;
            scanStarted = false;
            ui.postDelayed(scanAfterConnectionLoss, 8000);
            Toast.makeText(this, "Bluetooth bağlantısı koptu. Yeniden bağlanılıyor…", Toast.LENGTH_LONG).show();
        }
        wasConnected = connected;
        if (!bluetooth.enabled()) {
            if (devicePanel != null) {
                deviceStatus.setText(bluetooth.status());
                refreshDevices.setEnabled(false);
            }
            return;
        }
        if (connected) {
            ui.removeCallbacks(scanAfterConnectionLoss);
            connectionLost = false;
            bluetooth.stopScan();
            closeDevicePanel();
            return;
        }
        boolean savedHost = bluetooth.hasSavedHost();
        if (savedHost && !showSavedDevices) return;
        if ((!savedHost || showSavedDevices) && !scanStarted && !choosingDevice && !chooserDismissed) {
            scanStarted = true;
            bluetooth.scan();
        }
        if (chooserDismissed) return;
        if (devicePanel == null) {
            LinearLayout panelContent = new LinearLayout(this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = Math.min(MeasureSpec.getSize(widthSpec), dp(460));
                    int availableHeight = MeasureSpec.getSize(heightSpec);
                    int height = Math.min(availableHeight, Math.max(dp(260), availableHeight * 84 / 100));
                    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                }
            };
            panelContent.setOrientation(LinearLayout.VERTICAL);
            panelContent.setPadding(dp(16), dp(12), dp(16), dp(12));
            panelContent.setBackground(panel(R.color.chooser, 12));
            TextView title = text("Yakındaki cihazlar", 24);
            title.setGravity(Gravity.CENTER);
            panelContent.addView(title);
            deviceStatus = text("", 14);
            deviceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            LinearLayout statusRow = new LinearLayout(this);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            statusRow.addView(deviceStatus, new LinearLayout.LayoutParams(0, -2, 1));
            refreshDevices = new Button(this);
            stylePanelButton(refreshDevices, R.color.save_button, R.color.on_save_button);
            refreshDevices.setMinWidth(dp(60));
            refreshDevices.setMinimumWidth(dp(60));
            refreshDevices.setMinHeight(dp(48));
            refreshDevices.setMinimumHeight(dp(48));
            refreshDevices.setPadding(dp(10), 0, dp(10), 0);
            refreshDevices.setBackground(new android.graphics.drawable.InsetDrawable(
                refreshDevices.getBackground(), 0, dp(7), 0, dp(7)));
            refreshDevices.setText("Yenile");
            refreshDevices.setContentDescription("Yakındaki cihazları yeniden tara");
            refreshDevices.setOnClickListener(v -> {
                if (bluetooth == null || !bluetooth.enabled() || bluetooth.connected()
                        || bluetooth.scanning() || pairingDevice != null) return;
                bluetooth.scan();
            });
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, -2);
            refreshParams.setMarginStart(dp(8));
            statusRow.addView(refreshDevices, refreshParams);
            panelContent.addView(statusRow);
            deviceRows = new LinearLayout(this);
            deviceRows.setOrientation(LinearLayout.VERTICAL);
            deviceRows.setPadding(0, dp(4), 0, dp(8));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(deviceRows);
            panelContent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            devicePanel = panelContent;
            devicePanel.setClickable(true);
            FrameLayout.LayoutParams deviceLayout = new FrameLayout.LayoutParams(
                -1, -2, Gravity.CENTER);
            deviceLayout.setMargins(dp(14), dp(12), dp(14), dp(12));
            devicePanelArea.addView(devicePanel, deviceLayout);
        }
        refreshDevices.setEnabled(!bluetooth.scanning() && pairingDevice == null);
        deviceStatus.setText(connectionLost
            ? (bluetooth.scanning() ? "Bağlantı koptu. Yakındaki cihazlar aranıyor…" : "Bağlantı koptu. Yeniden bağlanmak için bilgisayarını seç.")
            : bluetooth.scanning() ? "Yakındaki cihazlar aranıyor…" : bluetooth.status());
        deviceRows.removeAllViews();
        java.util.List<BluetoothDevice> devices = bluetooth.devices();
        if (devices.isEmpty()) {
            deviceRows.addView(text(bluetooth.scanning() ? "Cihazlar aranıyor…" : "Bilgisayarında Bluetooth cihaz ekleme ekranını aç.", 14));
        }
        for (BluetoothDevice device : devices) {
            String name = device.getName() == null ? device.getAddress() : device.getName();
            menuAction(deviceRows, name, () -> chooseDevice(device));
        }
    }

    private void closeDevicePanel() {
        if (devicePanel == null) return;
        devicePanelArea.removeView(devicePanel);
        devicePanel = null;
        refreshDevices = null;
        deviceStatus = null;
        deviceRows = null;
        chooserDismissed = bluetooth == null || !bluetooth.connected();
        if (visible && bluetooth != null) bluetooth.cancelAttempt();
    }

    private boolean closeOpenPanel() {
        if (startPanel != null) {
            closeWindowsMenu();
            return true;
        }
        if (devicePanel != null) {
            closeDevicePanel();
            return true;
        }
        return false;
    }

    private void showKeyboard() {
        if (bluetooth == null || !bluetooth.connected()) {
            Toast.makeText(this, "Önce bilgisayara bağlan.", Toast.LENGTH_SHORT).show();
            return;
        }
        closeWindowsMenu();
        keyboardInput.requestFocus();
        InputMethodManager ime = getSystemService(InputMethodManager.class);
        ime.restartInput(keyboardInput);
        ime.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private View keyboardInputView() {
        View input = new View(this) {
            @Override public boolean onCheckIsTextEditor() { return true; }

            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(
                    android.view.inputmethod.EditorInfo info) {
                info.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                info.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
                return new android.view.inputmethod.BaseInputConnection(this, true) {
                    @Override public boolean commitText(CharSequence text, int cursor) {
                        String[] lines = text.toString().split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (i > 0 && !sendKey(0x28)) return false;
                            if (!lines[i].isEmpty() && (bluetooth == null || !bluetooth.connected()
                                    || !bluetooth.type(java.text.Normalizer.normalize(lines[i], java.text.Normalizer.Form.NFC),
                                        getPreferences(MODE_PRIVATE).getBoolean("turkishQ", true)))) {
                                Toast.makeText(Main.this, "Karakter gönderilemedi. Bağlantıyı ve klavye düzenini kontrol et.",
                                    Toast.LENGTH_SHORT).show();
                                return false;
                            }
                        }
                        return true;
                    }

                    @Override public boolean performEditorAction(int action) { return sendKey(0x28); }

                    @Override public boolean sendKeyEvent(KeyEvent event) {
                        int code = event.getKeyCode();
                        if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_DEL) {
                            return super.sendKeyEvent(event);
                        }
                        return event.getAction() != KeyEvent.ACTION_DOWN
                            || sendKey(code == KeyEvent.KEYCODE_ENTER ? 0x28 : 0x2a);
                    }

                    @Override public boolean deleteSurroundingText(int before, int after) {
                        for (int i = 0; i < before; i++) if (!sendKey(0x2a)) return false;
                        for (int i = 0; i < after; i++) if (!sendKey(0x4c)) return false;
                        return true;
                    }
                };
            }
        };
        input.setFocusableInTouchMode(true);
        input.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return input;
    }

    private final class TwoFingerGesture {
        private float span;
        private float centerY;
        private boolean zooming;
        private boolean scrolling;

        void begin(MotionEvent event) {
            int first = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    && event.getActionIndex() == 0 ? 1 : 0;
            int second = first + 1;
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    && second == event.getActionIndex()) second++;
            span = span(event, first, second);
            centerY = (event.getY(first) + event.getY(second)) / 2;
            zooming = false;
            scrolling = false;
        }

        void move(MotionEvent event) {
            if (bluetooth == null || startPanel != null || devicePanel != null) return;
            float currentSpan = span(event, 0, 1);
            float currentCenter = (event.getY(0) + event.getY(1)) / 2;
            float spanChange = currentSpan - span;
            float verticalChange = currentCenter - centerY;
            // Keep the chosen action until the fingers lift, so zoom never also scrolls.
            if (!scrolling && Math.abs(spanChange) >= dp(24)
                    && (zooming || Math.abs(spanChange) >= Math.abs(verticalChange))) {
                zooming = true;
                bluetooth.zoomPage(spanChange > 0);
                span = currentSpan;
            } else if (!zooming && Math.abs(verticalChange) >= dp(scrolling ? 12 : 20)
                    && (scrolling || Math.abs(verticalChange) > Math.abs(spanChange))) {
                scrolling = true;
                int wheel = Math.max(-127, Math.min(127, (int) (verticalChange / dp(12))));
                bluetooth.move(0, 0, wheel);
                centerY += wheel * dp(12);
            }
        }

        private float span(MotionEvent event, int first, int second) {
            return (float) Math.hypot(event.getX(first) - event.getX(second),
                    event.getY(first) - event.getY(second));
        }
    }

    private String installedVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version != null && version.startsWith("v") ? version.substring(1) : version;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private boolean sendKey(int code) {
        return bluetooth != null && bluetooth.connected() && bluetooth.key(code);
    }

    private String[] bluetoothPermissions() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE"}
            : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private boolean bluetoothPermissionsGranted() {
        for (String permission : bluetoothPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 43) return;
        Runnable action = bluetoothPermissionAction;
        bluetoothPermissionAction = null;
        if (bluetoothPermissionsGranted() && action != null) {
            ensureBluetooth();
            action.run();
        } else {
            Toast.makeText(this, "Bluetooth taraması için cihaz izni gerekiyor.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UPDATE_INSTALL_PERMISSION) {
            String url = pendingUpdateUrl;
            String digest = pendingUpdateDigest;
            pendingUpdateUrl = null;
            pendingUpdateDigest = null;
            if (url != null && getPackageManager().canRequestPackageInstalls())
                AppUpdate.download(this, url, digest);
            return;
        }

        if (request == 44 && result == RESULT_OK) startBluetooth();
        if (request == 45 && pairingDevice != null) {
            BluetoothDevice device = pairingDevice;
            pairingDevice = null;
            if (result != RESULT_CANCELED) {
                withBluetooth(() -> {
                    bluetooth.connect(device);
                });
            } else {
                choosingDevice = false;
                bluetooth.cancelAttempt();
                scanStarted = false;
                refreshBluetooth();
            }
        }
    }

    private Target save() {
        String addr = ip.getText().toString().trim();
        String hardware = normalizedMac(mac.getText().toString());
        String[] octets = addr.split("\\.", -1);
        boolean valid = octets.length == 4;
        for (String octet : octets) valid &= octet.matches("0|[1-9][0-9]{0,2}") && Integer.parseInt(octet) <= 255;
        ip.setError(valid ? null : "IPv4 adresini 192.168.1.100 biçiminde gir.");
        mac.setError(hardware.isEmpty() ? "Geçersiz format. Örnek: AA-BB-CC-DD-EE-FF" : null);
        if (!valid || hardware.isEmpty()) {
            (valid ? mac : ip).requestFocus();
            return null;
        }
        String formatted = formattedMac(hardware);
        ip.setText(addr);
        mac.setText(formatted);
        getSharedPreferences("pc", MODE_PRIVATE).edit().putString("ip", addr)
            .putString("mac", formatted).remove("key").apply();
        return new Target(addr, hardware);
    }

    private static String normalizedMac(String value) {
        String address = value.trim();
        if (!address.matches("(?i)([0-9a-f]{12}|[0-9a-f]{2}([:-])(?:[0-9a-f]{2}\\2){4}[0-9a-f]{2})")) return "";
        return address.replace(":", "").replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static String macDigits(String value) {
        String digits = value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(java.util.Locale.ROOT);
        return digits.substring(0, Math.min(12, digits.length()));
    }

    private static String formattedMac(String address) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < address.length(); i++) {
            formatted.append(address.charAt(i));
            int count = i + 1;
            if (count % 2 == 0 && count < 12) formatted.append('-');
        }
        return formatted.toString();
    }

    private static void wake(Target target) throws IOException {
        byte[] packet = new byte[102];
        java.util.Arrays.fill(packet, 0, 6, (byte) 255);
        for (int i = 6; i < packet.length; i++) {
            int offset = (i - 6) % 6 * 2;
            packet[i] = (byte) Integer.parseInt(target.mac.substring(offset, offset + 2), 16);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (String address : new String[]{"255.255.255.255", target.ip}) {
                try {
                    socket.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(address), 9));
                } catch (IOException e) {
                    if (address.equals("255.255.255.255")) throw e;
                }
            }
        }
    }

    private void wakeComputer(Target target) {
        if (target.ip.isEmpty() || target.mac.isEmpty()) { Toast.makeText(this, "Önce IP ve MAC adreslerini kaydet.", Toast.LENGTH_SHORT).show(); return; }
        if (busy) return;
        busy = true;
        int attempt = ++generation;

        network.execute(() -> {
            String message;
            try {
                wake(target);
                message = "Uyandırma paketi gönderildi.";
            } catch (IOException e) {
                message = "Uyandırma paketi gönderilemedi.";
            }
            String result = message;
            ui.post(() -> {
                if (generation != attempt || isFinishing()) return;
                busy = false;
                if (visible) Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                if (bluetooth != null) retryBluetooth(attempt, 15);
            });
        });
    }

    private void retryBluetooth(int attempt, int retries) {
        if (generation != attempt || retries == 0 || bluetooth == null || bluetooth.connected()) return;
        bluetooth.reconnect();
        ui.postDelayed(() -> retryBluetooth(attempt, retries - 1), 4000);
    }

    private void trackpad() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.canvas));
        keyboardInput = keyboardInputView();
        page.addView(keyboardInput, new LinearLayout.LayoutParams(1, 1));
        setContentView(page);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.windows_menu);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(27), dp(27));
        logoParams.setMarginEnd(dp(10));
        header.addView(logo, logoParams);
        TextView title = text("WİNDOWS TIK", 22);
        title.setSingleLine();
        title.setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(80));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(8), 0, dp(8), 0);
        header.addView(title, new LinearLayout.LayoutParams(-2, dp(36)));
        header.setGravity(Gravity.CENTER);
        TextView pad = new TextView(this);
        pad.setText("Touchpad");
        pad.setTextColor(getColor(R.color.secondary));
        pad.setTextSize(14);
        pad.setGravity(Gravity.CENTER);
        pad.setBackgroundColor(getColor(R.color.canvas));
        pad.setContentDescription("Dokunmatik fare alanı");
        touchpadArea = new FrameLayout(this);
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-1, 0, 1);
        page.addView(touchpadArea, padParams);
        touchpadArea.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        devicePanelArea = new FrameLayout(this);
        FrameLayout.LayoutParams deviceAreaParams = new FrameLayout.LayoutParams(-1, -1);
        deviceAreaParams.topMargin = dp(60);
        touchpadArea.addView(devicePanelArea, deviceAreaParams);
        touchpadArea.addView(header, new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP));
        page.setOnClickListener(v -> closeOpenPanel());
        pad.setOnClickListener(v -> click(1));
        pad.setOnLongClickListener(v -> {
            click(2);
            return true;
        });
        boolean[] multipleFingers = {false};
        boolean[] longPress = {false};
        boolean[] dragMoved = {false};
        float[] dragPosition = new float[2];
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                return !multipleFingers[0] && pad.performClick();
            }

            @Override public void onLongPress(MotionEvent event) {
                if (!multipleFingers[0]) longPress[0] = true;
            }

            @Override public boolean onScroll(MotionEvent start, MotionEvent event, float dx, float dy) {
                if (bluetooth == null || startPanel != null || devicePanel != null) return true;
                if (!multipleFingers[0]) {
                    bluetooth.move(Math.round(-dx), Math.round(-dy), 0);
                }
                return true;
            }
        });
        gestures.setOnDoubleTapListener(null);
        TwoFingerGesture twoFingerGesture = new TwoFingerGesture();
        pad.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                releaseDrag();
                multipleFingers[0] = false;
                longPress[0] = false;
                dragMoved[0] = false;
                dragPosition[0] = event.getX();
                dragPosition[1] = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                multipleFingers[0] = true;
                releaseDrag();
                if (event.getPointerCount() == 2) twoFingerGesture.begin(event);
            } else if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() == 3) {
                twoFingerGesture.begin(event);
            } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 2) {
                twoFingerGesture.move(event);
            } else if (action == MotionEvent.ACTION_MOVE && longPress[0] && !multipleFingers[0]) {
                float dx = event.getX() - dragPosition[0];
                float dy = event.getY() - dragPosition[1];
                if (!dragMoved[0] && Math.hypot(dx, dy) >= touchSlop) {
                    dragMoved[0] = true;
                    if (bluetooth != null && startPanel == null && devicePanel == null
                            && bluetooth.move(0, 0, 0, 1)) {
                        dragConnection = bluetooth;
                    }
                }
                if (dragConnection != null && dragConnection.connected()) {
                    int x = Math.round(dx);
                    int y = Math.round(dy);
                    if (x != 0 || y != 0) {
                        dragConnection.move(x, y, 0, 1);
                        dragPosition[0] = event.getX();
                        dragPosition[1] = event.getY();
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                releaseDrag();
                if (action == MotionEvent.ACTION_UP && longPress[0] && !multipleFingers[0] && !dragMoved[0]) {
                    pad.performLongClick();
                }
            }
            gestures.onTouchEvent(event);
            return true;
        });
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        footer.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(50));
        page.addView(footer, footerParams);
        footer.setBackgroundColor(getColor(R.color.surface));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        page.requestApplyInsets();

        ImageButton windows = new ImageButton(this);
        windows.setImageResource(R.drawable.windows_menu);
        windows.setContentDescription("Windows menüsü");
        windows.setScaleType(ImageView.ScaleType.CENTER);
        windows.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        windows.setOnClickListener(v -> windowsMenu());
        footer.addView(windows, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton keyboard = new ImageButton(this);
        keyboard.setImageResource(R.drawable.keyboard);
        keyboard.setImageTintList(ColorStateList.valueOf(getColor(R.color.ink)));
        keyboard.setContentDescription("Telefon klavyesini aç");
        keyboard.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        keyboard.setOnClickListener(v -> showKeyboard());
        keyboard.setOnLongClickListener(v -> {
            android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            new android.app.AlertDialog.Builder(this)
                .setTitle("Windows klavye düzeni")
                .setSingleChoiceItems(new String[]{"Türkçe Q", "İngilizce (ABD)"},
                    prefs.getBoolean("turkishQ", true) ? 0 : 1, (dialog, choice) -> {
                        prefs.edit().putBoolean("turkishQ", choice == 0).apply();
                        dialog.dismiss();
                    })
                .setNegativeButton("Vazgeç", null)
                .show();
            return true;
        });
        footer.addView(keyboard, new LinearLayout.LayoutParams(dp(48), dp(48)));


    }

    private void windowsMenu() {
        if (startPanel != null) {
            closeWindowsMenu();
            return;
        }
        closeDevicePanel();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(8));
        content.setFocusableInTouchMode(true);
        LinearLayout addresses = new LinearLayout(this);
        addresses.setOrientation(1);
        content.addView(addresses, new LinearLayout.LayoutParams(-1, -2));
        Target target = savedTarget();
        mac = addressField(addresses, "Physical address", target.mac, true);
        mac.setHint("AA-BB-CC-DD-EE-FF");
        ip = addressField(addresses, "IPv4 adres", target.ip, false);
        ip.setHint("192.168.1.100");
        LinearLayout addressButtons = new LinearLayout(this);
        addresses.addView(addressButtons, new LinearLayout.LayoutParams(-1, -2));
        panelButton(addressButtons, "Kaydet", R.color.save_button, R.color.on_save_button, () -> {
            if (save() != null) Toast.makeText(this, "Adresler kaydedildi.", Toast.LENGTH_SHORT).show();
        });
        panelButton(addressButtons, "Sil", R.color.delete_button, R.color.on_delete_button, () -> {
            getSharedPreferences("pc", MODE_PRIVATE).edit().remove("ip").remove("mac").apply();
            ip.setText(""); mac.setText("");
        });
        ImageButton wake = new ImageButton(this);
        wake.setImageResource(R.drawable.power);
        wake.setScaleType(ImageView.ScaleType.CENTER);
        wake.setContentDescription("Bilgisayarı aç");
        wake.setImageTintList(ColorStateList.valueOf(getColor(R.color.ink)));
        GradientDrawable hitArea = new GradientDrawable();
        hitArea.setColor(android.graphics.Color.WHITE);
        hitArea.setCornerRadius(dp(6));
        wake.setBackground(new RippleDrawable(
            ColorStateList.valueOf(getColor(R.color.ripple)), null, hitArea));
        wake.setOnClickListener(v -> wakeComputer(savedTarget()));
        FrameLayout footer = new FrameLayout(this);
        String versionNumber = installedVersion();
        TextView version = text(versionNumber.isEmpty() ? "" : "V" + versionNumber, 12);
        version.setTextColor(getColor(R.color.secondary));
        FrameLayout.LayoutParams versionParams = new FrameLayout.LayoutParams(
            -2, -2, Gravity.START | Gravity.BOTTOM);
        footer.addView(version, versionParams);
        FrameLayout.LayoutParams powerParams = new FrameLayout.LayoutParams(
            dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        footer.addView(wake, powerParams);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        startPanel = new ScrollView(this);
        startPanel.setBackground(panel(R.color.chooser, 10));
        startPanel.setClipToOutline(true);
        startPanel.setElevation(dp(8));
        startPanel.setClickable(true);
        startPanel.addView(content);
        FrameLayout.LayoutParams panelLayout = sidePanelLayout(Gravity.BOTTOM, -2);
        panelLayout.setMargins(dp(8), dp(8), dp(8), dp(8));
        touchpadArea.addView(startPanel, panelLayout);
        startPanel.setTranslationX(-dp(28));
        startPanel.animate().translationX(0).setDuration(160).start();
        content.requestFocus();
    }

    private FrameLayout.LayoutParams sidePanelLayout(int verticalGravity, int height) {
        int available = touchpadArea.getWidth();
        if (available == 0) available = getResources().getDisplayMetrics().widthPixels - dp(24);
        int width = Math.min(dp(320), available * 4 / 5);
        return new FrameLayout.LayoutParams(width, height, Gravity.START | verticalGravity);
    }

    private void closeWindowsMenu() {
        if (startPanel == null) return;
        View focused = startPanel.findFocus();
        if (focused instanceof EditText) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        touchpadArea.removeView(startPanel);
        startPanel = null;
    }

    private EditText addressField(LinearLayout box, String title, String value, boolean physicalAddress) {
        box.addView(text(title, 14));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        if (physicalAddress) {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.addTextChangedListener(new android.text.TextWatcher() {
                private boolean formatting;
                private int deletedSeparatorAt = -1;
                private boolean invalidInput;

                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                    deletedSeparatorAt = count == 1 && after == 0 && text.charAt(start) == '-'
                        ? start : -1;
                }

                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (formatting) return;
                    String inserted = text.subSequence(start, start + count).toString().trim();
                    invalidInput = !inserted.matches("[0-9A-Fa-f:-]*")
                        || text.toString().replaceAll("[^0-9A-Fa-f]", "").length() > 12
                        || (inserted.length() > 1 && (inserted.contains("-") || inserted.contains(":"))
                            && normalizedMac(inserted).isEmpty());
                }

                @Override public void afterTextChanged(android.text.Editable text) {
                    if (formatting) return;
                    String original = text.toString();
                    int cursor = Math.max(0, field.getSelectionStart());
                    if (deletedSeparatorAt > 0) {
                        int previous = deletedSeparatorAt - 1;
                        original = original.substring(0, previous) + original.substring(deletedSeparatorAt);
                        cursor = previous;
                    }
                    int digitsBeforeCursor = macDigits(original.substring(0, Math.min(cursor, original.length()))).length();
                    String digits = macDigits(original);
                    String formatted = formattedMac(digits);
                    if (formatted.contentEquals(text)) {
                        field.setError(invalidInput ? "Geçersiz format" : null);
                        return;
                    }

                    int position = 0;
                    for (int seen = 0; seen < digitsBeforeCursor && position < formatted.length(); position++) {
                        if (formatted.charAt(position) != '-') seen++;
                    }
                    if (position < formatted.length() && formatted.charAt(position) == '-') position++;
                    formatting = true;
                    text.replace(0, text.length(), formatted);
                    field.setSelection(position);
                    formatting = false;
                    field.setError(invalidInput ? "Geçersiz format" : null);
                }
            });
        } else {
            field.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789."));
            field.setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(15)});
        }
        field.setText(physicalAddress ? formattedMac(macDigits(value)) : value);
        field.setTextSize(13);
        field.setBackground(panel(R.color.surface, 8));
        field.setPadding(dp(8), 0, dp(8), 0);
        field.setContentDescription(title);
        box.addView(field, new LinearLayout.LayoutParams(-1, dp(38)));
        return field;
    }

    private void panelButton(LinearLayout row, String label, int fill, int ink, Runnable action) {
        Button button = new Button(this);
        stylePanelButton(button, fill, ink);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
        params.setMargins(0, dp(8), dp(4), 0);
        row.addView(button, params);
    }

    private void stylePanelButton(Button button, int fill, int ink) {
        styleButton(button, false);
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(getColor(R.color.ripple)), panel(fill, 12), null));
        button.setTextColor(getColor(ink));
        button.setStateListAnimator(null);
        button.setTextSize(13);
    }

    private void menuAction(LinearLayout parent, String title, Runnable action) {
        Button item = new Button(this);
        styleButton(item, false);
        item.setText(title);
        item.setTextSize(13);
        item.setMaxLines(2);
        item.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.setStateListAnimator(null);
        item.setMinimumHeight(dp(48));
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(5);
        parent.addView(item, params);
    }

    private void click(int button) {
        if (closeOpenPanel()) return;
        if (bluetooth != null && bluetooth.connected()) bluetooth.click(button);
        else {
            showSavedDevices = true;
            chooserDismissed = false;
            scanStarted = false;
            refreshBluetooth();
        }
        
    }

    @Override protected void onDestroy() { generation++; ui.removeCallbacksAndMessages(null); network.shutdownNow(); if (bluetooth != null) bluetooth.close(); super.onDestroy(); }
}
