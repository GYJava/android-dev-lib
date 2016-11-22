package com.ozner.wifi;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.aylanetworks.aaml.AylaCache;
import com.aylanetworks.aaml.AylaDevice;
import com.aylanetworks.aaml.AylaHostScanResults;
import com.aylanetworks.aaml.AylaHttpServer;
import com.aylanetworks.aaml.AylaLanMode;
import com.aylanetworks.aaml.AylaNetworks;
import com.aylanetworks.aaml.AylaSetup;
import com.aylanetworks.aaml.AylaSystemUtils;
import com.aylanetworks.aaml.AylaUser;
import com.mxchip.jmdns.JmdnsAPI;
import com.mxchip.jmdns.JmdnsListener;
import com.ozner.device.BaseDeviceIO;
import com.ozner.device.NotSupportDeviceException;
import com.ozner.device.OznerDevice;
import com.ozner.device.OznerDeviceManager;
import com.ozner.util.Helper;
import com.ozner.util.HttpUtil;
import com.ozner.util.dbg;
import com.ozner.wifi.ayla.AylaIO;
import com.ozner.wifi.ayla.AylaIOManager;
import com.ozner.wifi.mxchip.MXChipIO;
import com.ozner.wifi.mxchip.Pair.ConfigurationDevice;
import com.ozner.wifi.mxchip.Pair.EasyLinkSender;
import com.ozner.wifi.mxchip.Pair.FTC;
import com.ozner.wifi.mxchip.Pair.FTC_Listener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhiyongxu on 16/5/12.
 */
public class WifiPair {

    public interface WifiPairCallback {
        /**
         * 开始查找AYLA设备
         */
        void onStartPairAyla();

        /**
         * 开始配对庆科设备
         */
        void onStartPariMxChip();


        /**
         * 开始发送WIFI设置信息，AYLA 找到设备时掉用
         */
        void onSendConfiguration();

        /**
         * 开始激活注册设备
         */
        void onActivateDevice();
        /**
         * 等待设备连接WIFI
         */
        void onWaitConnectWifi();

        /**
         * 配网完成
         *
         * @param io 配对好的设备IO接口
         */
        void onPairComplete(BaseDeviceIO io);

        /**
         * 配网失败
         *
         * @param e 失败的异常
         */
        void onPairFailure(Exception e);
    }

    /**
     * 已经有一个配网过程正在运行
     */
    public class PairRunningException extends Exception
    {}

    /**
     * 无线设备没打开或无连接
     */
    public static class WifiStatusException extends Exception {
    }

    public static class NullSSIDException extends Exception {
    }

    public static class TimeoutException extends Exception {
    }
    public static class UnknownException extends Exception {}

    /**
     *   其它用户拥有这个设备
     */
    public static class AylaOtherUserException extends Exception {
        AylaOtherUserException(String message)
        {
            super(message);
        }

    }
    public static class AylaException extends Exception{
        public AylaException(String message)
        {
            super(message);
        }
    }

    private WifiPairCallback callback=null;
    private String ssid=null;
    private String password=null;
    private Context context;

    private int runPairCount=0;
    private Date startRunTime;
    private ThreadHandler runHandler=null;
    private int errorCount=0;
    Object waitObject = new Object();
    private void wait(int time) throws InterruptedException {
        synchronized (waitObject) {
            waitObject.wait(time);
        }
    }

    private void set() {
        synchronized (waitObject) {
            waitObject.notify();
        }
    }


    public WifiPair(Context context, WifiPairCallback callback)
            throws NullSSIDException
    {
        this.context=context;
        this.callback=callback;
    }

    private void doPairFailure(Exception ex)
    {

        callback.onPairFailure(ex);
        stop();
    }
    private void doComplete(BaseDeviceIO io)
    {
        callback.onPairComplete(io);
        stop();
    }

    public static boolean isAylaSSID(String ssid)
    {
        return ssid.matches(AylaIOManager.gblAmlDeviceSsidRegex);
    }
    class MXChipPairImp implements FTC_Listener, Runnable, JmdnsListener {
        /**
         * 默认1分钟配网超时
         */
        final static int ConfigurationTimeout = 30000;
        final static int MDNSTimeout = 60000;
        String deviceMAC = "";



        ConfigurationDevice device = null;


