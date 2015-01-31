package org.mems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import android.view.Window;
import android.widget.*;
import org.mems.BLEService.BLEDeviceContext;
import org.mems.BLEService.LocalBinder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class MainActivity extends Activity {

    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    // 进度对话框的ID
    private static final int DIALOG_ID_PROGRESS = 1;

    private BLEService bleService;
    private boolean serviceBounded = false;

    private List<BLEDeviceContext> usedDevices = new ArrayList<BLEDeviceContext>();
    private MyDeviceListAdapter listAdapter;
    private ListView deviceList;

    // 扫描到的设备
    private List<BluetoothDevice> unusedDevices = Collections.synchronizedList(new ArrayList<BluetoothDevice>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        deviceList = (ListView) findViewById(R.id.device_list);
        listAdapter = new MyDeviceListAdapter(this);
        deviceList.setAdapter(listAdapter);

        // 检查蓝牙设置
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        // 让用户启用蓝牙
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(LOG_TAG, "需要用户启动蓝牙");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(enableBtIntent);
            return;
        }

        Intent serviceIntent = new Intent(this, BLEService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.BROADCAST_USED_DEVICE_ADDED);
        registerReceiver(broadcastReceiver, filter);

        Intent intent = new Intent(this, BLEService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(broadcastReceiver);

        if (serviceBounded) {
            unbindService(serviceConnection);
            serviceBounded = false;
        }

        super.onStop();
    }

    @SuppressWarnings("deprecation")
    private void showDeviceChooser() {
        final String[] deviceNames = new String[unusedDevices.size()];
        final boolean[] checkeds = new boolean[unusedDevices.size()];
        for (int i = 0; i < unusedDevices.size(); i++) {
            BluetoothDevice device = unusedDevices.get(i);
            deviceNames[i] = device.getName() + "[" + device.getAddress() + "]";
            checkeds[i] = false;
        }

        dismissDialog(DIALOG_ID_PROGRESS);

        Builder dialogBuilder = new AlertDialog.Builder(MainActivity.this).setTitle("请选择设备")
                .setMultiChoiceItems(deviceNames, checkeds, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    }
                }).setPositiveButton("确认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialoginterface, int which) {
                        dialoginterface.dismiss();
                        List<BluetoothDevice> checkedDevices = new ArrayList<BluetoothDevice>();
                        for (int i = 0; i < checkeds.length; i++) {
                            if (checkeds[i]) {
                                checkedDevices.add(unusedDevices.get(i));
                            }
                        }
                        bleService.addDevices(checkedDevices);
                    }
                });
        dialogBuilder.show();
    }

    // 显示未使用设备列表，让用户添加
    @SuppressWarnings("deprecation")
    public void addDevice(View view) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }

        unusedDevices.clear();

        Log.i(LOG_TAG, "开始扫描");
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothAdapter.startLeScan(mLeScanCallback);

        deviceList.postDelayed(new Runnable() {

            @Override
            public void run() {
                bluetoothAdapter.stopLeScan(mLeScanCallback);
                showDeviceChooser();
            }
        }, 2000); // 扫描2秒

        showDialog(DIALOG_ID_PROGRESS);
    }

    public void switchAll(View view) {
        ImageButton btn = (ImageButton) view;

        btn.setActivated(!btn.isActivated());

        Map<String, BLEDeviceContext> devices = bleService.getUsedDevices();
        for (BLEDeviceContext d : devices.values()) {
            d.changeStatus(btn.isActivated());
        }

    }

    public void reconnect(View view) {
        String address = (String) ((View) view.getParent()).getTag();
        BLEDeviceContext device = bleService.getUsedDevices().get(address);
        device.reconnect();
    }

    public void deviceSettings(View view) {
        String address = (String) ((View) view.getParent()).getTag();
        Intent intent = new Intent(this, LEDSettingsActivity.class);
        intent.putExtra("device", address);
        startActivity(intent);
    }

    public void changeStatus(View view) {
        String address = (String) ((View) view.getParent()).getTag();
        BLEDeviceContext device = bleService.getUsedDevices().get(address);

        ImageButton btn = (ImageButton) view;
        device.changeStatus(!btn.isActivated());
        btn.setActivated(!btn.isActivated());
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case DIALOG_ID_PROGRESS:
                ProgressDialog dialog = new ProgressDialog(this);
                dialog.setMessage("正在扫描设备请稍后...");
                dialog.setIndeterminate(true);
                dialog.setCancelable(false);
                return dialog;
        }
        return null;
    }

    // 刷新列表
    private void refreshList() {
        Log.i(LOG_TAG, "refreshListAdapter");
        if (bleService == null) {
            return;
        }

        Map<String, BLEDeviceContext> devices = bleService.getUsedDevices();
        usedDevices.clear();
        usedDevices.addAll(devices.values());
        listAdapter.notifyDataSetChanged();
    }

    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            Log.i(LOG_TAG, "搜索到设备：" + device);
            if (!bleService.getUsedDevices().containsKey(device.getAddress())) {
                unusedDevices.add(device);
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            LocalBinder binder = (LocalBinder) iBinder;
            bleService = binder.getService(); // 调用Binder的公有方法
            serviceBounded = true;

            bleService.connectAllDevices();
            // 刷一次
            refreshList();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            serviceBounded = false;
        }
    };

    private class MyDeviceListAdapter extends ArrayAdapter<BLEDeviceContext> {

        private LayoutInflater mInflater;

        public MyDeviceListAdapter(Context context) {
            super(context, R.layout.activity_main_device_item, usedDevices);

            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;

            if (convertView == null) {
                view = (View) mInflater.inflate(R.layout.activity_main_device_item, parent, false);
            } else {
                view = (View) convertView;
            }

            BLEDeviceContext item = getItem(position);

            // view.setBackgroundColor(Color.rgb(item.red, item.green, item.blue));

            TextView nameView = (TextView) view.findViewById(R.id.device_name);
            nameView.setText(item.name);
            String status = item.usable ? "可以控制" : item.connected ? "已连接" : "未连接";
            view.setTag(item.address);
            return view;
        }
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (BLEService.BROADCAST_USED_DEVICE_ADDED.equals(intent.getAction())
                    || BLEService.BROADCAST_CONNECT_STATUS_CHANGED.equals(intent.getAction())
                    || BLEService.BROADCAST_USABLE_STATUS_CHANGED.equals(intent.getAction())) {
                refreshList();
            }
        }
    };

}
