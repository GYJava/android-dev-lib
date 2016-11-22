package com.ozner.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.ozner.XObject;
import com.ozner.util.Helper;
import com.ozner.util.dbg;

import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;

/**
 * @author zhiyongxu
 *         浩泽设备基类
 */
public abstract class OznerDevice extends XObject {
    public final static String Extra_Address = "Address";
    private String address;
    private BaseDeviceIO deviceIO;
    private DeviceSetting setting;
    private String Type;

    final static deviceTimerLoop glb_timerLoop = new deviceTimerLoop();

    static class deviceTimerLoop {
        //一个全局循环来完成设备的定时循环操作
        private Thread timeThread = null;
        Hashtable<OznerDevice, Date> devices = new Hashtable<>();

        public void addDevice(OznerDevice device) {
            synchronized (devices) {
                devices.put(device, new Date(0));
            }
        }

        public void removeDevice(OznerDevice device) {
            synchronized (devices) {
                devices.remove(device);
            }
        }


        private Runnable timerRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(1000);
                        if (XObject.getRunningMode() != RunningMode.Foreground) continue;
                        ArrayList<OznerDevice> list = null;
                        synchronized (devices) {
                            list = new ArrayList<>(devices.keySet());
                        }
                        for (OznerDevice device : list) {
                            Date lastTime = devices.get(device);
                            Date now = new Date();
                            long time = now.getTime() - lastTime.getTime();
                            //判断当前时间－上次运行时间是否大于设备要求的运行周期
                            if (time >= device.getTimerDelay()) {
                                try {
                                    device.doTimer();
                                } catch (Exception e) {
                                    dbg.e("doTime:%s", e.toString());
                                }
                                synchronized (devices) {
                                    //更新下设备运行时间，给下次检查使用
                                    //判断下设备是否还在列表里面
                                    if (devices.containsKey(device))
                                        devices.put(device, new Date());
                                }
                            }
                        }

                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        };

        public deviceTimerLoop() {
            timeThread = new Thread(timerRunnable);
            timeThread.start();
        }
    }

    BroadcastReceiver statusBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BaseDeviceIO.ACTION_DEVICE_CONNECTED.equals(action)) {
                String addr = intent.getStringExtra(BaseDeviceIO.Extra_Address);
                if (address.equals(addr)) {
                    glb_timerLoop.addDevice(OznerDevice.this);
                }
                return;
            }

            if (BaseDeviceIO.ACTION_DEVICE_DISCONNECTED.equals(action)) {
                String addr = intent.getStringExtra(BaseDeviceIO.Extra_Address);
                if (address.equals(addr)) {
                    glb_timerLoop.removeDevice(OznerDevice.this);
                }
                return;
            }
        }
    };

    public OznerDevice(Context context, String Address, String Type, String Setting) {
        super(context);
        this.address = Address;
        this.Type = Type;
        this.setting = initSetting(Setting);
        if (Helper.StringIsNullOrEmpty(setting.name())) {
            setting.name(getDefaultName());
        }
        IntentFilter intentFilter=new IntentFilter();
        intentFilter.addAction(BaseDeviceIO.ACTION_DEVICE_CONNECTED);
        intentFilter.addAction(BaseDeviceIO.ACTION_DEVICE_DISCONNECTED);

        context.registerReceiver(statusBroadcastReceiver,intentFilter);
    }

    /**
     * 获取设备定时查询操作的循环周期
     *
     * @return 返回查询操作的循环周期, 默认Integer.MAX_VALUE
     */
    public int getTimerDelay() {
        return Integer.MAX_VALUE;
    }

    /**
     * 定时查询操作
     */
    protected void doTimer() {

    }

    /**
     * 设备数值改变
     */
    public final static String ACTION_DEVICE_UPDATE = "com.ozner.device.update";

    protected void doUpdate() {
        Intent intent = new Intent(ACTION_DEVICE_UPDATE);
        intent.putExtra(Extra_Address, Address());
        context().sendBroadcast(intent);
    }


    protected abstract String getDefaultName();

    public abstract Class<?> getIOType();

    /**
     * 设备类型
     */
    public String Type() {
        return Type;
    }

    /**
     * 设置对象
     */
    public DeviceSetting Setting() {
        return setting;
    }


    /**
     * 地址
     */
    public String Address() {
        return address;
    }

    /**
     * 名称
     */
    public String getName() {
        return setting.name();
    }


    /**
     * 蓝牙控制对象
     *
     * @return NULL=没有蓝牙连接
     */
    public BaseDeviceIO IO() {
        return deviceIO;
    }


    /**
     * 判断设备是否连接
     */
    public BaseDeviceIO.ConnectStatus connectStatus() {
        if (deviceIO == null)
            return BaseDeviceIO.ConnectStatus.Disconnect;
        return deviceIO.connectStatus();
    }

    protected DeviceSetting initSetting(String Setting) {
        DeviceSetting setting = new DeviceSetting();
        setting.load(Setting);
        if (Helper.StringIsNullOrEmpty(setting.name())) {
            setting.name(getDefaultName());
        }
        return setting;
    }

    /**
     * 通知设备将设置存储
     */
    public void saveSettings() {

    }

    /**
     * 通知设备设置变更
     */
    public void updateSettings() {
    }


    protected abstract void doSetDeviceIO(BaseDeviceIO oldIO, BaseDeviceIO newIO);

    /**
     * 在后台模式时判断接口是否包含有效数据,如果是则连接,否不进行连接
     *
     * @param io 接口IO
     * @return true包含数据
     */
    protected boolean doCheckAvailable(BaseDeviceIO io) {
        return true;
    }

    public boolean Bind(BaseDeviceIO deviceIO) throws DeviceNotReadyException {

        if ((deviceIO != null) && (!deviceIO.getClass().equals(getIOType()))) {
            throw new ClassCastException();
        }

        if (this.deviceIO == deviceIO)
            return false;

        if ((getRunningMode() == RunningMode.Background) && (deviceIO != null)) {
            if (!doCheckAvailable(deviceIO)) return false;
        }

        BaseDeviceIO old = this.deviceIO;

        try {
            doSetDeviceIO(old, deviceIO);
        } catch (Exception e) {

        }

        if (this.deviceIO != null) {
            this.deviceIO = null;
        }

        this.deviceIO = deviceIO;


        if (deviceIO != null) {
            deviceIO.open();
            if (deviceIO.isReady()) {
                deviceIO.reCallDoReady();
            }
        } else {
            glb_timerLoop.removeDevice(this);
        }

        return true;
    }


}