        @Override
        public void onFTCfinished(String jsonString) {
            device = ConfigurationDevice.loadByFTCJson(jsonString);
            set();
        }

        @Override
        public void isSmallMTU(int MTU) {

        }

        private String getToken() {
            return String.valueOf(System.currentTimeMillis());
        }

        private String ActiveDevice() throws IOException {
            com.alibaba.fastjson.JSONObject jsonObject = new com.alibaba.fastjson.JSONObject();
            String url = "http://" + device.localIP + ":" + device.localPort + "/dev-activate";
            jsonObject.put("login_id", device.loginId);
            jsonObject.put("dev_passwd", device.devPasswd);
            jsonObject.put("user_token", getToken());
            String retString = HttpUtil.postJSON(url, jsonObject.toJSONString(), "US-ASCII");
            com.alibaba.fastjson.JSONObject ret = (com.alibaba.fastjson.JSONObject) JSON.parse(retString);
            return ret.getString("device_id");
        }

        private String Authorize() throws IOException {
            com.alibaba.fastjson.JSONObject jsonObject = new com.alibaba.fastjson.JSONObject();
            String url = "http://" + device.localIP + ":" + device.localPort + "/dev-authorize";
            jsonObject.put("login_id", device.loginId);
            jsonObject.put("dev_passwd", device.devPasswd);
            jsonObject.put("user_token", getToken());
            String retString = HttpUtil.postJSON(url, jsonObject.toJSONString(), "US-ASCII");
            com.alibaba.fastjson.JSONObject ret = (com.alibaba.fastjson.JSONObject) JSON.parse(retString);
            return ret.getString("device_id");
        }

        @Override
        public void run() {
            try {

                JmdnsAPI mdnsApi = new JmdnsAPI(context);
                device = null;
                deviceMAC = null;
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wifiManager.getConnectionInfo();

                if (info.getSupplicantState() != SupplicantState.COMPLETED) {
                    doPairFailure(new WifiStatusException());
                    return;
                }
                callback.onStartPariMxChip();

                FTC ftc = new FTC(context, this);
                try {
                    ftc.startListen();
                    EasyLinkSender sender = new EasyLinkSender();
                    sender.setSettings(ssid.trim(), password, info.getIpAddress());
                    Date t = new Date();

                    while (device == null) {
                        sender.send_easylink_v3();
                        Thread.sleep(100);
                        sender.send_easylink_v2();
                        Thread.sleep(100);
                        Date now = new Date();
                        if ((now.getTime() - t.getTime()) > ConfigurationTimeout) {
                            break;
                        }
                    }
                    sender.close();
                } finally {
                    ftc.stop();
                }

                if (device == null) {
                    runNext();
                    return;
                }

                if ((device.activated) && (!Helper.StringIsNullOrEmpty(device.activeDeviceID))) {
                    int p = device.activeDeviceID.indexOf("/");
                    if (p > 0) {
                        String tmp = device.activeDeviceID.substring(p + 1).toUpperCase();
                        if (tmp.length() == 12) {
                            String mac = tmp.substring(0, 2) + ":" +
                                    tmp.substring(2, 4) + ":" +
                                    tmp.substring(4, 6) + ":" +
                                    tmp.substring(6, 8) + ":" +
                                    tmp.substring(8, 10) + ":" +
                                    tmp.substring(10, 12);

                            String type=device.activeDeviceID.substring(0,p);
                            if (Helper.StringIsNullOrEmpty(type))
                            {
                                doPairFailure(new UnknownException());
                                return;
                            }
                            device.Type=type;
                            MXChipIO io = OznerDeviceManager.Instance().ioManagerList().mxChipIOManager().
                                    createMXChipDevice(mac, device.Type);
                            if (io != null) {
                                doComplete(io);
                            } else
                                doPairFailure(new UnknownException());
                            return;
                        }
                    }
                }
                callback.onWaitConnectWifi();

                //Thread.sleep(2000);
                mdnsApi.startMdnsService("_easylink._tcp.local.", this);
                WifiPair.this.wait(MDNSTimeout);
                mdnsApi.stopMdnsService();

                if (Helper.StringIsNullOrEmpty(deviceMAC)) {
                    doPairFailure(new TimeoutException());
                    return;
                }
                callback.onActivateDevice();

                String deviceId = "";
                errorCount=0;
                while(errorCount<3) {
                    try {
                        deviceId = ActiveDevice();
                    } catch (FileNotFoundException fe) {
                        Thread.sleep(1000);
                        errorCount++;
                        continue;
                    }
                    break;
                }

                if (Helper.StringIsNullOrEmpty(deviceId)) {
                    doPairFailure(new UnknownException());
                    return;
                }
                int p=deviceId.indexOf('/');
                if (p<0)
                {
                    doPairFailure(new UnknownException());
                }else
                {
                    String type=deviceId.substring(0,p);
                    if (Helper.StringIsNullOrEmpty(type))
                    {
                        doPairFailure(new UnknownException());
                        return;
                    }
                    device.Type=type;
                }
                //Authorize();
//                if (Helper.StringIsNullOrEmpty(deviceId)) {
//                    doPairFailure(null);
//                }
                MXChipIO io = OznerDeviceManager.Instance().ioManagerList().mxChipIOManager().
                        createMXChipDevice(deviceMAC, device.Type);
                io.name=device.name;
                if (io != null) {
                    doComplete(io);
                } else
                    doPairFailure(null);
            } catch (Exception e) {
                doPairFailure(e);
            } finally {

            }
        }

