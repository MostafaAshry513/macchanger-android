package android.os;

/** Stub of android.os.Message (only needed for Handler.Callback). */
public class Message {

    public int what;
    public int arg1;
    public int arg2;
    public Object obj;
    public Handler target;

    public Message() { }

    public static Message obtain() { return new Message(); }

    public static Message obtain(Handler h) { return new Message(); }

    public void sendToTarget() { }
}
