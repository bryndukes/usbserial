package dev.bessems.usbserial;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

public class FlowControlHandler implements MethodCallHandler, EventChannel.StreamHandler {

    private final String TAG = FlowControlHandler.class.getSimpleName();

    private int m_InterfaceId;
    private UsbDeviceConnection m_Connection;
    private UsbSerialDevice m_SerialDevice;
    private Registrar m_Registrar;
    private String m_MethodChannelName;
    private EventChannel.EventSink m_EventSink;
    private Handler m_handler;

    FlowControlHandler(Registrar registrar, int interfaceId, UsbDeviceConnection connection, UsbSerialDevice serialDevice) {
        m_Registrar = registrar;
        m_InterfaceId = interfaceId;
        m_Connection = connection;
        m_SerialDevice = serialDevice;
        m_MethodChannelName = "usb_serial/FlowControlAdapter/" + String.valueOf(interfaceId);
        m_handler = new Handler(Looper.getMainLooper());
        final MethodChannel channel = new MethodChannel(registrar.messenger(), m_MethodChannelName);
        channel.setMethodCallHandler(this);
        final EventChannel eventChannel = new EventChannel(registrar.messenger(), m_MethodChannelName + "/stream");
        eventChannel.setStreamHandler(this);
    }

    String getMethodChannelName() {
        return m_MethodChannelName;
    }

    private void setFlowControl( int flowControl ) {
        m_SerialDevice.setFlowControl(flowControl);
        if (flowControl == 1) {
            UsbSerialInterface.UsbCTSCallback ctsCallback = new UsbSerialInterface.UsbCTSCallback() {
                @Override
                public void onCTSChanged(boolean state) {
                    if (m_EventSink != null) {
                        m_handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (m_EventSink != null) {
                                    m_EventSink.success(state);
                                }
                            }
                        });
                    }
                }
            };

            m_SerialDevice.getCTS(ctsCallback);
        }
    }


    // return true if the object is to be kept, false if it is to be destroyed.
    public void onMethodCall(MethodCall call, Result result) {

        switch (call.method) {
            case "setFlowControl":
                setFlowControl((int) call.argument("flowControl"));
                result.success(null);
                break;
            default:
                result.notImplemented();
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
}