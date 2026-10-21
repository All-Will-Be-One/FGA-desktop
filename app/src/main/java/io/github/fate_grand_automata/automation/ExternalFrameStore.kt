package io.github.fate_grand_automata.automation

import io.github.lib_automata.Pattern
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import timber.log.Timber
import java.util.UUID

/** App-private, short-lived captures. Tokens are never filesystem paths or URI grants. */
class ExternalFrameStore(private val directory:File,private val authority:String) {
    private data class Entry(val runId:String,val file:File)
    private val entries=linkedMapOf<String,Entry>()
    init {
        directory.mkdirs()
        // Restart invalidates every previous token; external actions are never resumed.
        directory.listFiles()?.filter { it.isFile }?.forEach {
            if (!it.delete()) Timber.w("Could not delete old capture; previous token remains invalid: %s", it.name)
        }
    }
    @Synchronized fun save(runId:String,pattern:Pattern):JsonObject {
        if(entries.size>=8) {
            // The synchronous receiver retains only current/parent captures, never a history.
            entries.entries.first().let { it.value.file.delete();entries.remove(it.key) }
        }
        val token=UUID.randomUUID().toString()
        val file=File(directory,"$token.png")
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            var count=0L
            FileOutputStream(file).use { raw ->
                val bounded=object:FilterOutputStream(DigestOutputStream(raw,digest)) {
                    override fun write(b:Int) { check(++count<=16*1024*1024) { "Capture exceeds 16MiB" };out.write(b) }
                    override fun write(b:ByteArray,off:Int,len:Int) { count+=len;check(count<=16*1024*1024) { "Capture exceeds 16MiB" };out.write(b,off,len) }
                }
                pattern.save(bounded);bounded.flush();raw.fd.sync()
            }
            check(file.length() in 24..16*1024*1024) { "Invalid capture size" }
            entries[token]=Entry(runId,file)
            return buildJsonObject {
                put("uri","content://$authority/frames/$token")
                put("sha256",digest.digest().joinToString("") { "%02x".format(it) })
                put("bytes",file.length());put("width",pattern.width);put("height",pattern.height)
                put("coordinateSpace",if(pattern.width==1280 && pattern.height==720) "normalized_1280x720" else "unsupported")
            }
        } catch(error:Exception) { file.delete();throw error }
    }
    @Synchronized fun read(token:String):File {
        if(!token.matches(Regex("[a-f0-9-]{36}"))) throw FileNotFoundException("Unknown external capture")
        val entry=entries[token] ?: throw FileNotFoundException("Unknown external capture")
        if(!entry.file.isFile || entry.file.canonicalFile.parentFile!=directory.canonicalFile) throw FileNotFoundException("Capture unavailable")
        return entry.file
    }
    @Synchronized fun release(runId:String) {
        entries.filterValues { it.runId==runId }.keys.toList().forEach { token -> entries.remove(token)?.file?.delete() }
    }
}
