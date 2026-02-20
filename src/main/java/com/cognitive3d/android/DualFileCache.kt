package com.cognitive3d.android

import android.content.Context
import android.util.Log
import java.io.*

class DualFileCache(context: Context, private val cacheLimit: Long = 1024 * 1024 * 5) {
    private val readFileName = "data_read"
    private val writeFileName = "data_write"
    private val eol = "\n" 
    private val eolBytes = eol.toByteArray(Charsets.UTF_8).size

    private val cacheDir: File
    private val readFile: File
    private val writeFile: File

    // Stores length in BYTES to ensure accurate seeking and truncation
    private val readLineLengths = mutableListOf<Int>()
    private var numberWriteBatches = 0

    init {
        val externalDir = context.getExternalFilesDir(null)
        cacheDir = File(externalDir ?: context.filesDir, "c3dlocal")
        
        readFile = File(cacheDir, readFileName)
        writeFile = File(cacheDir, writeFileName)

        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        
        if (!readFile.exists()) readFile.createNewFile()
        if (!writeFile.exists()) writeFile.createNewFile()

        // Count existing batches in write file
        if (writeFile.exists() && writeFile.length() > 0) {
            var lines = 0
            writeFile.bufferedReader(Charsets.UTF_8).use { reader ->
                while (reader.readLine() != null) lines++
            }
            numberWriteBatches = lines / 2
        }

        if (numberWriteBatches > 0) {
            mergeDataFiles()
        } else {
            rebuildReadLineLengths()
        }
    }

    @Synchronized
    fun hasContent(): Boolean {
        if (readFile.length() > 0) return true
        if (numberWriteBatches > 0) {
            mergeDataFiles()
            return readFile.length() > 0
        }
        return false
    }

    @Synchronized
    fun peekContent(): Triple<String, String, Boolean>? {
        if (!hasContent()) return null
        if (readLineLengths.size < 2) return null

        try {
            val bodyLen = readLineLengths[readLineLengths.size - 1]
            val urlLen = readLineLengths[readLineLengths.size - 2]
            
            // Calculate total bytes to read including delimiters
            val totalToRead = bodyLen + urlLen + eolBytes * 2
            
            RandomAccessFile(readFile, "r").use { raf ->
                val startPos = readFile.length() - totalToRead
                if (startPos < 0) return null
                
                raf.seek(startPos)
                val buffer = ByteArray(totalToRead)
                raf.readFully(buffer)
                
                // Decode using UTF-8 and split by our known delimiter
                val content = String(buffer, Charsets.UTF_8)
                val lines = content.split(eol)
                if (lines.size >= 2) {
                    val url = lines[0]
                    val body = lines[1]
                    if (url.isNotEmpty() && body.isNotEmpty()) {
                        return Triple(url, body, url.contains("audio"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error peeking cache", e)
        }
        return null
    }

    @Synchronized
    fun popContent() {
        if (readLineLengths.size < 2) return

        try {
            val bodyLen = readLineLengths.removeAt(readLineLengths.size - 1)
            val urlLen = readLineLengths.removeAt(readLineLengths.size - 1)
            val bytesToRemove = bodyLen.toLong() + urlLen.toLong() + (eolBytes * 2).toLong()

            val newLength = readFile.length() - bytesToRemove
            if (newLength >= 0) {
                RandomAccessFile(readFile, "rw").use { raf ->
                    raf.setLength(newLength)
                }
            } else {
                RandomAccessFile(readFile, "rw").use { it.setLength(0) }
                readLineLengths.clear()
            }
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error popping cache", e)
            rebuildReadLineLengths()
        }
    }

    @Synchronized
    fun writeContent(url: String, body: String): Boolean {
        if (!canWrite(url, body)) return false

        try {
            FileOutputStream(writeFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(url)
                writer.write(eol)
                writer.write(body)
                writer.write(eol)
            }
            numberWriteBatches++
            return true
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error writing to cache", e)
            return false
        }
    }

    private fun canWrite(url: String, body: String): Boolean {
        val totalBytes = readFile.length() + writeFile.length()
        val newBytes = url.toByteArray(Charsets.UTF_8).size.toLong() + 
                       body.toByteArray(Charsets.UTF_8).size.toLong() + 
                       (eolBytes * 2).toLong()
        
        if ((totalBytes + newBytes) > cacheLimit) {
            Log.w(Util.TAG, "Data Cache reached size limit!")
            return false
        }
        return true
    }

    private fun mergeDataFiles() {
        try {
            if (writeFile.length() == 0L) return

            // Binary copy for better performance and safety
            FileOutputStream(readFile, true).use { outputStream ->
                writeFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Clear write file
            RandomAccessFile(writeFile, "rw").use { it.setLength(0) }
            numberWriteBatches = 0
            
            rebuildReadLineLengths()
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error merging cache files", e)
        }
    }

    private fun rebuildReadLineLengths() {
        readLineLengths.clear()
        if (!readFile.exists() || readFile.length() == 0L) return
        
        try {
            readFile.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    readLineLengths.add(line!!.toByteArray(Charsets.UTF_8).size)
                }
            }
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error rebuilding line lengths", e)
        }
    }
}
