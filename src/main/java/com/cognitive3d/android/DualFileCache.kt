package com.cognitive3d.android

import android.content.Context
import android.util.Log
import java.io.*

class DualFileCache(context: Context, private val cacheLimit: Long = 1024 * 1024 * 5) {
    private val readFileName = "data_read"
    private val writeFileName = "data_write"
    private val eol = System.lineSeparator()

    private val cacheDir: File
    private val readFile: File
    private val writeFile: File

    private val readLineLengths = mutableListOf<Int>()
    private var numberWriteBatches = 0

    init {
        // Use external storage if available, fallback to internal if not
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
        if (writeFile.exists()) {
            var lines = 0
            writeFile.bufferedReader().use { reader ->
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
            
            val totalToSeek = (bodyLen + urlLen + eol.length * 2).toLong()
            
            RandomAccessFile(readFile, "r").use { raf ->
                raf.seek(readFile.length() - totalToSeek)
                val url = raf.readLine()
                val body = raf.readLine()
                
                if (!url.isNullOrBlank() && !body.isNullOrBlank()) {
                    return Triple(url, body, url.contains("audio"))
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
            val bytesToRemove = bodyLen + urlLen + eol.length * 2

            val newLength = readFile.length() - bytesToRemove
            RandomAccessFile(readFile, "rw").use { raf ->
                raf.setLength(newLength)
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
            FileOutputStream(writeFile, true).bufferedWriter().use { writer ->
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
        val eolBytes = eol.toByteArray().size
        val newBytes = url.toByteArray().size + body.toByteArray().size + (eolBytes * 2).toLong()
        
        if ((totalBytes + newBytes) > cacheLimit) {
            Log.w(Util.TAG, "Data Cache reached size limit!")
            return false
        }
        return true
    }

    private fun mergeDataFiles() {
        try {
            if (writeFile.length() == 0L) return

            FileOutputStream(readFile, true).bufferedWriter().use { writer ->
                writeFile.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (!line.isNullOrBlank()) {
                            writer.write(line)
                            writer.write(eol)
                        }
                    }
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
        if (!readFile.exists()) return
        
        try {
            readFile.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    readLineLengths.add(line!!.length)
                }
            }
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error rebuilding line lengths", e)
        }
    }
}