        @Override
        public void onJmdnsFind(JSONArray jsonArray) {
            if (device == null) return;
            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    JSONObject object = jsonArray.getJSONObject(i);
                    String name = object.getString("deviceName");
                    int p = name.indexOf("#");
                    if (p > 0) {
                        name = name.substring(p + 1);
                        if (device.name.indexOf(name) > 0) {
                            deviceMAC = object.getString("deviceMac");
                            device.localIP = object.getString("deviceIP");
                            set();
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
        }
    }

    class AylaPairImp implements Runnable
    {
        private void doRegister(final AylaDevice device)
        {
            //callback.onActivateDevice();
            device.registrationType=AylaNetworks.AML_REGISTRATION_TYPE_AP_MODE;
            dbg.d("start registerNewDevice");
            AylaLanMode.enable(null,null);
            device.registerNewDevice(new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    dbg.d("recv registerNewDevice:%s",msg.toString());
                    if (msg.what==AylaNetworks.AML_ERROR_OK)
                    {
                        String jsonResults = (String)msg.obj;
                        AylaDevice device = AylaSystemUtils.gson.fromJson(jsonResults,  AylaDevice.class);
                        AylaIO io= OznerDeviceManager.Instance().ioManagerList().aylaIOManager().createAylaIO(device);
                        doComplete(io);

                        aylaFinally();

                    }else
                    {
                        if (errorCount<5)
                        {
                            errorCount++;
                            dbg.d("register error",msg.toString());
                            doRegister(device);
                            aylaFinally();

                        }
                        else {
                            String error = "registerNewDevice:"+msg.toString();
                            doPairFailure(new AylaOtherUserException(error));
                            aylaFinally();

                        }
                    }
                    super.handleMessage(msg);
                }
            });
        }

