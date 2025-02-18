package dev.bessems.usbserial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.EventChannel;



/** UsbSerialPlugin */
public class UsbSerialPlugin implements MethodCallHandler, EventChannel.StreamHandler {

    private final String TAG = UsbSerialPortAdapter.class.getSimpleName();

    private android.content.Context m_Context;
    private UsbManager m_Manager;
    private int m_InterfaceId;
    private Registrar m_Registrar;
    private EventChannel.EventSink m_EventSink;
    private UsbSerialDevice m_SerialDeviceDevice;
    private UsbDeviceConnection m_Connection;

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";


    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_ATTACHED)) {
                Log.d(TAG, "ACTION_USB_ATTACHED");
                if ( m_EventSink != null ) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    HashMap<String, Object> msg = serializeDevice(device);
                    msg.put("event", ACTION_USB_ATTACHED);
                    m_EventSink.success(msg);
                }
            } else if (intent.getAction().equals(ACTION_USB_DETACHED)) {
                Log.d(TAG, "ACTION_USB_DETACHED");
                if ( m_EventSink != null ) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    HashMap<String, Object> msg = serializeDevice(device);
                    msg.put("event", ACTION_USB_DETACHED);
                    m_EventSink.success(msg);
                }
            }
        }
    };


    UsbSerialPlugin(Registrar registrar) {
        m_Registrar = registrar;
        m_Context = registrar.context();
        m_Manager = (UsbManager) m_Context.getSystemService(android.content.Context.USB_SERVICE);
        m_InterfaceId = 100;
        final EventChannel eventChannel = new EventChannel(registrar.messenger(), "usb_serial/usb_events");
        eventChannel.setStreamHandler(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        m_Context.registerReceiver(usbReceiver, filter);
    }


    private interface AcquirePermissionCallback {
        void onSuccess(UsbDevice device);
        void onFailed(UsbDevice device);
    }
    private void acquirePermissions(UsbDevice device, AcquirePermissionCallback cb) {

        class BRC2 extends  BroadcastReceiver {

            private UsbDevice m_Device;
            private AcquirePermissionCallback m_CB;

            BRC2(UsbDevice device, AcquirePermissionCallback cb ) {
                m_Device = device;
                m_CB = cb;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    Log.e(TAG, "BroadcastReceiver intent arrived, entering sync...");
                    m_Context.unregisterReceiver(this);
                    synchronized (this) {
                        Log.e(TAG, "BroadcastReceiver in sync");
                        /* UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE); */
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            // createPort(m_DriverIndex, m_PortIndex, m_Result, false);
                            m_CB.onSuccess(m_Device);
                        } else {
                            Log.d(TAG, "permission denied for device ");
                            m_CB.onFailed(m_Device);
                        }
                    }
                }
            }
        }

        Context cw = m_Registrar.context();

        BRC2 usbReceiver = new BRC2(device, cb);

        PendingIntent permissionIntent = PendingIntent.getBroadcast(cw, 0, new Intent(ACTION_USB_PERMISSION), 0);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        cw.registerReceiver(usbReceiver, filter);

        m_Manager.requestPermission(device, permissionIntent);
    }

    private void openDevice(String type, UsbDevice device, int iface, Result result, boolean allowAcquirePermission) {

        final AcquirePermissionCallback cb = new AcquirePermissionCallback() {

            @Override
            public void onSuccess(UsbDevice device) {
                openDevice(type, device, iface, result, false);
            }

            @Override
            public void onFailed(UsbDevice device) {
                result.error(TAG, "Failed to acquire permissions.", null);
            }
        };

        try {
            m_Connection = m_Manager.openDevice(device);

            if ( m_Connection == null && allowAcquirePermission ) {
                acquirePermissions(device, cb);
                return;
            }

            if ( type.equals("") ) {
                m_SerialDeviceDevice = UsbSerialDevice.createUsbSerialDevice(device, m_Connection, iface);
            } else {
                m_SerialDeviceDevice = UsbSerialDevice.createUsbSerialDevice(type, device, m_Connection, iface);
            }

            if (m_SerialDeviceDevice != null) {
                int interfaceId = m_InterfaceId++;
                UsbSerialPortAdapter adapter = new UsbSerialPortAdapter(m_Registrar, interfaceId, m_Connection, m_SerialDeviceDevice);
                result.success(adapter.getMethodChannelName());
                Log.d(TAG, "success.");
                return;
            }
            result.error(TAG, "Not an Serial device.", null);

        } catch ( java.lang.SecurityException e ) {

            if ( allowAcquirePermission ) {
                acquirePermissions(device, cb);
                return;
            } else {
                result.error(TAG, "Failed to acquire USB permission.", null);
            }
        } catch ( java.lang.Exception e ) {
            result.error(TAG, "Failed to acquire USB device.", null);
        }
    }

    private void createTyped(String type, int vid, int pid, int deviceId, int iface, Result result) {
        Map<String, UsbDevice> devices = m_Manager.getDeviceList();
        for (UsbDevice device : devices.values()) {

            if ( deviceId == device.getDeviceId() || (device.getVendorId() == vid && device.getProductId() == pid) ) {
                openDevice(type, device, iface, result, true);
                return;
            }
        }

        result.error(TAG, "No such device", null);
    }

    private HashMap<String, Object> serializeDevice(UsbDevice device) {
        HashMap<String, Object> dev = new HashMap<>();
        dev.put("vid", device.getVendorId());
        dev.put("pid", device.getProductId());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dev.put("manufacturerName", device.getManufacturerName());
            dev.put("productName", device.getProductName());
            dev.put("interfaceCount", device.getInterfaceCount());
            /* if the app targets SDK >= android.os.Build.VERSION_CODES.Q and the app does not have permission to read from the device. */
            try {
                dev.put("serialNumber", device.getSerialNumber());
            } catch  ( java.lang.SecurityException e ) {
            }
        }
        dev.put("deviceId", device.getDeviceId());
        return dev;
    }

    private void listDevices(Result result) {
        Map<String, UsbDevice> devices = m_Manager.getDeviceList();
        if ( devices == null ) {
            result.error(TAG, "Could not get USB device list.", null);
            return;
        }
        List<HashMap<String, Object>> transferDevices = new ArrayList<>();

        for (UsbDevice device : devices.values()) {
            transferDevices.add(serializeDevice(device));
        }
        result.success(transferDevices);
    }

    private void initFlowControlHandler(Result result){
        try {
            if (m_SerialDeviceDevice != null) {
                int interfaceId = m_InterfaceId++;
                FlowControlHandler flowControlHandler = new FlowControlHandler(m_Registrar, interfaceId, m_Connection, m_SerialDeviceDevice);
                result.success(flowControlHandler.getMethodChannelName());
                Log.d(TAG, "success");
                return;
            }
            result.error(TAG, "Not a Serial device.", null);
        } catch ( java.lang.Exception e ) {
            result.error(TAG, "Failed to acquire Flow Control Handler for device.", null);
        }
    }


    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        m_EventSink = eventSink;

    }

    @Override
    public void onCancel(Object o) {
        m_EventSink = null;

    }


    /** Plugin registration. */
    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "usb_serial");
        channel.setMethodCallHandler(new UsbSerialPlugin(registrar));
    }


    @Override
    public void onMethodCall(MethodCall call, Result result) {

        switch (call.method) {

            case "create": {
                createTyped((String)call.argument("type"), (int)call.argument("vid"),
                        (int)call.argument("pid"), (int)call.argument("deviceId"),
                        (int)call.argument("interface"), result);
                break;
            }
            case "listDevices":
                listDevices(result);
                break;
            case "getFlowControlChannel":
                initFlowControlHandler(result);
                break;
            default:
                result.notImplemented();
                break;
            }

    }

}

