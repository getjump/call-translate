package dev.getjump.calltranslate;

import android.os.ParcelFileDescriptor;

interface ICaptureService {
    void destroy() = 16777114;
    ParcelFileDescriptor start(int audioSource) = 1;
    void stop() = 2;
    String diagnostics() = 3;
    void openInjection(int sampleRate) = 4;
    int writeInjection(in byte[] pcm16, long generation) = 5;
    void flushInjection(long generation) = 6;
    void stopInjection() = 7;
    void stopInput() = 8;
    boolean injectionDrained(long generation) = 9;
}
