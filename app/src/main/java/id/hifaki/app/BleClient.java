package id.hifaki.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.List;
import java.util.UUID;

/** BLE is local and works without Wi-Fi or mobile data. Permissions are requested by MainActivity. */
@SuppressLint("MissingPermission")
public final class BleClient {
    public interface Listener {
        void onStatus(String status);
        void onReading(Reading reading);
    }

    public static final UUID SERVICE = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    public static final UUID DATA = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    public static final UUID COMMAND = UUID.fromString("82b9e6e1-593a-456f-9b1c-7812a34f56d9");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private ScanCallback scanCallback;
    private BluetoothGattCharacteristic data;
    private BluetoothGattCharacteristic command;
    private boolean closed;

    public BleClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(bondReceiver, filter);
    }

    public boolean available() { return adapter != null && adapter.isEnabled(); }
    
    public boolean isConnected() { return gatt != null; }

    public void scan() {
        if (closed || !available()) { status("Aktifkan Bluetooth pada ponsel."); return; }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { status("Pemindai BLE tidak tersedia."); return; }
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                List<ParcelUuid> services = result.getScanRecord() == null ? null : result.getScanRecord().getServiceUuids();
                boolean serviceFound = services != null && services.contains(new ParcelUuid(SERVICE));
                if ((name != null && name.startsWith("HIFAKI")) || serviceFound) {
                    BluetoothDevice device = result.getDevice();
                    stopScan();
                    connect(device);
                }
            }

            @Override public void onScanFailed(int code) { stopScan(); status("Pemindaian gagal (" + code + ")."); }
        };
        status("Mencari alat HIFAKI di dekat Anda…");
        scanner.startScan(scanCallback);
        ScanCallback active = scanCallback;
        main.postDelayed(() -> {
            if (scanCallback == active) {
                stopScan();
                status("Alat belum ditemukan. Dekatkan dan nyalakan alat.");
            }
        }, 12000);
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback);
        scanCallback = null;
    }

    private void connect(BluetoothDevice device) {
        status("Menghubungkan " + (device.getName() == null ? "HIFAKI" : device.getName()) + "…");
        if (gatt != null) gatt.close();
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (gatt == null || device == null || !device.getAddress().equals(gatt.getDevice().getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (state == BluetoothDevice.BOND_BONDED) {
                status("Terpasang aman. Membaca sensor…");
                main.postDelayed(() -> {
                    if (gatt != null) gatt.discoverServices();
                }, 600);
            } else if (state == BluetoothDevice.BOND_NONE) {
                status("Pemasangan gagal. Ulangi dan masukkan kode alat.");
            }
        }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int code, int state) {
            if (closed) return;
            if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                status("Terhubung. Membaca sensor…");
                // Langsung discoverServices tanpa memaksa proses pairing (bonding)
                main.postDelayed(connection::discoverServices, 600);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                data = null;
                status("Alat terputus. Data lama tidak dipakai untuk pemeriksaan baru.");
                main.post(() -> listener.onReading(null));
                connection.close();
                if (gatt == connection) gatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int code) {
            if (code != BluetoothGatt.GATT_SUCCESS) { status("Layanan BLE belum terbaca."); return; }
            BluetoothGattService service = connection.getService(SERVICE);
            data = service == null ? null : service.getCharacteristic(DATA);
            command = service == null ? null : service.getCharacteristic(COMMAND);
            if (data == null) { status("Firmware alat tidak sesuai protokol HIFAKI."); return; }
            
            // Berikan jeda tambahan agar sistem Android siap membaca dan mengaktifkan notifikasi
            main.postDelayed(() -> {
                if (!connection.readCharacteristic(data)) status("Gagal membaca karakteristik sensor.");
            }, 300);
        }

        @Override public void onCharacteristicRead(BluetoothGatt connection,
                                                    BluetoothGattCharacteristic characteristic,
                                                    byte[] value, int code) {
            if (code == BluetoothGatt.GATT_SUCCESS) {
                accept(value);
                subscribe(connection, characteristic);
            } else status("Akses data ditolak. Periksa kode pairing BLE.");
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt connection,
                                                    BluetoothGattCharacteristic characteristic, int code) {
            if (Build.VERSION.SDK_INT < 33) {
                if (code == BluetoothGatt.GATT_SUCCESS) {
                    accept(characteristic.getValue());
                    subscribe(connection, characteristic);
                } else status("Akses data ditolak. Periksa pairing BLE.");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection,
                                                       BluetoothGattCharacteristic characteristic, byte[] value) {
            accept(value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt connection,
                                                       BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) accept(characteristic.getValue());
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int code) {
            status(code == BluetoothGatt.GATT_SUCCESS ? "Alat terhubung • BLE offline aktif" :
                    "Notifikasi sensor gagal diaktifkan.");
        }
    };

    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
        if (!connection.setCharacteristicNotification(characteristic, true)) {
            status("Gagal mengaktifkan pembaruan data."); return;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) { status("Descriptor BLE tidak ditemukan."); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            int result = connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (result != BluetoothGatt.GATT_SUCCESS) status("Gagal berlangganan data BLE.");
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!connection.writeDescriptor(descriptor)) status("Gagal berlangganan data BLE.");
        }
    }

    private void accept(byte[] packet) {
        Reading value = Reading.parse(packet, System.currentTimeMillis());
        if (value != null) main.post(() -> listener.onReading(value));
    }

    private void status(String value) { main.post(() -> listener.onStatus(value)); }

    @SuppressWarnings("deprecation")
    public void sendCommand(String commandText) {
        if (gatt == null || command == null) {
            status("BLE belum siap untuk mengirim perintah.");
            return;
        }
        byte[] payload = commandText.getBytes();
        boolean success;
        if (Build.VERSION.SDK_INT >= 33) {
            success = gatt.writeCharacteristic(command, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        } else {
            command.setValue(payload);
            success = gatt.writeCharacteristic(command);
        }
        if (!success) {
            status("Gagal mengirim perintah ke alat.");
        }
    }

    public void disconnect() {
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        data = null;
        command = null;
        status("Sinyal BLE diputuskan. Siap gunakan mode Online.");
        main.post(() -> listener.onReading(null));
    }

    public void close() {
        closed = true;
        stopScan();
        context.unregisterReceiver(bondReceiver);
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        main.removeCallbacksAndMessages(null);
    }
}
