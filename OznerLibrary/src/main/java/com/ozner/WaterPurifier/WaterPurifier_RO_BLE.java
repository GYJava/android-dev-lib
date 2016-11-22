package com.ozner.WaterPurifier;

import android.content.Context;

import com.ozner.bluetooth.BluetoothIO;
import com.ozner.bluetooth.BluetoothScanResponse;
import com.ozner.cup.CupManager;
import com.ozner.device.BaseDeviceIO;
import com.ozner.device.OperateCallback;
import com.ozner.util.ByteUtil;
import com.ozner.util.dbg;
import com.ozner.wifi.mxchip.MXChipIO;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Created by zhiyongxu on 16/9/12.
 */
public class WaterPurifier_RO_BLE extends WaterPurifier {
    private static final int defaultAutoUpdatePeriod=1000;
    private static final byte opCode_request_info=(byte)0x20;
    private static final byte opCode_reset=(byte)0xa0;
    private static final byte opCode_respone_setting=(byte)0x21;
    private static final byte opCode_respone_water=(byte)0x22;
    private static final byte opCode_respone_filter=(byte)0x23;
    private static final byte opCode_respone_filterHis1=(byte)0x11;
    private static final byte opCode_respone_filterHis2=(byte)0x12;



    private static final byte param_request_settinginfo=1;
    private static final byte param_request_water_info=2;
    private static final byte param_request_filter_info=3;
    private static final byte param_request_filter_history_info=4;
    private SettingInfo settingInfo=new SettingInfo();
    private WaterInfo waterInfo=new WaterInfo();
    private FilterInfo filterInfo=new FilterInfo();

    public class SettingInfo
    {
        /**
         * RTC时间
         */
        public Date rtc;
        /**
         * 臭氧工作时间
         */
        public int Ozone_WorkTime;
        /**
         * 臭氧工作间隔
         */
        public int Ozone_Interval;
        public void fromBytes(byte[] bytes)
        {
            this.rtc = new Date(bytes[0] + 2000 - 1900, bytes[1] - 1, bytes[2], bytes[3],
                    bytes[4], bytes[5]);
            this.Ozone_Interval=bytes[6];
            this.Ozone_WorkTime=bytes[7];

        }
        @Override
        public String toString() {
            if (rtc==null) return "";
            return String.format("设备时间:%s\n臭氧工作时间:%d 间隔:%d",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(rtc),
                    Ozone_WorkTime,Ozone_Interval);
        }
    }

    public class WaterInfo
    {
        public int TDS1;
        public int TDS2;
        public int TDS1_RAW;
        public int TDS2_RAW;
        public int TDS_Temperature;

        public void fromBytes(byte[] bytes)
        {
            this.TDS1= ByteUtil.getShort(bytes,0);
            this.TDS2= ByteUtil.getShort(bytes,2);
            this.TDS1_RAW= ByteUtil.getShort(bytes,4);
            this.TDS2_RAW= ByteUtil.getShort(bytes,6);
            this.TDS_Temperature= ByteUtil.getShort(bytes,8);
        }

        @Override
        public String toString() {
            return String.format("TDS1:%d(%d) TDS2:%d(%d) 温度:%d",TDS1,TDS1_RAW,TDS2,TDS2_RAW,TDS_Temperature);
        }
    }

    public class FilterInfo
    {
        public int Filter_A_Time;
        public int Filter_B_Time;
        public int Filter_C_Time;

        public int Filter_A_Percentage;
        public int Filter_B_Percentage;
        public int Filter_C_Percentage;

        public void fromBytes(byte[] bytes)
        {
            Filter_A_Time=(int)ByteUtil.getUInt(bytes,0);
            Filter_B_Time=(int)ByteUtil.getUInt(bytes,4);
            Filter_C_Time=(int)ByteUtil.getUInt(bytes,8);
            Filter_A_Percentage=bytes[12];
            Filter_B_Percentage=bytes[13];
            Filter_C_Percentage=bytes[14];
        }

        @Override
        public String toString() {
            StringBuilder sb=new StringBuilder();
            sb.append(String.format("滤芯 A:%d %d%%\n",Filter_A_Time,Filter_A_Percentage));
            sb.append(String.format("滤芯 B:%d %d%%\n",Filter_B_Time,Filter_B_Percentage));
            sb.append(String.format("滤芯 C:%d %d%%\n",Filter_C_Time,Filter_C_Percentage));
            return sb.toString();
        }
    }

    private WaterPurifierIMP waterPurifierIMP=new WaterPurifierIMP();
    public static final int BLE_RO_ScanResponseType=0x11;
    public WaterPurifier_RO_BLE(Context context, String Address, String Model, String Setting) {
        super(context, Address, Model, Setting);
    }

    @Override
    public Class<?> getIOType() {
        return BluetoothIO.class;
    }

    @Override
    protected void doSetDeviceIO(BaseDeviceIO oldIO, BaseDeviceIO newIO) {
        if (oldIO != null) {
            oldIO.setOnInitCallback(null);
            oldIO.unRegisterStatusCallback(waterPurifierIMP);
            oldIO.setOnTransmissionsCallback(null);
            oldIO.setCheckTransmissionsCompleteCallback(null);
            //firmwareTools.bind(null);
        }
        if (newIO != null) {
            newIO.setOnTransmissionsCallback(waterPurifierIMP);
            newIO.setOnInitCallback(waterPurifierIMP);
            newIO.registerStatusCallback(waterPurifierIMP);
            newIO.setCheckTransmissionsCompleteCallback(waterPurifierIMP);
            //firmwareTools.bind((BluetoothIO) newIO);
        }
    }

