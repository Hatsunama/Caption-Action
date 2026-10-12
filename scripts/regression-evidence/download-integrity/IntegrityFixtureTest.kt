package com.hatsunama.captionaction.service

import android.content.Context
import com.hatsunama.captionaction.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Post-repair fixtures use an internal descriptor seam; they do NOT validate real model bytes. */
class IntegrityFixtureTest {
    companion object {
        val bytes = ByteArray(8193) { (it * 31).toByte() }
        internal val id = ModelIdentity(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val tier = ModelTier.BALANCED
        val requests = CopyOnWriteArrayList<HttpURLConnection>()
        @Volatile var script: (Int) -> Response = { Response() }
        @JvmStatic @BeforeClass fun install() {
            URL.setURLStreamHandlerFactory { protocol -> if(protocol != "https") null else object: URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection {
                    val r = script(requests.size)
                    return object: HttpURLConnection(u) {
                        override fun connect() {}
                        override fun disconnect() { r.disconnect?.invoke() }
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int { r.codeHook?.invoke(); return r.code }
                        override fun getContentLengthLong() = r.length
                        override fun getHeaderField(name: String?) = when(name) { "Content-Range" -> r.range; "Content-Encoding" -> r.encoding; else -> null }
                        override fun getInputStream() = r.stream ?: ByteArrayInputStream(r.body)
                    }.also { requests.add(it) }
                }
            } }
        }
    }
    data class Response(val code: Int = 200, val body: ByteArray = bytes, val length: Long = body.size.toLong(),
        val range: String? = null, val encoding: String? = null, val stream: InputStream? = null,
        val disconnect: (() -> Unit)? = null, val codeHook: (() -> Unit)? = null)
    private lateinit var dir: File
    private lateinit var cache: ModelCache
    private fun cache(ops: ModelFileOperations = ModelFileOperations()) = ModelCache(Context(dir), { id }, ops)
    @Before fun setup() { requests.clear(); script = { Response() }; dir = Files.createTempDirectory("n5-fixture").toFile(); cache = cache() }
    @After fun cleanup() { dir.deleteRecursively() }
    private suspend fun download(c: ModelCache = cache) = ModelDownloadManager(c).download(tier) {}
    private fun assertReady(c: ModelCache = cache) { assertTrue(c.isReadyForAsr(tier)); assertArrayEquals(bytes,c.fileFor(tier).readBytes()) }
    private fun range(start: Int, end: Int = bytes.size-1) = "bytes $start-$end/${bytes.size}"
    @Test fun freshValidForEveryTier() = runBlocking {
        for (t in ModelTier.entries) { assertTrue(ModelDownloadManager(cache).download(t) {}.isSuccess); assertTrue(cache.isPresent(t)); assertArrayEquals(bytes,cache.fileFor(t).readBytes()) }
        assertEquals(3,requests.size); assertTrue(requests.all { it.getRequestProperty("Accept-Encoding") == "identity" })
    }
    @Test fun actualMetadataConstantsRemainExact() {
        assertEquals(32_152_673, ModelTier.BALANCED.expectedBytes); assertEquals(190_085_487, ModelTier.ACCURATE.expectedBytes); assertEquals(239_233_841,ModelTier.FAST.expectedBytes)
        assertEquals("818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",ModelTier.BALANCED.sha256)
        assertTrue(ModelTier.entries.all { it.sha256.length == 64 && it.downloadUrl.contains("/resolve/main/") })
    }
    @Test fun validPrefixResume() = runBlocking {
        cache.partFileFor(tier).writeBytes(bytes.copyOfRange(0,17)); script = { Response(206,bytes.copyOfRange(17,bytes.size),range=range(17)) }
        assertTrue(download().isSuccess); assertReady(); assertEquals("bytes=17-",requests.single().getRequestProperty("Range"))
    }
    @Test fun ignoredRange200RestartsInsteadOfAppending() = runBlocking {
        cache.partFileFor(tier).writeText("bad prefix"); assertTrue(download().isSuccess); assertReady()
    }
    @Test fun knownFullValidPartialPromotesWithoutHttp() = runBlocking {
        cache.partFileFor(tier).writeBytes(bytes); assertTrue(download().isSuccess); assertReady(); assertEquals(0,requests.size)
    }
    @Test fun knownWrongFullPartialRecoversInSameOperation() = runBlocking {
        cache.partFileFor(tier).writeBytes(ByteArray(bytes.size)); assertTrue(download().isSuccess); assertReady(); assertNull(requests.single().getRequestProperty("Range"))
    }
    @Test fun oversizedPartialRecovers() = runBlocking {
        cache.partFileFor(tier).writeBytes(ByteArray(bytes.size+5)); assertTrue(download().isSuccess); assertReady(); assertNull(requests.single().getRequestProperty("Range"))
    }
    @Test fun incomplete416ResetsThenSucceeds() = runBlocking {
        cache.partFileFor(tier).writeText("prefix"); script = { if(it == 0) Response(416) else Response() }
        assertTrue(download().isSuccess); assertReady(); assertEquals(2,requests.size); assertNull(requests[1].getRequestProperty("Range"))
    }
    @Test fun invalidFinalRecoveryReplacesAtomically() = runBlocking {
        cache.fileFor(tier).writeBytes(ByteArray(bytes.size)); assertTrue(download().isSuccess); assertReady()
    }
    @Test fun wrongFullBodyDoesNotPoisonGoodRetry() = runBlocking {
        script = { Response(body=ByteArray(bytes.size)) }; assertTrue(download().isFailure); assertEquals(0,cache.partialBytes(tier)); assertFalse(cache.isPresent(tier))
        script = { Response() }; assertTrue(download().isSuccess); assertReady()
    }
    @Test fun coldValidCacheRequiresActualReadAndAvoidsNetworkAfterVerification() = runBlocking {
        cache.fileFor(tier).writeBytes(bytes); assertFalse(cache.isPresent(tier)); assertTrue(cache.verify(tier)); assertReady()
        assertTrue(download().isSuccess); assertEquals(0,requests.size)
    }
    @Test fun registryIsSharedButFixtureMetadataCannotBlessProduction() = runBlocking {
        cache.fileFor(tier).writeBytes(bytes); assertTrue(cache.verify(tier)); assertTrue(cache().isPresent(tier)); assertFalse(ModelCache(Context(dir)).isPresent(tier))
    }
    @Test fun changedSizeInvalidatesSnapshot() = runBlocking {
        cache.fileFor(tier).writeBytes(bytes); assertTrue(cache.verify(tier)); cache.fileFor(tier).appendText("x"); assertFalse(cache().isPresent(tier))
    }
    @Test fun nativeLoadForcesRehashEvenWithRestoredTimestamp() = runBlocking {
        val f = cache.fileFor(tier); f.writeBytes(bytes); assertTrue(cache.verify(tier)); val time = Files.getLastModifiedTime(f.toPath())
        f.writeBytes(ByteArray(bytes.size)); Files.setLastModifiedTime(f.toPath(),time)
        var loads = 0; assertNull(cache.withVerifiedModel(tier) { loads++; true }); assertEquals(0,loads); assertFalse(cache.isPresent(tier))
    }
    @Test fun validNativeLoadKeepsWriterLockAndCancellationReleasesIt() = runBlocking {
        cache.fileFor(tier).writeBytes(bytes)
        val loaded = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>(); val mutated = CompletableDeferred<Unit>()
        val loader = launch(Dispatchers.Default) { cache.withVerifiedModel(tier) { loaded.complete(Unit); never.await() } }
        loaded.await()
        val mutation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { ModelDownloadLocks.withFile(File(dir,"models/../models/${tier.fileName}")) { mutated.complete(Unit) } }
        assertFalse(mutated.isCompleted)
        loader.cancelAndJoin(); withTimeout(5000) { mutated.await() }; mutation.join()
    }
    @Test fun failedLoadReleasesLockAndDoesNotPretendLoadSuccess() = runBlocking {
        cache.fileFor(tier).writeBytes(bytes); assertEquals(false, cache.withVerifiedModel(tier) { false })
        assertTrue(runCatching { cache.withVerifiedModel(tier) { error("native failure") } }.isFailure)
        withTimeout(5000) { ModelDownloadLocks.withFile(cache.fileFor(tier)) {} }
    }
    @Test fun hashIsOffCallerThreadAndCancellationNeverPublishesTrust() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val c = cache(object: ModelFileOperations() { override fun openRead(file: File): InputStream = object: ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); return super.read(b,off,len) }
        } })
        c.fileFor(tier).writeBytes(bytes)
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val job = launch(caller) { c.verify(tier) }
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(c.isPresent(tier))
            withTimeout(5000) { withContext(caller) { assertFalse(c.isPresent(tier)) } }
            job.cancel(); release.countDown(); job.join(); assertFalse(c.isPresent(tier))
        } finally { release.countDown(); caller.close() }
    }
    @Test fun managerCancellationDuringHashPreservesRetryablePartialButNoReadiness() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val c = cache(object: ModelFileOperations() { override fun openRead(file: File): InputStream = object: ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); return super.read(b,off,len) }
        } })
        val manager = ModelDownloadManager(c); val job = async(Dispatchers.Default) { manager.download(tier) {} }
        assertTrue(entered.await(5,TimeUnit.SECONDS)); manager.cancel(); release.countDown(); assertTrue(job.await().isFailure)
        assertFalse(c.isPresent(tier)); assertFalse(c.fileFor(tier).exists()); assertTrue(cache.partFileFor(tier).exists())
        assertTrue(download().isSuccess); assertReady()
    }
    @Test fun atomicMoveFailureHasNoCopyFallbackAndKeepsPartialRetryable() = runBlocking {
        val old = "preserve old invalid file".toByteArray(); cache.fileFor(tier).writeBytes(old)
        val c = cache(object: ModelFileOperations() { override fun atomicReplace(source: File,destination: File) { throw AtomicMoveNotSupportedException(source.path,destination.path,"test failure") } })
        assertTrue(download(c).isFailure); assertArrayEquals(old,c.fileFor(tier).readBytes()); assertArrayEquals(bytes,c.partFileFor(tier).readBytes()); assertFalse(c.isPresent(tier))
        val opens = requests.size; assertTrue(download().isSuccess); assertEquals(opens,requests.size); assertReady()
    }
    @Test fun syncFailureCannotPublishAndRemainsRetryable() = runBlocking {
        val c = cache(object: ModelFileOperations() { override fun sync(output: FileOutputStream) { throw IOException("sync failure") } })
        assertTrue(download(c).isFailure); assertFalse(c.fileFor(tier).exists()); assertFalse(c.isPresent(tier)); assertTrue(download().isSuccess); assertReady()
    }
    @Test fun persistentSyncFailureCannotBeBypassedByCompletePartialRetry() = runBlocking {
        val attempts = AtomicInteger()
        val c = cache(object: ModelFileOperations() { override fun sync(output: FileOutputStream) { attempts.incrementAndGet(); throw IOException("persistent sync failure") } })
        assertTrue(download(c).isFailure); assertArrayEquals(bytes,c.partFileFor(tier).readBytes())
        assertTrue("retry of a complete partial must still require a successful sync",download(c).isFailure)
        assertEquals(2,attempts.get()); assertFalse(c.fileFor(tier).exists()); assertFalse(c.isPresent(tier)); assertArrayEquals(bytes,c.partFileFor(tier).readBytes())
    }
    @Test fun destinationMustActuallyVerifyAfterMove() = runBlocking {
        val c = cache(object: ModelFileOperations() { override fun atomicReplace(source: File,destination: File) { super.atomicReplace(source,destination); destination.writeBytes(ByteArray(bytes.size)) } })
        assertTrue(download(c).isFailure); assertFalse(c.isPresent(tier)); assertTrue(download().isSuccess); assertReady()
    }
    @Test fun interruptedResponseResumesCorrectPrefix() = runBlocking {
        script = { Response(length=-1, stream=object: InputStream() {
            var sent=false
            override fun read() = error("buffered read only")
            override fun read(b: ByteArray,off:Int,len:Int):Int { if(sent) throw IOException("interrupt"); sent=true; bytes.copyInto(b,off,0,17); return 17 }
        }) }
        assertTrue(download().isFailure); assertEquals(17,cache.partialBytes(tier)); assertFalse(cache.isPresent(tier))
        script = { Response(206,bytes.copyOfRange(17,bytes.size),range=range(17)) }; assertTrue(download().isSuccess); assertReady()
    }
    @Test fun shortValidRangeRemainsPartialNotReady() = runBlocking {
        cache.partFileFor(tier).writeBytes(bytes.copyOfRange(0,17)); script = { Response(206,bytes.copyOfRange(17,33),range=range(17,32)) }
        assertTrue(download().isFailure); assertEquals(33,cache.partialBytes(tier)); assertFalse(cache.isPresent(tier))
    }
    @Test fun invalidRangeLengthCannotWrite() = runBlocking {
        cache.partFileFor(tier).writeBytes(bytes.copyOfRange(0,17)); script = { Response(206,bytes.copyOfRange(17,bytes.size),length=100,range=range(17)) }
        assertTrue(download().isFailure); assertEquals(17,cache.partialBytes(tier))
    }
    @Test fun overlongResponseCannotPublishAndResets() = runBlocking {
        script = { Response(body=bytes + byteArrayOf(1),length=-1) }; assertTrue(download().isFailure); assertEquals(0,cache.partialBytes(tier)); assertFalse(cache.isPresent(tier))
    }
    @Test fun encodedResponseRejected() = runBlocking { script = { Response(encoding="gzip") }; assertTrue(download().isFailure); assertFalse(cache.partFileFor(tier).exists()) }
    @Test fun cancellationBetween416AndRetryOpensNoSecondRequest() = runBlocking {
        cache.partFileFor(tier).writeText("prefix"); val manager=ModelDownloadManager(cache)
        script = { Response(416,disconnect={ manager.cancel() }) }
        assertTrue(manager.download(tier) {}.isFailure); assertEquals(1,requests.size); assertFalse(cache.isPresent(tier))
    }
    @Test fun cancellationAtAtomicReplaceReturnLeavesVerifiedBytesButNoReadyToken() = runBlocking {
        lateinit var manager: ModelDownloadManager
        val c = cache(object: ModelFileOperations() { override fun atomicReplace(source:File,destination:File) { super.atomicReplace(source,destination); manager.cancel() } })
        manager=ModelDownloadManager(c)
        assertTrue(manager.download(tier) {}.isFailure); assertFalse(c.isPresent(tier)); assertArrayEquals(bytes,c.fileFor(tier).readBytes())
        assertTrue(cache.verify(tier)); assertReady()
    }
    @Test fun cancellationDuringDestinationHashCannotPublishReadiness() = runBlocking {
        lateinit var manager: ModelDownloadManager
        val c = cache(object: ModelFileOperations() { override fun openRead(file:File): InputStream {
            if (!file.name.endsWith(".part")) manager.cancel()
            return super.openRead(file)
        } })
        manager=ModelDownloadManager(c)
        assertTrue(manager.download(tier) {}.isFailure); assertFalse(c.isPresent(tier)); assertArrayEquals(bytes,c.fileFor(tier).readBytes())
    }
    @Test fun actualManagersSerializeWritersAndQueuedCancellationCannotOpenHttp() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        script={ Response(stream=object:ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int):Int { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); return super.read(b,off,len) }
        }) }
        val first=async(Dispatchers.IO) { download() }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val queuedManager=ModelDownloadManager(cache)
        // Matching IO context + UNDISPATCHED runs download synchronously to the held mutex.
        val second=async(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) { queuedManager.download(tier) {} }
        try { assertFalse(second.isCompleted); assertEquals(1,requests.size); queuedManager.cancel() }
        finally { release.countDown() }
        assertTrue(first.await().isSuccess); assertTrue(second.await().isFailure); assertEquals(1,requests.size); assertReady()
    }
    @Test fun readinessCannotAppearWhileAtomicMoveIsBlocked() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val c = cache(object: ModelFileOperations() { override fun atomicReplace(source:File,destination:File) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); super.atomicReplace(source,destination) } })
        val job=async(Dispatchers.Default) { download(c) }
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); repeat(100) { assertFalse(cache().isPresent(tier)); assertFalse(c.fileFor(tier).exists()) } }
        finally { release.countDown() }
        assertTrue(job.await().isSuccess); assertReady(c)
    }
    @Test fun warmRevalidationKeepsPreviouslyVerifiedSnapshotUntilFreshResult() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val block=java.util.concurrent.atomic.AtomicBoolean(false)
        val c=cache(object:ModelFileOperations() { override fun openRead(file:File):InputStream {
            val real=super.openRead(file)
            return object:FilterInputStream(real) { override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(block.get()) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }; return super.read(b,off,len)
            } }
        } })
        c.fileFor(tier).writeBytes(bytes); assertTrue(c.verify(tier)); block.set(true)
        val check=async(Dispatchers.IO) { c.verify(tier) }
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue("prior byte-verified snapshot stays valid during recheck",c.isPresent(tier)) }
        finally { release.countDown() }
        assertTrue(check.await())
    }
    @Test fun cancelledWarmRevalidationCreatesNoNewTrustButKeepsPriorSnapshot() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val block=java.util.concurrent.atomic.AtomicBoolean(false)
        val c=cache(object:ModelFileOperations() { override fun openRead(file:File):InputStream {
            val real=super.openRead(file)
            return object:FilterInputStream(real) { override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(block.get()) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }; return super.read(b,off,len)
            } }
        } })
        c.fileFor(tier).writeBytes(bytes); assertTrue(c.verify(tier)); block.set(true)
        val check=launch(Dispatchers.IO) { c.verify(tier) }
        assertTrue(entered.await(5,TimeUnit.SECONDS)); check.cancel(); release.countDown(); check.join(); assertTrue(c.isPresent(tier))
    }
    @Test fun failedReadDuringWarmRevalidationInvalidatesPriorSnapshot() = runBlocking {
        var fail=false
        val c=cache(object:ModelFileOperations() { override fun openRead(file:File):InputStream { if(fail) throw IOException("read failure"); return super.openRead(file) } })
        c.fileFor(tier).writeBytes(bytes); assertTrue(c.verify(tier)); fail=true
        assertFalse(c.verify(tier)); assertFalse(c.isPresent(tier))
    }

}
