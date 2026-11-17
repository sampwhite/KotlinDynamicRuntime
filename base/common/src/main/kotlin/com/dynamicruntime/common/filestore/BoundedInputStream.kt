package com.dynamicruntime.common.filestore

import java.io.FilterInputStream
import java.io.InputStream

/**
 * [source] read no further than [limit] bytes (issue #1835): how a directory store hands out one range of a file from
 * a stream positioned at its start. Closing it closes [source].
 */
class BoundedInputStream(source: InputStream, limit: Long) : FilterInputStream(source) {
    private var left = limit

    override fun read(): Int {
        if (left <= 0) return -1
        val b = super.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, left))
        left -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), left).toInt()

    override fun markSupported(): Boolean = false
}
