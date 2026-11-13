package com.samet.windowstik;

import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class BluetoothControls {
    private static final byte[] REPORTS = {
        0x05, 0x01, 0x09, 0x06, (byte)0xa1, 0x01, (byte)0x85, 1,
        0x05, 0x07, 0x19, (byte)0xe0, 0x29, (byte)0xe7, 0x15, 0, 0x25, 1,
        0x75, 1, (byte)0x95, 8, (byte)0x81, 2,
        0x75, 8, (byte)0x95, 1, (byte)0x81, 1,
        0x05, 8, 0x19, 1, 0x29, 5, 0x75, 1, (byte)0x95, 5, (byte)0x91, 2,
        0x75, 3, (byte)0x95, 1, (byte)0x91, 1,
        0x05, 7, 0x19, 0, 0x29, 0x65, 0x15, 0, 0x25, 0x65,
        0x75, 8, (byte)0x95, 6, (byte)0x81, 0, (byte)0xc0,
        0x05, 1, 0x09, 2, (byte)0xa1, 1, (byte)0x85, 2,
        0x09, 1, (byte)0xa1, 0,
        0x05, 9, 0x19, 1, 0x29, 3, 0x15, 0, 0x25, 1,
        0x75, 1, (byte)0x95, 3, (byte)0x81, 2,
        0x75, 5, (byte)0x95, 1, (byte)0x81, 3,
        0x05, 1, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, (byte)0x81, 0x25, 0x7f, 0x75, 8, (byte)0x95, 3,
        (byte)0x81, 6, (byte)0xc0, (byte)0xc0
    };
    private final Activity activity;
    private final Runnable changed;
    private final BluetoothAdapter adapter;
    private BluetoothHidDevice profile;
    private BluetoothDevice host;
    private String selectedHost;
    private boolean registered;
    private boolean pending;
    private boolean closed;
    private boolean scanning;
    private boolean scanRequested;
    private boolean allowReconnect = true;
    private boolean waitingForDiscovery;
    private boolean pairOnSelection;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (enabled()) {
                    resume();
                    if (scanRequested) scan();
                } else {
                    host = null;
                    scanning = false;
                    handler.removeCallbacks(retry);
                    found.clear();
                    update("Bluetooth kapalı.");
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                waitingForDiscovery = false;
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, 400);
                changed.run();
            } else {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                if (BluetoothDevice.ACTION_FOUND.equals(action) && scanRequested) {
                    found.put(device.getAddress(), device);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                        && device.getAddress().equals(selectedHost)) {
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED && allowReconnect) {
                        handler.removeCallbacks(connectionTimeout);
                        handler.postDelayed(connectionTimeout, 35000);
                        handler.postDelayed(retry, 1200);
                    } else if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                        forget(device);
                        update("Eşleşme kaldırıldı veya tamamlanamadı.");
                    }
                }
                changed.run();
            }
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::reconnect;
    private final Runnable connectionTimeout = this::onConnectionTimeout;

    private void onConnectionTimeout() {
        if (connected()) return;
        BluetoothDevice device = selectedHost.isEmpty() ? null : adapter.getRemoteDevice(selectedHost);
        if (device == null) return;
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            allowReconnect = false;
            update("Eşleşme tamamlanmadı. Windows’ta Cihaz ekle → Bluetooth ekranını aç ve iki ekrandaki kodu onayla.");
        } else if (!registered) {
            update("Telefonun Bluetooth fare ve klavye profili hazır olmadı. Uygulamayı kapatıp yeniden aç.");
        } else {
            update("Windows bağlantısı bekleniyor. Yeniden denenecek…");
            scheduleReconnect();
        }
    }
    private void scheduleReconnect() {
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 5000);
    }

    private String message = "Bluetooth klavye hazırlanıyor…";

    BluetoothControls(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        IntentFilter events = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        events.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        events.addAction(BluetoothDevice.ACTION_FOUND);
        events.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, events, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(receiver, events);
        selectedHost = activity.getPreferences(Activity.MODE_PRIVATE).getString("keyboardHost", "");
    }

    private void update(String value) {
        message = value;
        if (!closed) changed.run();
    }

    String status() { return message; }
    boolean connected() { return host != null; }
    boolean enabled() { return adapter != null && adapter.isEnabled(); }
    boolean scanning() { return scanning; }

    boolean hasSavedHost() {
        if (!enabled()) return false;
        String address = activity.getPreferences(Activity.MODE_PRIVATE).getString("keyboardHost", "");
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (device.getAddress().equals(address)) return true;
        }
        return false;
    }
    boolean connectedTo(BluetoothDevice device) { return device.equals(host); }

    void scan() {
        if (!enabled()) return;
        scanRequested = true;
        if (!scanning) {
            found.clear();
            scanning = adapter.startDiscovery();
        }
        changed.run();
    }

    void stopScan() {
        pauseDiscovery();
        found.clear();
    }

    void toggle() {
        if (!enabled()) {
            start();
        } else if (Build.VERSION.SDK_INT >= 33 || !adapter.disable()) {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private final BluetoothProfile.ServiceListener service = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int type, BluetoothProfile proxy) {
            if (closed) {
                adapter.closeProfileProxy(type, proxy);
                return;
            }
            pending = false;
            profile = (BluetoothHidDevice) proxy;
            register();
        }

        @Override public void onServiceDisconnected(int type) {
            profile = null;
            registered = false;
            pending = false;
            host = null;
            update("Bluetooth klavye bağlantısı kesildi.");
        }
    };

    void resume() {
        if (!enabled()) return;
        if (!connected() && hasSavedHost()) {
            handler.removeCallbacks(connectionTimeout);
            handler.postDelayed(connectionTimeout, 35000);
        }
        start();
    }

    void start() {
        if (Build.VERSION.SDK_INT < 28 || adapter == null) {
            update("Bu telefonda Bluetooth klavye desteği yok.");
        } else if (!adapter.isEnabled()) {
            update("Devam etmek için Bluetooth’u aç.");
            activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 44);
        } else if (profile != null) {
            if (!registered && !pending) register();
            else reconnect();
        } else if (!pending) {
            pending = adapter.getProfileProxy(activity, service, BluetoothProfile.HID_DEVICE);
            update(pending ? "Bluetooth klavye hazırlanıyor…" : "Telefon Bluetooth klavye profilini açamadı.");
        }
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings identity = new BluetoothHidDeviceAppSdpSettings(
            "WINDOWS TIK", "WINDOWS TIK klavye ve fare", "WINDOWS TIK",
            BluetoothHidDevice.SUBCLASS1_COMBO, REPORTS);
        pending = profile.registerApp(identity, null, null, activity::runOnUiThread, new BluetoothHidDevice.Callback() {
            @Override public void onAppStatusChanged(BluetoothDevice device, boolean ready) {
                if (closed) return;
                pending = false;
                registered = ready;
                if (!ready) host = null;
                if (!ready) update("Bluetooth fare ve klavye profili açılamadı. Yeniden denemek için cihazı seç.");
                else if (selectedHost.isEmpty()) update("Bağlanmak için bilgisayarını seç.");
                if (ready) reconnect();
            }

            @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (closed) return;
                if (!device.getAddress().equals(selectedHost)) {
                    if (state == BluetoothProfile.STATE_CONNECTED && profile != null) profile.disconnect(device);
                    return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (!allowReconnect) {
                        if (profile != null) profile.disconnect(device);
                        return;
                    }
                    rememberConnection(device);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    host = null;
                    if (!registered || !allowReconnect) return;
                    update("Windows bağlantısı bekleniyor. Yeniden denenecek…");
                    scheduleReconnect();
                }
            }
        });
        if (!pending) update("Telefon Bluetooth klavye kaydını başlatamadı.");
    }

    void reconnect() {
        if (!allowReconnect || !registered || profile == null || connected() || selectedHost.isEmpty() || scanning || waitingForDiscovery) return;
        if (pairOnSelection) {
            pairOnSelection = false;
            beginPairing(adapter.getRemoteDevice(selectedHost));
        }
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (!device.getAddress().equals(selectedHost)) continue;
            int state = profile.getConnectionState(device);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                rememberConnection(device);
                return;
            }
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                boolean connecting = profile.connect(device);
                update(connecting ? "Fare ve klavye bağlanıyor…"
                        : "Windows bağlantısı bekleniyor. Yeniden denenecek…");
            }
            scheduleReconnect();
            return;
        }
    }

    private void rememberConnection(BluetoothDevice device) {
        host = device;
        activity.getPreferences(Activity.MODE_PRIVATE).edit().putString("keyboardHost", device.getAddress()).apply();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(connectionTimeout);
        update("Bluetooth fare ve klavye bağlandı.");
    }

    List<BluetoothDevice> devices() {
        Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
        if (enabled()) {
            for (BluetoothDevice device : adapter.getBondedDevices()) devices.put(device.getAddress(), device);
            devices.putAll(found);
        }
        return new ArrayList<>(devices.values());
    }

    void connect(BluetoothDevice device) {
        if (!enabled()) return;
        pauseDiscovery();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(connectionTimeout);
        if (host != null && !device.equals(host) && profile != null) profile.disconnect(host);
        if (!device.equals(host)) host = null;
        allowReconnect = true;
        selectedHost = device.getAddress();
        pairOnSelection = true;
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            handler.postDelayed(connectionTimeout, 35000);
            update("Fare ve klavye profili hazırlanıyor…");
        } else {
            handler.postDelayed(connectionTimeout, 90000);
            update("Windows’ta Bluetooth ve cihazlar → Cihaz ekle → Bluetooth ekranından telefonunu seç. Kod görünürse onayla.");
        }
        start();
    }

    private void beginPairing(BluetoothDevice device) {
        if (!allowReconnect || device.getBondState() != BluetoothDevice.BOND_NONE) return;
        if (device.createBond()) update("Eşleşme isteği gönderildi. Kod görünürse iki cihazda da onayla.");
    }

    void pauseDiscovery() {
        scanRequested = false;
        if (enabled() && adapter.isDiscovering()) {
            waitingForDiscovery = adapter.cancelDiscovery();
        }
        scanning = false;
    }

    void cancelAttempt() {
        stopScan();
        if (connected()) return;
        allowReconnect = false;
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(retry);
    }

    void disconnect(BluetoothDevice device) {
        if (!device.getAddress().equals(selectedHost)) return;
        allowReconnect = false;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(connectionTimeout);
        if (profile != null) profile.disconnect(device);
        host = null;
        activity.getPreferences(Activity.MODE_PRIVATE).edit().remove("keyboardHost").apply();
        update("Bluetooth bağlantısı kesildi.");
    }

    void forget(BluetoothDevice device) {
        disconnect(device);
        if (device.getAddress().equals(selectedHost)) selectedHost = "";
    }

    boolean type(String text, boolean turkishQ) {
        final String normal = "-=[]\\;'`,./";
        final String shifted = "_+{}|:\"~<>?";
        final String symbols = "!@#$%^&*()";
        int[] codes = new int[text.length()];
        int[] modifiers = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int turkishKey = turkishQ ? "ıIiİğĞüÜşŞöÖçÇ".indexOf(c) : -1;
            if (turkishKey >= 0) {
                int[] turkishKeys = {0x0c, 0x34, 0x2f, 0x30, 0x33, 0x36, 0x37};
                codes[i] = turkishKeys[turkishKey / 2];
                modifiers[i] = (turkishKey % 2) * 2;
            } else if (c >= 'a' && c <= 'z') codes[i] = 4 + c - 'a';
            else if (c >= 'A' && c <= 'Z') {
                codes[i] = 4 + c - 'A';
                modifiers[i] = 2;
            } else if (c >= '1' && c <= '9') codes[i] = 0x1e + c - '1';
            else if (c == '0') codes[i] = 0x27;
            else if (c == ' ') codes[i] = 0x2c;
            else if (turkishQ) {
                int stroke = turkishQPunctuation(c);
                if (stroke < 0) return false;
                codes[i] = stroke & 0xff;
                modifiers[i] = stroke >> 8;
            } else if (symbols.indexOf(c) >= 0) {
                codes[i] = 0x1e + symbols.indexOf(c);
                modifiers[i] = 2;
            } else {
                int index = normal.indexOf(c);
                if (index < 0) {
                    index = shifted.indexOf(c);
                    modifiers[i] = 2;
                }
                if (index < 0) return false;
                codes[i] = 0x2d + index + (index >= 5 ? 1 : 0);
            }
        }
        for (int i = 0; i < codes.length; i++) {
            if (!keyboardReport(modifiers[i], codes[i])) return false;
        }
        return true;
    }

    private static int turkishQPunctuation(char c) {
        final String plain = "\"*-,.<";
        final String shifted = "é?_;:>";
        final int[] positions = {0x35, 0x2d, 0x2e, 0x31, 0x38, 0x64};
        int index = plain.indexOf(c);
        if (index >= 0) return positions[index];
        index = shifted.indexOf(c);
        if (index >= 0) return 0x200 | positions[index];
        index = "!'^+%&/()=".indexOf(c);
        if (index >= 0) return 0x200 | (0x1e + index);
        final String alt = "@€₺#$\\|{}[]~";
        final int[] altPositions = {0x14, 0x08, 0x17, 0x20, 0x21, 0x2d, 0x2e,
            0x24, 0x27, 0x25, 0x26, 0x30};
        index = alt.indexOf(c);
        return index < 0 ? -1 : 0x4000 | altPositions[index];
    }

    boolean key(int code) {
        return keyboardReport(0, code);
    }

    void zoomPage(boolean zoomIn) {
        if (connected()) {
            // Left Control with keypad add/subtract works across keyboard layouts.
            keyboardReport(0x01, zoomIn ? 0x57 : 0x56);
        }
    }

    private boolean keyboardReport(int modifiers, int code) {
        if (host == null || profile == null) return false;
        byte[] press = new byte[8];
        press[0] = (byte) modifiers;
        press[2] = (byte) code;
        boolean sent = profile.sendReport(host, 1, press);
        return profile.sendReport(host, 1, new byte[8]) && sent;
    }

    private static byte axis(int value) {
        return (byte) Math.max(-127, Math.min(127, value));
    }

    void move(int x, int y, int wheel) {
        move(x, y, wheel, 0);
    }

    boolean move(int x, int y, int wheel, int buttons) {
        if (connected() && profile != null) {
            return profile.sendReport(host, 2, new byte[]{(byte) buttons, axis(x), axis(y), axis(wheel)});
        }
        return false;
    }

    void click(int button) {
        if (connected() && profile != null) {
            profile.sendReport(host, 2, new byte[]{(byte)button, 0, 0, 0});
            profile.sendReport(host, 2, new byte[4]);
        }
    }

    void close() {
        closed = true;
        stopScan();
        activity.unregisterReceiver(receiver);
        handler.removeCallbacks(connectionTimeout);
        handler.removeCallbacks(retry);
        if (profile != null) {
            try {
                profile.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile);
            } catch (SecurityException ignored) {
                // The user may have revoked Bluetooth access.
            }
        }
    }
}
