package android.os;

/** Stub of android.os.Looper. */
public class Looper {

    protected Looper() { }

    public static Looper getMainLooper() { return new Looper(); }

    public static Looper myLooper() { return new Looper(); }

    public void quit() { }

    public void quitSafely() { }

    public Thread getThread() { return Thread.currentThread(); }
}
