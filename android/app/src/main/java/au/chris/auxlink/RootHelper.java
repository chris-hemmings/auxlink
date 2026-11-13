package au.chris.auxlink;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Runs as root, outside the app, started by RootAdb through the head unit's
 * own root adb:
 *
 *   CLASSPATH=&lt;this APK&gt; app_process /system/bin au.chris.auxlink.RootHelper grant 1209 1,2 &lt;uid&gt;
 *
 * Gives the app (uid) USB permission for the auxlink devices (vendor 1209,
 * products 0001 = XIAO, 0002 = the Pi's USB-C port) that are plugged in now -
 * the same thing tapping Allow on Android's USB prompt does, without the
 * prompt. Android's USB service lets root do this (it holds every
 * permission). The grant lasts until the device is unplugged; the app asks
 * again at the next plug-in.
 *
 * Plain Java, no app classes, no Context: it runs in a bare app_process.
 * Prints one result line: "OK granted N" or "ERR ...".
 */
public final class RootHelper {
    private RootHelper() {}

    public static void main(String[] args) {
        try {
            if (args.length < 4 || !"grant".equals(args[0])) {
                System.out.println("ERR usage: grant <vid-hex> <pid-hex,pid-hex> <uid>");
                return;
            }
            int vid = Integer.parseInt(args[1], 16);
            String[] pidList = args[2].split(",");
            int[] pids = new int[pidList.length];
            for (int i = 0; i < pidList.length; i++) pids[i] = Integer.parseInt(pidList[i], 16);
            int uid = Integer.parseInt(args[3]);

            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "usb");
            if (b == null) { System.out.println("ERR no usb service"); return; }
            Object usb = Class.forName("android.hardware.usb.IUsbManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);

            Bundle devices = new Bundle();
            usb.getClass().getMethod("getDeviceList", Bundle.class).invoke(usb, devices);
            Method grant = usb.getClass().getMethod("grantDevicePermission", UsbDevice.class, int.class);

            int n = 0;
            for (String key : devices.keySet()) {
                Object o = devices.get(key);
                if (!(o instanceof UsbDevice)) continue;
                UsbDevice d = (UsbDevice) o;
                if (d.getVendorId() != vid) continue;
                boolean match = false;
                for (int p : pids) if (d.getProductId() == p) match = true;
                if (!match) continue;
                grant.invoke(usb, d, uid);
                n++;
            }
            System.out.println("OK granted " + n);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("ERR " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }
}
