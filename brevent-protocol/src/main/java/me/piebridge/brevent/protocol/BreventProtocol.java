package me.piebridge.brevent.protocol;

import android.accounts.NetworkErrorException;
import android.os.Parcel;
import android.support.annotation.CallSuper;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * brevent protocol, request via socket, response via broadcast
 * <p>
 * Created by thom on 2017/2/6.
 */
public abstract class BreventProtocol {

    // md5(BuildConfig.APPLICATION_ID)
    public static final int PORT = 59526;

    private static final int VERSION = BuildConfig.VERSION_CODE;

    public static final int STATUS_REQUEST = 0;
    public static final int STATUS_RESPONSE = 1;
    public static final int UPDATE_BREVENT = 2;
    public static final int CONFIGURATION = 3;
    public static final int UPDATE_PRIORITY = 4;
    public static final int STATUS_NO_EVENT = 5;
    public static final int SHOW_ROOT = 6;

    private int mVersion;

    private int mAction;

    public boolean retry;

    private static ExecutorService executor = new ScheduledThreadPoolExecutor(0x1);

    public BreventProtocol(int action) {
        this.mVersion = VERSION;
        this.mAction = action;
    }

    BreventProtocol(Parcel in) {
        mVersion = in.readInt();
        mAction = in.readInt();
    }

    @CallSuper
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mVersion);
        dest.writeInt(mAction);
    }

    public final int getAction() {
        return mAction;
    }

    public final boolean versionMismatched() {
        return mVersion != VERSION;
    }

    public static void writeTo(BreventProtocol protocol, DataOutputStream os) throws IOException {
        Parcel parcel = Parcel.obtain();
        protocol.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        bytes = compress(bytes);
        int size = bytes.length;
        if (size > 0xffff) {
            throw new IOTooLargeException(size);
        }
        os.writeShort(size);
        os.write(bytes);
    }

    @Nullable
    public static BreventProtocol readFrom(DataInputStream is) throws IOException {
        int size = is.readUnsignedShort();
        if (size == 0) {
            return null;
        }

        byte[] bytes = new byte[size];
        int length;
        int offset = 0;
        int remain = bytes.length;
        while (remain > 0 && (length = is.read(bytes, offset, remain)) != -1) {
            if (length > 0) {
                offset += length;
                remain -= length;
            }
        }

        return unwrap(uncompress(bytes));
    }

    private String getActionName(int action) {
        switch (action) {
            case STATUS_REQUEST:
                return "request";
            case STATUS_RESPONSE:
                return "response";
            case UPDATE_BREVENT:
                return "brevent";
            case CONFIGURATION:
                return "configuration";
            case UPDATE_PRIORITY:
                return "priority";
            case STATUS_NO_EVENT:
                return "no_event";
            default:
                return "(unknown: " + action + ")";
        }
    }

    private static BreventProtocol unwrap(byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);
        parcel.readInt(); // skip version
        int action = parcel.readInt();
        parcel.setDataPosition(0);

        try {
            switch (action) {
                case STATUS_REQUEST:
                    return new BreventRequest(parcel);
                case STATUS_RESPONSE:
                    return new BreventResponse(parcel);
                case UPDATE_BREVENT:
                    return new BreventPackages(parcel);
                case CONFIGURATION:
                    return new BreventConfiguration(parcel);
                case UPDATE_PRIORITY:
                    return new BreventPriority(parcel);
                case STATUS_NO_EVENT:
                    return new BreventNoEvent(parcel);
                case SHOW_ROOT:
                    return new BreventDisableRoot(parcel);
                default:
                    return null;
            }
        } finally {
            parcel.recycle();
        }
    }

    private static byte[] compress(byte[] bytes) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gos = new GZIPOutputStream(baos);
            gos.write(bytes);
            gos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] uncompress(byte[] compressed) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            GZIPInputStream gis = new GZIPInputStream(bais);
            byte[] buffer = new byte[0x1000];
            int length;
            while ((length = gis.read(buffer)) != -1) {
                if (length > 0) {
                    baos.write(buffer, 0, length);
                }
            }
            gis.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "version: " + mVersion + ", action: " + getActionName(mAction);
    }

    private static void d(String tag, String msg, Throwable t) {
        Log.d(tag, msg);
        if (Log.isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, msg, t);
        }
    }

    private static void v(String tag, String msg) {
        Log.v(tag, msg);
    }

    private static void v(String tag, String msg, Throwable t) {
        if (Log.isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, msg, t);
        } else {
            Log.v(tag, msg);
        }
    }

    @WorkerThread
    public static void checkPortSync() throws IOException {
        try (
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), BreventProtocol.PORT);
                DataOutputStream os = new DataOutputStream(socket.getOutputStream());
                DataInputStream is = new DataInputStream(socket.getInputStream())
        ) {
            os.writeShort(0);
            os.flush();
            BreventProtocol.readFrom(is);
        }
    }

    public static boolean checkPort(final String tag) throws NetworkErrorException {
        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    checkPortSync();
                    v(tag, "connected to localhost: " + BreventProtocol.PORT);
                    return true;
                } catch (ConnectException e) {
                    v(tag, "cannot connect to localhost:" + BreventProtocol.PORT, e);
                    return false;
                } catch (IOException e) {
                    v(tag, "io error to localhost:" + BreventProtocol.PORT, e);
                    return false;
                }
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            d(tag, "Can't check port: " + e.getMessage(), e);
            throw new NetworkErrorException(e);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    public static class IOTooLargeException extends IOException {

        private final int mSize;

        IOTooLargeException(int size) {
            super();
            mSize = size;
        }

        public int getSize() {
            return mSize;
        }

    }

}
