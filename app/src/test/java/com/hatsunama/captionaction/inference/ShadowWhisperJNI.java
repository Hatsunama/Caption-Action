package com.hatsunama.captionaction.inference;

import dev.ffmpegkit.whisper.WhisperJNI;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.LinkedBlockingQueue;

/** Replaces only the JNI boundary; the shipped AAR's model and wrapper run unchanged. */
@Implements(value = WhisperJNI.class, isInAndroidSdk = false)
public class ShadowWhisperJNI {
    static CountDownLatch entered, proceed, loadEntered, loadProceed, freed;
    static AtomicInteger frees, active, peak, calls;
    static volatile boolean failTranscription;
    static volatile CountDownLatch secondEntry;
    static volatile String language;
    static volatile long lastHandle;
    static AtomicLong handles;
    static LinkedBlockingQueue<Long> freeEvents;
    static void reset() {
        secondEntry = null;
        handles = new AtomicLong(41); freeEvents = new LinkedBlockingQueue<>();
        freed = new CountDownLatch(1); language = null; lastHandle = 0;
        entered = new CountDownLatch(1); proceed = new CountDownLatch(1);
        loadEntered = new CountDownLatch(1); loadProceed = new CountDownLatch(0);
        frees = new AtomicInteger(); active = new AtomicInteger();
        peak = new AtomicInteger(); calls = new AtomicInteger(); failTranscription = false;
    }
    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("JNI fixture barrier timed out");
        } catch (InterruptedException e) { throw new AssertionError("JNI fixture interrupted", e); }
    }
    @Implementation protected static void __staticInitializer__() {
        ReflectionHelpers.setStaticField(WhisperJNI.class, "INSTANCE", ReflectionHelpers.callConstructor(WhisperJNI.class));
    }
    @Implementation protected long nativeLoadModel(String path) {
        long handle = handles.incrementAndGet();
        CountDownLatch gate = loadProceed;
        loadEntered.countDown(); await(gate); return handle;
    }
    @Implementation protected String nativeTranscribe(long handle, String path, String lang, boolean translate, int threads) {
        lastHandle = handle; language = lang;
        int call = calls.incrementAndGet(); int n = active.incrementAndGet(); peak.accumulateAndGet(n, Math::max);
        if (call == 2 && secondEntry != null) secondEntry.countDown();
        entered.countDown();
        try {
            await(proceed);
            if (failTranscription) throw new IllegalStateException("controlled JNI failure");
            return "{\"text\":\"The blue train arrives at seven\",\"segments\":[]}";
        } finally { active.decrementAndGet(); }
    }
    @Implementation protected void nativeReleaseModel(long handle) { frees.incrementAndGet(); freeEvents.add(handle); freed.countDown(); }
}
