package com.ozner.WaterPurifier;

import android.content.Context;

import com.ozner.bluetooth.BluetoothIO;
import com.ozner.bluetooth.BluetoothScanResponse;
import com.ozner.bluetooth.IBluetoothScanResponseParser;
import com.ozner.device.BaseDeviceIO;
import com.ozner.device.BaseDeviceManager;
import com.ozner.device.OznerDevice;
import com.ozner.device.OznerDeviceManager;

import java.util.Arrays;
import java.util.Date;

/**
 * Created by xzyxd on 2015/11/2.
 */
public class WaterPurifierManager extends BaseDeviceManager {


    IBluetoothScanResponseParser bluetoothScanResponseParser = new IBluetoothScanResponseParser() {
        @Override
        public BluetoothScanResponse parseScanResponse(String name, byte[] Manufacturer_Specific, byte[] Service_Data) {
            ///转到RO设备里面去处理BLE广播数据，返回NULL说明不是RO设备
            return WaterPurifier_RO_BLE.parseScanResp(name,Service_Data);

        }
    };

    public WaterPurifierManager(Context context) {
        super(context);
        OznerDeviceManager.Instance().ioManagerList().bluetoothIOMgr().registerScanResponseParser(bluetoothScanResponseParser);
    }

    public static boolean IsWaterPurifier(String Model) {
        if (Model == null) return false;
        if (Model.trim().equals("MXCHIP_HAOZE_Water"))
        {
            return true;
        }
        if (Model.trim().equals("AY001MAB1"))
        {
            return true;
        }
        if (Model.trim().equals("Ozner RO"))
        {
            return true;
        }
        if (Model.trim().equals("16a21bd6"))
        {
            return true;
        }

        return false;

    }

    @Override
    public boolean isMyDevice(String type) {
        return IsWaterPurifier(type);
    }

    @Override
    protected OznerDevice createDevice(String address, String type, String settings) {
        if (type.trim().equals("MXCHIP_HAOZE_Water") || (type.trim().equals("16a21bd6")))
        {
            WaterPurifier waterPurifier = new WaterPurifier_MXChip(context(), address, type, settings);
            OznerDeviceManager.Instance().ioManagerList().mxChipIOManager()
                        .createMXChipDevice(waterPurifier.Address(), waterPurifier.Type());
            return waterPurifier;
        }
        if (type.trim().equals("AY001MAB1"))
        {
            WaterPurifier waterPurifier = new WaterPurifier_Ayla(context(), address, type, settings);
            return waterPurifier;
        }else
        if (type.trim().equals("Ozner RO"))
        {
            WaterPurifier waterPurifier = new WaterPurifier_RO_BLE(context(), address, type, settings);
            return waterPurifier;
        }else
            return null;
    }

    @Override
    public boolean checkIsBindMode(BaseDeviceIO io) {
        if (io instanceof BluetoothIO)
        {
            //检查是否在配对模式
            return WaterPurifier_RO_BLE.isBindMode((BluetoothIO)io);
        }
        return super.checkIsBindMode(io);
    }

    //    @Override
//    public OznerDevice loadDevice(String address, String Type, String Settings) {
//        if (IsWaterPurifier(Type)) {
//            OznerDevice device = OznerDeviceManager.Instance().getDevice(address);
//            if (device == null) {
//                device = new WaterPurifier(context(), address, Type, Settings);
//            }
//            return device;
//        }
//        else
//            return null;
//    }
//    @Override
//    protected OznerDevice getDevice(BaseDeviceIO io) throws DeviceNotReadyException {
//        if (io instanceof MXChipIO) {
//            String address = io.getAddress();
//            OznerDevice device = OznerDeviceManager.Instance().getDevice(address);
//            if (device != null) {
//                return device;
//            } else {
//                if (IsWaterPurifier(io.getType())) {
//                    WaterPurifier c = new WaterPurifier(context(), address, io.getType(), "");
//                    c.Bind(io);
//                    return c;
//                }
//            }
//        }
//        return null;
//    }

//    public OznerDevice newWaterPurifier(Context context, String address) {
//        OznerDevice device = OznerDeviceManager.Instance().getDevice(address);
//        if (device != null) {
//            return device;
//        } else {
//            WaterPurifier waterPurifier = new WaterPurifier(context(), address, "MXCHIP_HAOZE_Water", "");
//            MXChipIO io = OznerDeviceManager.Instance().ioManagerList().mxChipIOManager()
//                    .createNewIO(waterPurifier.Setting().name(), waterPurifier.Address(), waterPurifier.Type());
//            try {
//                waterPurifier.Bind(io);
//            } catch (DeviceNotReadyException e) {
//                e.printStackTrace();
//            }
//
//            return waterPurifier;
//        }
//    }
//
//
//    @Override
//    protected OznerDevice loadDevice(String address, String Type, String Setting) {
//        if (IsWaterPurifier(Type)) {
//            WaterPurifier waterPurifier = new WaterPurifier(context(), address, Type, Setting);
//            OznerDeviceManager.Instance().ioManagerList().mxChipIOManager()
//                    .createNewIO(waterPurifier.Setting().name(), waterPurifier.Address(), waterPurifier.Type());
//            return waterPurifier;
//        } else
//            return null;
//    }

//    @Override
//    public boolean isMyDevice(BaseDeviceIO io) {
//        if (io instanceof MXChipIO) {
//            return IsWaterPurifier(io.getType());
//        } else return false;
//    }
}
