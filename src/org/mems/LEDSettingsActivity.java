package org.mems;

import android.app.TimePickerDialog;
import android.content.*;
import android.view.Window;
import android.widget.*;
import org.mems.BLEService.BLEDeviceContext;
import org.mems.BLEService.LocalBinder;
import org.mems.ColorChooser.ImageRGBDelegate;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;

public class LEDSettingsActivity extends Activity implements ImageRGBDelegate, SeekBar.OnSeekBarChangeListener {

    private boolean serviceBounded = false;
    private BLEService bleService;

    private String deviceAddress;
    private BLEDeviceContext device;

    private EditText ledNameField;
    private ColorChooser colorChooser;

    private SeekBar brightBar;
    private Button setTimeOnBtn;
    private Button setTimeOffBtn;
    private ImageButton switchScheduleOn;
    private ImageButton switchScheduleOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_settings);

        Intent intent = getIntent();
        deviceAddress = intent.getExtras().getString("device");

        ledNameField = (EditText) findViewById(R.id.led_name);
        colorChooser = (ColorChooser) findViewById(R.id.color_chooser);
        colorChooser.setDelegate(this);

        brightBar = (SeekBar) findViewById(R.id.bright_bar);
        brightBar.setOnSeekBarChangeListener(this);
        brightBar.setProgress(80);

        switchScheduleOn = (ImageButton) findViewById(R.id.switch_schedule_on);
        switchScheduleOff = (ImageButton) findViewById(R.id.switch_schedule_off);

        setTimeOnBtn = (Button) findViewById(R.id.set_time_on);
        setTimeOffBtn = (Button) findViewById(R.id.set_time_off);


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

        SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
        switchScheduleOn.setActivated(p.getBoolean("on_status" + deviceAddress, false));
        switchScheduleOff.setActivated(p.getBoolean("off_status" + deviceAddress, false));

        if (switchScheduleOn.isActivated()) {
            setTimeOnBtn.setVisibility(View.VISIBLE);
        }
        setTimeOnBtn.setText(p.getInt("timeOnHour" + deviceAddress, 0) + ":" + p.getInt("timeOnMinute" + deviceAddress, 0));

        if (switchScheduleOff.isActivated()) {
            setTimeOffBtn.setVisibility(View.VISIBLE);
        }
        setTimeOffBtn.setText(p.getInt("timeOffHour" + deviceAddress, 0) + ":" + p.getInt("timeOffMinute" + deviceAddress, 0));
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

    public void switchScheduleOn(View view) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }

        ImageButton btn = (ImageButton) view;
        if (!btn.isActivated()) {
            setTimeOnBtn.setVisibility(View.VISIBLE);
            setTimeOnBtn.performClick();
        } else {
            setTimeOnBtn.setVisibility(View.GONE);
            device.stopScheduleOn();
        }

        btn.setActivated(!btn.isActivated());

        SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
        p.edit().putBoolean("on_status" + deviceAddress, btn.isActivated()).apply();
    }

    public void switchScheduleOff(View view) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }

        ImageButton btn = (ImageButton) view;
        if (!btn.isActivated()) {
            setTimeOffBtn.setVisibility(View.VISIBLE);
            setTimeOnBtn.performClick();
        } else {
            setTimeOffBtn.setVisibility(View.GONE);
            device.stopScheduleOff();
        }
        btn.setActivated(!btn.isActivated());

        SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
        p.edit().putBoolean("off_status" + deviceAddress, btn.isActivated()).apply();
    }

    public void setTimeOn(View view) {
        SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
        int h = p.getInt("timeOnHour" + deviceAddress, 0);
        int m = p.getInt("timeOnMinute" + deviceAddress, 0);
        TimePickerDialog timeOnPicker = new TimePickerDialog(this, timeOnListener, h, m, true);
        timeOnPicker.show();
    }

    public void setTimeOff(View view) {
        SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
        int h = p.getInt("timeOffHour" + deviceAddress, 0);
        int m = p.getInt("timeOffMinute" + deviceAddress, 0);
        TimePickerDialog timeOffPicker = new TimePickerDialog(this, timeOffListener, h, m, true);
        timeOffPicker.show();
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

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (!serviceBounded) {
            Toast.makeText(this, "正在准备服务，请稍后再试...", Toast.LENGTH_LONG).show();
            return;
        }
        device.changeBright(progress);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    private TimePickerDialog.OnTimeSetListener timeOnListener = new TimePickerDialog.OnTimeSetListener() {
        @Override
        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
            SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
            p.edit().putInt("timeOnHour" + deviceAddress, hourOfDay).putInt("timeOnMinute" + deviceAddress, minute).apply();
            setTimeOnBtn.setText(hourOfDay + ":" + minute);
            device.startScheduleOn(hourOfDay, minute);
        }
    };

    private TimePickerDialog.OnTimeSetListener timeOffListener = new TimePickerDialog.OnTimeSetListener() {
        @Override
        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
            SharedPreferences p = getSharedPreferences("timer", Context.MODE_PRIVATE);
            p.edit().putInt("timeOffHour" + deviceAddress, hourOfDay).putInt("timeOffMinute" + deviceAddress, minute).apply();
            setTimeOffBtn.setText(hourOfDay + ":" + minute);
            device.startScheduleOff(hourOfDay, minute);
        }
    };


}
