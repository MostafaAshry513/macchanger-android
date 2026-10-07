package android.os;

/** Stub of android.os.StatFs. */
public class StatFs {

    public StatFs(String path) { }

    public int getBlockSize() { return 4096; }

    public int getBlockCount() { return 0; }

    public int getAvailableBlocks() { return 0; }

    public long getBlockSizeLong() { return 4096L; }

    public long getBlockCountLong() { return 0L; }

    public long getAvailableBlocksLong() { return 0L; }

    public long getTotalBytes() { return 0L; }

    public long getAvailableBytes() { return 0L; }

    public void restat(String path) { }
}
