package dev.getjump.calltranslate.ml.npu;

import android.os.IBinder;
import android.os.Bundle;
import android.os.SharedMemory;

interface INpuService {
    long openSession(String modelDirectory, IBinder owner);
    Bundle transcribe(long sessionId, long requestId, in SharedMemory audio, int sampleCount, String language);
    void cancel(long sessionId, long requestId);
    void closeSession(long sessionId);
}
