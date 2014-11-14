package org.mems;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class BLEService extends Service {

    public static final String BROADCAST_USED_DEVICE_ADDED = "BROADCAST_USED_DEVICE_ADDED";
    public static final String BROADCAST_CONNECT_STATUS_CHANGED = "BROADCAST_CONNECT_STATUS_CHANGED";
    public static final String BROADCAST_USABLE_STATUS_CHANGED = "BROADCAST_USABLE_STATUS_CHANGED";
    public static final String BROADCAST_READWRITE_FINISH_CHANGED = "BROADCAST_READWRITE_FINISH_CHANGED";

    private static final String LOG_TAG = BLEService.class.getSimpleName();

    // TODO !!!!!!!!!!!!!!!!!!!!!
    private static final UUID SERVICE_LIGHT_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");

    private static final UUID CHARACTERISTIC_LIGHT_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private static final String DEVICE_PREF = "DEVICE_PREF";
    // 保存已添加到列表中的所有设备地址
    private static final String USED_DEVICE_ADDRESSES = "USED_DEVICE_ADDRESSES";
    // 保存设备名字
    private static final String USED_DEVICE_NAMES_PREFIX = "USED_DEVICE_NAME_";

    private final IBinder binder = new LocalBinder();

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;

    private volatile boolean busy = false;
    private ExecutorService generalWorker = Executors.newSingleThreadExecutor();
    private ExecutorService readWriteWorker = Executors.newSingleThreadExecutor();

    // 用户已使用的设备
    private Map<String, BLEDeviceContext> usedDevices = new ConcurrentHashMap<String, BLEDeviceContext>();
    private Set<String> usedDeviceAddresses;

    @Override
    public void onCreate() {
        super.onCreate();

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadPersistedDevices();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 连接到所有设备
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 断开到所有设备的连接
        disconnectAllDevices();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "onDestroy");
        // 关闭所有设备
        closeAllGatt();
        super.onDestroy();
    }

    private void loadPersistedDevices() {
        // 已使用的设备的地址列表
        SharedPreferences preferences = getSharedPreferences(DEVICE_PREF, MODE_PRIVATE);
        usedDeviceAddresses = Collections.synchronizedSet(preferences.getStringSet(USED_DEVICE_ADDRESSES,
                new HashSet<String>(0)));
        Log.i(LOG_TAG, "已使用的设备地址：" + usedDeviceAddresses.toString());
        for (String address : usedDeviceAddresses) {
            buildDeviceContext(bluetoothAdapter.getRemoteDevice(address));
        }
        notifyUsedDeviceChanged();
    }

    // TODO private?
    public void addDevices(List<BluetoothDevice> devices) {
        for (BluetoothDevice device : devices) {
            BLEDeviceContext context = buildDeviceContext(device);
            context.connect(); // TODO 延时，任务化？
        }

        persistUsedDevices();
    }

    public void removeDevice(String address) {
        usedDevices.remove(address);
        persistUsedDevices();
    }

    private void persistUsedDevices() {
        usedDeviceAddresses.clear();
        usedDeviceAddresses.addAll(usedDevices.keySet());
        Log.i(LOG_TAG, "更新-已使用的设备地址：" + usedDeviceAddresses.toString());
        Editor edit = getSharedPreferences(DEVICE_PREF, MODE_PRIVATE).edit();
        edit.putStringSet(USED_DEVICE_ADDRESSES, usedDeviceAddresses).commit();

        notifyUsedDeviceChanged();
    }

    private BLEDeviceContext buildDeviceContext(BluetoothDevice device) {
        Log.i(LOG_TAG, "useDevice: " + device.getAddress());
        SharedPreferences preferences = getSharedPreferences(DEVICE_PREF, MODE_PRIVATE);

        BLEDeviceContext deviceStatus = new BLEDeviceContext();
        deviceStatus.address = device.getAddress();
        deviceStatus.name = preferences.getString(USED_DEVICE_NAMES_PREFIX + device.getAddress(), device.getName());

        usedDevices.put(device.getAddress(), deviceStatus);

        return deviceStatus;
    }

    private void notifyUsedDeviceChanged() {
        Intent intent = new Intent(BROADCAST_USED_DEVICE_ADDED);
        sendBroadcast(intent);
    }

    public Map<String, BLEDeviceContext> getUsedDevices() {
        return usedDevices;
    }

    public void changeDeviceName(String address, String name) {
        BLEDeviceContext device = getUsedDevices().get(address);
        if (device != null) {
            device.name = name;
        }
        Editor edit = getSharedPreferences(DEVICE_PREF, MODE_PRIVATE).edit();
        edit.putString(USED_DEVICE_NAMES_PREFIX + address, name);
        edit.commit();
    }

    public void connectAllDevices() {
        for (BLEDeviceContext dc : usedDevices.values()) {
            // TODO 延时？
            dc.connect();
        }
    }

    private void disconnectAllDevices() {
        for (BLEDeviceContext dc : usedDevices.values()) {
            // TODO 延时？
            dc.disconnect();
        }
    }

    private void closeAllGatt() {
        // TODO 延时？
        for (BLEDeviceContext dc : usedDevices.values()) {
            dc.close();
        }
    }

    public class BLEDeviceContext {

        public volatile String address; // 设备MAC
        public volatile String name; // 备注名称
        public volatile boolean connected = false;
        public volatile boolean usable = false;

        private volatile BluetoothGatt gatt;
        private volatile BluetoothGattCharacteristic characteristicLight;

        // 当前状态
        private volatile boolean lightOn = false;
        // 当前颜色
        public volatile byte red = (byte) 0xff;
        public volatile byte green = (byte) 0xff;
        public volatile byte blue = (byte) 0xff;

        // TODO ？多个体设备使用一个回调还是多个回调
        private BluetoothGattCallback callback = new MyBluetoothGattCallback(this);

        private void connect() {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

            int connectionState = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
            if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(LOG_TAG, "connect: GATT already connected");
                boolean result = gatt.discoverServices();
                if (!result) {
                    Log.e(LOG_TAG, "connect: discoverServices return false");
                }
                return;
            }

            if (gatt != null) {
                Log.i(LOG_TAG, "connect: Connect using the existed connection");
                boolean result = gatt.connect();
                if (!result) {
                    Log.e(LOG_TAG, "connect: connect return false");
                }
            } else {
                Log.i(LOG_TAG, "connect: Create a new GATT connection");
                gatt = device.connectGatt(BLEService.this, false, callback);
            }
        }

        private void disconnect() {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

            int connectionState = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
            if (connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(LOG_TAG, "disconnect: GATT already disconnected");
            } else {
                if (gatt != null) {
                    gatt.disconnect();
                }
            }
        }

        public void reconnect() {
            connect();
        }

        private void close() {
            Log.i(LOG_TAG, "close: closing gatt");
            if (gatt == null) {
                return;
            }
            gatt.close();
            gatt = null;
            characteristicLight = null;
        }

        private void setCharacteristics(BluetoothGatt gatt) {
            BluetoothGattService service = gatt.getService(SERVICE_LIGHT_UUID);
            if (service == null) {
                Log.e(LOG_TAG, "setCharacteristics: cannot find service");
                return;
            }
            characteristicLight = service.getCharacteristic(CHARACTERISTIC_LIGHT_UUID);
            if (characteristicLight == null) {
                Log.e(LOG_TAG, "setCharacteristics: cannot find characteristic" + CHARACTERISTIC_LIGHT_UUID);
            }
            Log.i(LOG_TAG, "发现特征值" + CHARACTERISTIC_LIGHT_UUID);
        }

        public void changeColor(int color) {
            if (busy || characteristicLight == null) {
                return;
            }

            Log.i(LOG_TAG, "改变颜色:" + Integer.toHexString(color));
            red = (byte) Color.red(color);
            green = (byte) Color.green(color);
            blue = (byte) Color.blue(color);

            byte[] value = new byte[] { red, green, blue, (byte) 0x00, (byte) 0x64 };
            readWriteWorker.submit(new Task(value, characteristicLight, gatt));
        }

        public void changeStatus(boolean on) {
            if (characteristicLight == null) {
                return;
            }

            lightOn = on;
            Log.i(LOG_TAG, on ? "准备亮灯" : "准备灭灯");
            byte[] value = new byte[] { red, green, blue, (byte) 0x00, on ? (byte) 0x64 : (byte) 0x00 };
            readWriteWorker.submit(new Task(value, characteristicLight, gatt));
        }

        public void toggle() {
            changeStatus(!lightOn);
        }
    }

    private class MyBluetoothGattCallback extends BluetoothGattCallback {

        private BLEDeviceContext deviceContext;

        public MyBluetoothGattCallback(BLEDeviceContext deviceContext) {
            this.deviceContext = deviceContext;
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(LOG_TAG, "onConnectionStateChange: connected | " + deviceContext.address);
                deviceContext.connected = true;
                // 不在回调线程中调用
                generalWorker.submit(new Runnable() {

                    @Override
                    public void run() {
                        boolean discoverServicesResult = gatt.discoverServices();
                        if (!discoverServicesResult) {
                            Log.w(LOG_TAG, "调用发现服务直接返回false");
                        }
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(LOG_TAG, "onConnectionStateChange: disconnected | " + deviceContext.address);
                deviceContext.connected = false;
            } else {
                Log.e(LOG_TAG, "onConnectionStateChange: unknown: " + newState + " | " + deviceContext.address);
            }
            // 广播
            Intent intent = new Intent(BROADCAST_CONNECT_STATUS_CHANGED);
            intent.putExtra("address", deviceContext.address);
            sendBroadcast(intent);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOG_TAG, "onServicesDiscovered： success | " + deviceContext.address);
                deviceContext.usable = true;
                // 不在回调线程中调用
                generalWorker.submit(new Runnable() {

                    @Override
                    public void run() {
                        deviceContext.setCharacteristics(gatt);
                    }
                });
            } else {
                Log.e(LOG_TAG, "onServicesDiscovered： fail " + status + "| " + deviceContext.address);
                deviceContext.usable = false;
            }
            // 广播
            Intent intent = new Intent(BROADCAST_USED_DEVICE_ADDED);
            intent.putExtra("address", deviceContext.address);
            sendBroadcast(intent);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Intent intent = new Intent(BROADCAST_USED_DEVICE_ADDED);
            intent.putExtra("address", deviceContext.address);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOG_TAG, characteristic.toString());
                intent.putExtra("success", true);
            } else {
                intent.putExtra("success", false);
            }

            busy = false;
            sendBroadcast(intent);
        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Intent intent = new Intent(BROADCAST_USED_DEVICE_ADDED);
            intent.putExtra("address", deviceContext.address);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOG_TAG, "onCharacteristicWrite: success | " + deviceContext.address);
                intent.putExtra("success", true);
            } else {
                Log.e(LOG_TAG, "onCharacteristicWrite: fail " + status + " | " + deviceContext.address);
                intent.putExtra("success", false);
            }

            busy = false;
            sendBroadcast(intent);
        };

    };

    private class Task implements Runnable {

        private final byte[] value;
        private final BluetoothGattCharacteristic characteristicLight;
        private final BluetoothGatt gatt;

        public Task(byte[] value, BluetoothGattCharacteristic characteristicLight, BluetoothGatt gatt) {
            super();
            this.value = value;
            this.characteristicLight = characteristicLight;
            this.gatt = gatt;
        }

        @Override
        public void run() {
            int count = 0;
            while (busy && count < 10) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
                count++;
            }

            if (!busy) {
                busy = true;
                characteristicLight.setValue(value);
                gatt.writeCharacteristic(characteristicLight);
            }
        }

    }

    public class LocalBinder extends Binder {
        public BLEService getService() {
            // 返回Service实例，让客户端直接调用Service的方法
            return BLEService.this;
        }
    }

}