    int requestCount=0;
    @Override
    protected void updateStatus(OperateCallback<Void> cb) {


            if ((requestCount%2)==0)
            {
                waterPurifierIMP.requestFilterInfo();
            }else
                waterPurifierIMP.requestWaterInfo();
            requestCount++;

    }

    @Override
    protected int getTDS1() {
        return waterInfo.TDS1;
    }

    @Override
    protected int getTDS2() {
        return waterInfo.TDS2;
    }

    @Override
    public String toString() {
        StringBuilder sb=new StringBuilder();
        sb.append(settingInfo.toString()+"\n");
        sb.append(waterInfo.toString()+"\n");
        sb.append(filterInfo.toString()+"\n");
        return sb.toString();
    }

    class WaterPurifierIMP  implements
            BluetoothIO.OnInitCallback,
            BluetoothIO.OnTransmissionsCallback,
            BluetoothIO.StatusCallback,
            BluetoothIO.CheckTransmissionsCompleteCallback
    {
        private byte calcSum(byte[] data,int size)
        {
            byte sum=0;
            for (int i=0;i<size;i++)
                sum+=data[i];
            return sum;
        }

        private void requestSettingInfo()
        {
            if (IO() != null) {
                byte[] bytes=new byte[3];
                bytes[0]=0x20;
                bytes[1]=1;
                bytes[2]=calcSum(bytes,2);
                IO().send(bytes);
                dbg.i("请求设置信息");
            }
        }
        private void requestWaterInfo()
        {
            if (IO() != null) {
                byte[] bytes=new byte[3];
                bytes[0]=0x20;
                bytes[1]=2;
                bytes[2]=calcSum(bytes,2);
                IO().send(bytes);
                dbg.i("请求水质信息");
            }
        }

        private void requestFilterInfo()
        {
            if (IO() != null) {
                byte[] bytes=new byte[3];
                bytes[0]=0x20;
                bytes[1]=3;
                bytes[2]=calcSum(bytes,2);
                IO().send(bytes);
                dbg.i("请求滤芯信息");
            }
        }
        private void requestFilterHisInfo()
        {
            if (IO() != null) {
                byte[] bytes=new byte[3];
                bytes[0]=0x20;
                bytes[1]=4;
                bytes[2]=calcSum(bytes,2);
                IO().send(bytes);
                dbg.i("请求滤芯历史信息");
            }
        }
        private void reset()
        {
            if (IO() != null) {
                byte[] bytes=new byte[2];
                bytes[0]=(byte)0xa0;
                bytes[1]=calcSum(bytes,2);
                IO().send(bytes);
                dbg.i("重启设备");
            }
        }


        @Override
        public boolean CheckTransmissionsComplete(BaseDeviceIO io) {
            return true;
        }

        @Override
        public boolean onIOInit() {
            return true;
        }

        @Override
        public void onIOSend(byte[] bytes) {

        }

        @Override
        public void onIORecv(byte[] bytes) {
            if (bytes==null) return;
            if (bytes.length<=0) return;
            byte opCode = bytes[0];
            if (bytes[bytes.length-1]!=calcSum(bytes,bytes.length-1))
                return;
            byte[] data = null;
            if (bytes.length > 2)
                data = Arrays.copyOfRange(bytes, 1, bytes.length-1);

            switch (opCode)
            {
                case opCode_respone_setting:
                    settingInfo.fromBytes(data);
                    break;
                case opCode_respone_water:
                    waterInfo.fromBytes(data);
                    break;
                case opCode_respone_filter:
                    filterInfo.fromBytes(data);
                    break;
                case opCode_respone_filterHis1:
                    break;
                case opCode_respone_filterHis2:
                    break;
            }
            doUpdate();
        }

        @Override
        public void onConnected(BaseDeviceIO io) {

        }

        @Override
        public void onDisconnected(BaseDeviceIO io) {

        }

        @Override
        public void onReady(BaseDeviceIO io) {
            requestSettingInfo();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            requestFilterHisInfo();

        }


    }

    /**
     * 返回是否允许滤芯重置
     * @return
     */
    public boolean isEnableFilterReset()
    {
        return true;
    }

    /**
     * 重置滤芯时间
     * @return
     */
    public boolean resetFilter()
    {
        return false;
    }

    public static BluetoothScanResponse parseScanResp(String name,byte[] Service_Data)
    {
        if (name.equals("Ozner RO")) {
            BluetoothScanResponse rep = new BluetoothScanResponse();
            if (Service_Data != null) {
                if (Service_Data.length<25) return null;
                rep.Platform= new String(Service_Data, 0, 3);
                rep.Firmware = new Date(Service_Data[3] + 2000 - 1900, Service_Data[4] - 1,
                        Service_Data[5], Service_Data[6],
                        Service_Data[7], Service_Data[8]);

                rep.Model="Ozner RO";
                rep.MainbroadPlatform= new String(Service_Data, 9, 3);
                rep.MainbroadFirmware = new Date(Service_Data[12] + 2000 - 1900, Service_Data[13] - 1,
                        Service_Data[14], Service_Data[15],
                        Service_Data[16], Service_Data[17]);

                rep.CustomDataLength=8;
                rep.ScanResponseType=WaterPurifier_RO_BLE.BLE_RO_ScanResponseType;
                rep.ScanResponseData= Arrays.copyOfRange(Service_Data, 18, 25);
            }
            return rep;
        }else
            return null;
    }


    /**
     * 判断设备是否处于配对状态
     *
     * @param io 设备接口
     * @return true=配对状态
     */
    public static boolean isBindMode(BluetoothIO io) {
        return true;
        /*if (!CupManager.IsCup(io.getType())) return false;
        byte[] data=io.getScanResponseData();
        if ((io.getScanResponseType() == BLE_RO_ScanResponseType) && (data!= null)) {
            return data[0]==1;
        }
        return false;*/
    }
}
