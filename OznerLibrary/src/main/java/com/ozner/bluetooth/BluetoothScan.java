package com.ozner.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.SparseArray;

import com.ozner.XObject;
import com.ozner.util.ByteUtil;
import com.ozner.util.Helper;
import com.ozner.util.dbg;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@SuppressLint("NewApi")
public class BluetoothScan extends XObject {

    final short Service_UUID = (short)0xFFF0;

    public static final String Extra_Service_Data="ble_service_data";
    public static final String Extra_Manufacturer_Specific ="ble_manufacturer_specific";
    public static final String Extra_RSSI = "RSSI";
    public static final String Extra_Address = "Address";
    final static byte GAP_ADTYPE_MANUFACTURER_SPECIFIC = (byte) 0xff;
    final static byte GAP_ADTYPE_SERVICE_DATA = 0x16;

    /**
     * 扫描开始广播,无附加数据
     */
    //public final static String ACTION_SCANNER_START = "com.ozner.bluetooth.sanner.start";

    /**
     * 扫描停止广播
     */
    //public final static String ACTION_SCANNER_STOP = "com.ozner.bluetooth.sanner.stop";

    /**
     * 找到设备广播,附加设备的MAC地址
     */
    public final static String ACTION_SCANNER_FOUND = "com.ozner.bluetooth.scanner.found";

    final static int FrontPeriod = 500;
    final static int BackgroundPeriod = 5000;

    Context mContext;
    BluetoothMonitor mMonitor;
    boolean isScanning = false;
    int scanPeriod = FrontPeriod;
    final HashMap<String, FoundDevice> mFoundDevice = new HashMap<>();
    private Thread scanThread;

    ScanRunnableIMP scanRunnableIMP =new ScanRunnableIMP();
    public BluetoothScan(Context context) {
        super(context);
        mContext = context;
        mMonitor = new BluetoothMonitor();
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mMonitor, filter);

    }
    public void StartScan() {
        if (isRunning())
            return;

        if (scanThread != null) {
            scanThread.interrupt();
        }
        scanThread = new Thread(scanRunnableIMP);
        scanThread.setName(this.getClass().getName());
        isScanning = true;
        scanThread.start();
    }

    public void StopScan() {
        isScanning = false;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
    }
    private class ScanRunnableIMP implements Runnable,BluetoothAdapter.LeScanCallback
    {
        @Override
        public void run() {
            BluetoothManager bluetoothManager = (BluetoothManager) mContext
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null) return;
            try {

                while (isScanning) {
                    if (adapter.isEnabled()) {
                        synchronized (BluetoothSynchronizedObject.getLockObject()) {
                            synchronized (mFoundDevice) {
                                mFoundDevice.clear();
                            }
                            if (adapter.getState() == BluetoothAdapter.STATE_OFF) {
                                Thread.sleep(1000);
                                continue;
                            }
                            adapter.startLeScan(this);
                            Thread.sleep(scanPeriod);
                            adapter.stopLeScan(this);
                            //dbg.i("扫描结束");
                        }
                    }
                    Thread.sleep(scanPeriod);
                    synchronized (mFoundDevice) {
                        if (mFoundDevice.size() > 0) {
                            for (FoundDevice found : mFoundDevice.values()) {
                                doFoundDevice(found);
                            }
                        }
                    }
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                adapter.stopLeScan(this);
            }
        }

        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            synchronized (mFoundDevice) {
                if (mFoundDevice.containsKey(device.getAddress())) {
                    mFoundDevice.remove(device.getAddress());
                }
                FoundDevice found = new FoundDevice();
                found.device = device;
                found.rssi = rssi;
                found.scanRecord = scanRecord;
                mFoundDevice.put(device.getAddress(), found);
            }
        }
    }


    private void doFoundDevice(FoundDevice found)
    {
        if (Helper.StringIsNullOrEmpty(found.device.getName())) return;
        if (found.scanRecord == null) return;
        String address = found.device.getAddress();
        byte[] manufacturer_specific=null;
        byte[] service_data=null;
        byte[] scanRecord=found.scanRecord;
        int pos=0;
        while (pos<scanRecord.length) {
            try {
                int len = scanRecord[pos++];
                if (len > 0) {
                    byte flag = scanRecord[pos];
                    if (len > 1) {
                        switch (flag)
                        {
                            case GAP_ADTYPE_MANUFACTURER_SPECIFIC:
                                manufacturer_specific = Arrays.copyOfRange(scanRecord,
                                        pos+1 , pos+len);
                                break;
                            case GAP_ADTYPE_SERVICE_DATA:
                            {
                                int uuid= (short)(scanRecord[pos]<<16)+scanRecord[pos+1];
                                if (uuid==Service_UUID)
                                {
                                    service_data = Arrays.copyOfRange(scanRecord, pos+3 , pos + len);
                                }
                            }
                            break;
                        }

                    }
                }
                pos += len;
                if (pos >= scanRecord.length)
                    break;
            } catch (Exception e) {
                dbg.e(e.toString());
                return;
            }
        }


        Intent intent = new Intent(ACTION_SCANNER_FOUND);
        intent.putExtra(Extra_Address, address);
        intent.putExtra(Extra_RSSI, found.rssi);
        intent.putExtra(Extra_Manufacturer_Specific,manufacturer_specific);
        intent.putExtra(Extra_Service_Data,service_data);
        mContext.sendBroadcast(intent);
    }




    public boolean isRunning() {
        return scanThread != null && scanThread.isAlive();
    }


    @Override
    protected void doChangeRunningMode() {
        if (getRunningMode() == RunningMode.Background) {
            scanPeriod = BackgroundPeriod;
        } else if (getRunningMode() == RunningMode.Foreground) {
            scanPeriod = FrontPeriod;
        }
    }


    public BluetoothDevice getDevice(String address) {
        BluetoothManager bluetoothManager = (BluetoothManager) mContext
                .getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        return adapter.getRemoteDevice(address);
    }



    /**
     * 用来接收系统蓝牙开关信息,打开开启自动扫描,关闭就关掉
     */
    class BluetoothMonitor extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED
                    .equals(intent.getAction())) {
                BluetoothManager bluetoothManager = (BluetoothManager) mContext
                        .getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bluetoothManager.getAdapter();
                if (adapter.getState() == BluetoothAdapter.STATE_OFF) {
                    StopScan();
                } else if (adapter.getState() == BluetoothAdapter.STATE_ON) {
                    StartScan();
                }
            }
        }
    }

    class FoundDevice {
        public BluetoothDevice device;
        public byte[] scanRecord;
        public int rssi;
    }

}