        private void confirmNewDeviceToService()
        {
            //确认设备连接WIFI
            callback.onWaitConnectWifi();
            dbg.d("start confirmNewDeviceToServiceConnection");
            AylaSetup.confirmNewDeviceToServiceConnection(new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    dbg.d("recv confirmNewDeviceToServiceConnection:%s",msg.toString());
                    if (msg.what==AylaNetworks.AML_ERROR_OK)
                    {
                        String jsonResults = (String)msg.obj;
                        AylaDevice device = AylaSystemUtils.gson.fromJson(jsonResults,  AylaDevice.class);

                        String json= null;
                        try {
                            json = HttpUtil.get(String.format("http://%s/status.json",device.lanIp));
                            if (!Helper.StringIsNullOrEmpty(json))
                            {
                                com.alibaba.fastjson.JSONObject object= JSON.parseObject(json);
                                String mac=object.getString("mac").toUpperCase();
                                dbg.d("设备JSON:%s",json);
                                BaseDeviceIO io=OznerDeviceManager.Instance().ioManagerList().aylaIOManager().getAvailableDevice(mac);
                                if (io==null)
                                {
                                    doRegister(device);
                                }else
                                {
                                    device.mac=mac;
                                    device.model=object.getString("Model");
                                    doComplete(io);
                                    set();
                                }
                            }
                        } catch (IOException e) {
                            set();
                            e.printStackTrace();
                        }
                        errorCount=0;
                        doRegister(device);
                    }else
                    {
                        if (errorCount<5)
                        {
                            errorCount++;
                            confirmNewDeviceToService();
                        }
                        else {
                            String error = "confirmNewDeviceToServiceConnection:"+msg.toString();
                            doPairFailure(new AylaException(error));
                            set();
                            aylaFinally();
                        }

                    }

                    super.handleMessage(msg);
                }
            });
        }
        private void aylaFinally()
        {
            //AylaSetup.exit();
        }

        private void connectNewDeviceToService()
        {
            Map<String, Object> callParams = new HashMap<String, Object>();
            callParams.put(AylaNetworks.AML_SETUP_LOCATION_LONGITUDE, 0.00d);
            callParams.put(AylaNetworks.AML_SETUP_LOCATION_LATITUDE, 0.00d);
            //AylaModule device = AylaSystemUtils.gson.fromJson(jsonResults, AylaModule.class);

            AylaSetup.lanSsid=ssid;
            AylaSetup.lanPassword=password;

            callback.onSendConfiguration();
            dbg.d("start connectNewDeviceToService");
            //配置AYLA 设备的WIFI信息
            AylaSetup.connectNewDeviceToService(new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    dbg.d("recv connectNewDeviceToService:%s",msg.toString());

                    if (msg.what==AylaNetworks.AML_ERROR_OK)
                    {
                        confirmNewDeviceToService();
                    }else
                    {
                        String error="connectNewDeviceToService:"+msg.toString();

                        doPairFailure(new AylaException(error));
                        set();
                        aylaFinally();
                    }

                    super.handleMessage(msg);
                }

            }, callParams);

        }
        boolean isConnectToNewDevice=false;
        //开始连接AYLA AP
        private void connectDevice(AylaHostScanResults result) {
            AylaLanMode.disable();
            isConnectToNewDevice=false;

            AylaSetup.newDevice.hostScanResults = result;
            //连接设备
            dbg.d("start connectToNewDevice");
            AylaSetup.connectToNewDevice(new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    dbg.e("----------------------------------------------------------------------");
                    dbg.d("recv connectToNewDevice:%s",msg.toString());
                    String jsonResults = (String) msg.obj;
                    if (msg.what == AylaNetworks.AML_ERROR_OK) {
                        if (!isConnectToNewDevice) {
                            isConnectToNewDevice = true;
                            connectNewDeviceToService();

                        }
                    }else
                    {

                        String error="connectToNewDevice:"+msg.toString();

                        doPairFailure(new AylaException(error));
                        set();
                        aylaFinally();
                    }
                }
            });
        }



        @Override
        public void run() {
            if (AylaUser.getCurrent().getAccessToken()==null)
            {
                callback.onPairFailure(new AylaException("not login"));
                return;
            }
            callback.onStartPairAyla();

            AylaSetup.returnHostScanForNewDevices(new Handler()
            {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.what == AylaNetworks.AML_ERROR_OK) {
                        String jsonResults = (String) msg.obj;
                        AylaHostScanResults[] scanResults = AylaSystemUtils.gson.fromJson(jsonResults, AylaHostScanResults[].class);
                        if (scanResults.length>0)
                        {
                            connectDevice(scanResults[0]);

                        }else
                        {
                            aylaFinally();
                            runNext();
                        }
                    }else
                    {
                        aylaFinally();
                        runNext();
                    }

                }
            });
        }

    }

    public void pair(String ssid,String password) throws PairRunningException
    {
        runPairCount=1;
        errorCount=0;
        this.ssid=ssid;
        this.password=password;
        if (runHandler!=null)
        {
            throw new PairRunningException();
        }
        startRunTime=new Date();
        runHandler=new ThreadHandler();
        runNext();
    }
    private void runNext()
    {

        Date now = new Date();
        if ((now.getTime() - startRunTime.getTime()) > 2*60*1000) {
            doPairFailure(new TimeoutException());
            return;
        }
        if ((runPairCount % 3)==0)
        {
            runHandler.post(new MXChipPairImp());
        }else
        {
            runHandler.post(new AylaPairImp());
        }
        //runHandler.post(new MXChipPairImp());
        runPairCount++;

    }

    public void stop()
    {
        runHandler.close();
        runHandler=null;
    }



}
