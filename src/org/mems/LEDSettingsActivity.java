package org.mems;

import org.mems.BLEService.BLEDeviceContext;
import org.mems.BLEService.LocalBinder;
import org.mems.ColorChooser.ImageRGBDelegate;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

public class LEDSettingsActivity extends Activity implements ImageRGBDelegate {

    private boolean serviceBounded = false;
    private BLEService bleService;

    private String deviceAddress;
    private BLEDeviceContext device;

    private EditText ledNameField;
    private ColorChooser colorChooser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Intent intent = getIntent();
        deviceAddress = intent.getExtras().getString("device");

        ledNameField = (EditText) findViewById(R.id.led_name);
        colorChooser = (ColorChooser) findViewById(R.id.color_chooser);
        colorChooser.setDelegate(this);
    }

    @Override
    public void imageColor(int color) {
        if (device != null) {
            device.changeColor(color);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = new Intent(this, BLEService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (serviceBounded) {
            unbindService(serviceConnection);
            serviceBounded = false;
        }
    }

    public void changeName(View view) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }

        String name = ledNameField.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "名字不能为空", Toast.LENGTH_LONG).show();
        } else {
            bleService.changeDeviceName(deviceAddress, name);
            Toast.makeText(this, "已保存", Toast.LENGTH_LONG).show();
        }
    }

    public void removeLED(View view) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }
        bleService.removeDevice(deviceAddress);
        finish();
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            LocalBinder binder = (LocalBinder) iBinder;
            bleService = binder.getService(); // 调用Binder的公有方法
            serviceBounded = true;

            device = bleService.getUsedDevices().get(deviceAddress);

            ledNameField.setText(device.name);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            serviceBounded = false;
        }
    };

}
