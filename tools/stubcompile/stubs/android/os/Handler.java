package android.os;

/** Stub of android.os.Handler. Nothing here runs; every post is a no-op. */
public class Handler {

    public Handler() { }

    public Handler(Looper looper) { }

    public Handler(android.os.Handler.Callback callback) { }

    public boolean post(Runnable r) { return true; }

    public boolean postDelayed(Runnable r, long delayMillis) { return true; }

    public boolean postAtTime(Runnable r, long uptimeMillis) { return true; }

    public void removeCallbacks(Runnable r) { }

    public void removeCallbacks(Runnable r, Object token) { }

    public void removeCallbacksAndMessages(Object token) { }

    public boolean sendEmptyMessage(int what) { return true; }

    public boolean sendEmptyMessageDelayed(int what, long delayMillis) { return true; }

    public void removeMessages(int what) { }

    public Looper getLooper() { return Looper.getMainLooper(); }

    public interface Callback {
        boolean handleMessage(android.os.Message msg);
    }
}
