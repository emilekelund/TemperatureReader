package com.example.temperaturereader.activities;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.temperaturereader.R;
import com.example.temperaturereader.services.BleResistanceService;
import com.example.temperaturereader.services.GattActions;
import com.example.temperaturereader.utilities.ExponentialMovingAverage;
import com.example.temperaturereader.utilities.MsgUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import static com.example.temperaturereader.services.GattActions.ACTION_GATT_RESISTANCE_EVENTS;
import static com.example.temperaturereader.services.GattActions.EVENT;
import static com.example.temperaturereader.services.GattActions.RESISTANCE_DATA;

public class ResistanceReadActivity extends Activity {
    private static final String TAG = ResistanceReadActivity.class.getSimpleName();

    private BluetoothDevice mSelectedDevice = null;
    private TextView mResistanceView;
    private TextView mTemperatureView;
    private TextView mDeviceView;
    private TextView mStatusView;
    private String mDeviceAddress;
    ILineDataSet set = null;
    private ExponentialMovingAverage ewmaFilter = new ExponentialMovingAverage(0.02);

    private BleResistanceService mBluetoothLeService;

    private LineChart mChart;
    private Thread thread;
    private boolean plotData = true;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resistance_read);

        // Setup UI references
        mResistanceView = findViewById(R.id.resistanceValueViewer);
        mTemperatureView = findViewById(R.id.tempViewer);
        mDeviceView = findViewById(R.id.device_view);
        mStatusView = findViewById(R.id.status_view);


        final Intent intent = getIntent();
        mSelectedDevice = intent.getParcelableExtra(ScanActivity.SELECTED_DEVICE);

        if (mSelectedDevice == null) {
            MsgUtils.createDialog("Error", "No device found", this).show();
            mDeviceView.setText(R.string.no_resistance_board);
        } else {
            mDeviceView.setText(mSelectedDevice.getName());
            mDeviceAddress = mSelectedDevice.getAddress();
        }

        // Setup UI reference to the chart
        mChart = findViewById(R.id.temperatureChart);

        // enable description text
        mChart.getDescription().setEnabled(true);

        // enable touch gestures
        mChart.setTouchEnabled(true);

        // enable scaling and dragging
        mChart.setDragEnabled(true);
        mChart.setScaleEnabled(true);
        mChart.setDrawGridBackground(false);

        // if disabled, scaling can be done on x- and y-axis separately
        mChart.setPinchZoom(true);

        // set an alternative background color
        mChart.setBackgroundColor(Color.WHITE);

        LineData data = new LineData();
        data.setValueTextColor(Color.BLACK);

        // add empty data
        mChart.setData(data);

        // get the legend (only possible after setting data)
        Legend l = mChart.getLegend();

        // modify the legend ...
        l.setForm(Legend.LegendForm.LINE);
        l.setTextColor(Color.BLACK);

        // X-axis setup
        XAxis bottomAxis = mChart.getXAxis();
        bottomAxis.setTextColor(Color.BLACK);
        bottomAxis.setDrawGridLines(true);
        bottomAxis.setPosition(XAxis.XAxisPosition.BOTTOM_INSIDE);
        bottomAxis.setAvoidFirstLastClipping(true);
        bottomAxis.setEnabled(true);

        // Y-axis setup
        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setTextColor(Color.BLACK);
        leftAxis.setDrawGridLines(true);
        leftAxis.setAxisMaximum(40f);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);
        // Disable right Y-axis
        YAxis rightAxis = mChart.getAxisRight();
        rightAxis.setEnabled(false);

        mChart.getAxisLeft().setDrawGridLines(true);
        mChart.getXAxis().setDrawGridLines(false);
        mChart.setDrawBorders(false);

        feedMultiple();


        // Bind to BleImuService
        // We use onResume or onStart to register a broadcastReceiver
        Intent gattServiceIntent = new Intent(this, BleResistanceService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        if (thread != null) {
            thread.interrupt();
        }
    }

    /*
    NB! Unbind from service when this activity is destroyed (the service itself
    might then stop).
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
        thread.interrupt();
    }

    /*
    Callback methods to manage the (BleImu)Service lifecycle.
    */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BleResistanceService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.i(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
            Log.i(TAG, "onServiceConnected");
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
            Log.i(TAG, "onServiceDisconnected");
        }
    };

    /*
    A method to add our resistance entries to the chart
     */
    private void addEntry(double temperature) {
        LineData data = mChart.getData();


        if (data != null) {
            set = data.getDataSetByIndex(0);
            // set.addEntry(.....); Can be called as well
        }

        if (set == null) {
            set = createSet();
            assert data != null;
            data.addDataSet(set);
        }

        assert data != null;
        data.addEntry(new Entry(set.getEntryCount(), (float) temperature), 0);
        data.notifyDataChanged();

        // let the chart know it's data has changed
        mChart.notifyDataSetChanged();

        // limit the number of visible entries
        mChart.setVisibleXRangeMaximum(50);
        //mChart.setVisibleYRange(0,30, YAxis.AxisDependency.LEFT);

        // move to the latest entry
        //mChart.moveViewToX(data.getEntryCount());
        mChart.moveViewTo(data.getEntryCount(), (float) temperature, YAxis.AxisDependency.LEFT);
    }

    private LineDataSet createSet() {

        LineDataSet set = new LineDataSet(null, "Temperature");
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setLineWidth(3f);
        set.setColor(Color.RED);
        set.setHighlightEnabled(false);
        set.setDrawValues(false);
        set.setDrawCircles(true);
        set.setCircleRadius(2f);
        return set;
    }

    private void feedMultiple() {

        if (thread != null){
            thread.interrupt();
        }

        thread = new Thread(new Runnable() {

            @Override
            public void run() {
                while (true){
                    plotData = true;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        thread.start();
    }


    /*
    A BroadcastReceiver handling various events fired by the Service, see GattActions.Event.
    */
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_GATT_RESISTANCE_EVENTS.equals(action)) {
                GattActions.Event event = (GattActions.Event) intent.getSerializableExtra(EVENT);
                if (event != null) {
                    switch (event) {
                        case GATT_CONNECTED:
                        case GATT_DISCONNECTED:
                            mResistanceView.setText(R.string.board_disconnected);
                        case GATT_SERVICES_DISCOVERED:
                        case RESISTANCE_SERVICE_DISCOVERED:
                            mStatusView.setText(event.toString());
                            mResistanceView.setText(R.string.calculating_resistance);
                            mTemperatureView.setText(R.string.calculating_temperature);
                            break;
                        case DATA_AVAILABLE:
                            final double resistance = intent.getDoubleExtra(RESISTANCE_DATA,0);
                            double temperature;
                            double ewmaResistance = 0;
                            ewmaResistance = ewmaFilter.average(resistance);
                            Log.i(TAG, "EWMA: " + ((double)Math.round((ewmaResistance * (1 * Math.pow(10, -3))) * 10d) / 10d));
                            temperature = resistanceToTemp(ewmaResistance);
                            Log.i(TAG, "Temp: " + temperature);
                            mResistanceView.setText(String.format("%.1fk\u2126", (ewmaResistance * (1*Math.pow(10, -3))))); // Display in kiloOhm
                            mTemperatureView.setText(String.format("%.1f\u00B0C", temperature));

                            if (plotData) {
                                addEntry(temperature);
                                plotData = false;
                            }


                            break;
                        case RESISTANCE_SERVICE_NOT_AVAILABLE:
                            mStatusView.setText(event.toString());
                            break;
                        default:
                            mStatusView.setText(R.string.device_unreachable);
                    }
                }
            }
        }
    };

    // Intent filter for broadcast updates from BleHeartRateServices
    private IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_GATT_RESISTANCE_EVENTS);
        return intentFilter;
    }

    private double resistanceToTemp(double resistance) {
        return (-3.4331 * ((double)Math.round((resistance * (1 * Math.pow(10, -3))) * 10d) / 10d)) + 958.29;
    }


}