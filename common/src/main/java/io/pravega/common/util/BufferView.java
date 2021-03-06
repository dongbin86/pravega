/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.common.util;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Defines a generic read-only view of a readable memory buffer with a known length.
 *
 * For array-backed Buffers, see {@link ArrayView}.
 *
 * For any implementations that wrap direct memory (a {@link java.nio.ByteBuffer} or Netty ByteBuf and thus support
 * reference counting, consider using {@link #retain()} {@link #release()} to ensure the underlying buffer is correctly
 * managed. Invoke {@link #retain()} if this {@link BufferView} instance is to be needed for more than the buffer creator
 * anticipates (i.e., a background async task) and invoke {@link #release()} to notify that it is no longer needed. The
 * behavior of these two methods are dependent on the actual buffer implementation; for example, Netty ByteBufs only
 * release the memory once the internal reference count reaches 0 (refer to Netty ByteBuf documentation for more details).
 */
public interface BufferView {
    /**
     * Gets a value representing the length of this {@link BufferView}.
     *
     * @return The length.
     */
    int getLength();

    /**
     * Creates a new {@link BufferView.Reader} that can be used to read this {@link BufferView}. This reader is
     * preferable to {@link #getReader()} that returns an {@link InputStream} as it contains optimized methods for copying
     * directly into other {@link BufferView} instances, such as {@link ByteArraySegment}s.
     *
     * @return A new {@link BufferView.Reader}.
     */
    BufferView.Reader getBufferViewReader();

    /**
     * Creates an InputStream that can be used to read the contents of this {@link BufferView}. The InputStream returned
     * spans the entire {@link BufferView}.
     *
     * @return The InputStream.
     */
    InputStream getReader();

    /**
     * Creates an InputStream that can be used to read the contents of this {@link BufferView}.
     *
     * @param offset The starting offset of the section to read.
     * @param length The length of the section to read.
     * @return The InputStream.
     */
    InputStream getReader(int offset, int length);

    /**
     * Equivalent to invoking {@link #slice(int, int)} with offset 0 and getLength(). Depending on the implementation,
     * this may return this instance or a new instance that is a copy of this one but pointing to the same backing buffer.
     *
     * @return A {@link BufferView}.
     */
    default BufferView slice() {
        return this;
    }

    /**
     * Creates a new {@link BufferView} that represents a sub-range of this {@link BufferView} instance. The new instance
     * will share the same backing buffer as this one, so a change to one will be reflected in the other.
     *
     * @param offset The starting offset to begin the slice at.
     * @param length The sliced length.
     * @return A new {@link BufferView}.
     */
    BufferView slice(int offset, int length);

    /**
     * Returns a copy of the contents of this {@link BufferView}.
     *
     * @return A byte array with the same length as this ArrayView, containing a copy of the data within it.
     */
    byte[] getCopy();

    /**
     * Copies the contents of this {@link BufferView} to the given {@link OutputStream}.
     *
     * @param target The {@link OutputStream} to write to.
     * @throws IOException If an exception occurred while writing to the target {@link OutputStream}.
     */
    void copyTo(OutputStream target) throws IOException;

    /**
     * Copies the contents of this {@link BufferView} to the given {@link ByteBuffer}.
     *
     * @param byteBuffer The {@link ByteBuffer} to copy to. This buffer must have sufficient capacity to allow the entire
     *                   contents of the {@link BufferView} to be written. If less needs to be copied, consider using
     *                   {@link BufferView#slice} to select a sub-range of this {@link BufferView}.
     * @return The number of bytes copied.
     */
    int copyTo(ByteBuffer byteBuffer);

    /**
     * When implemented in a derived class, notifies any wrapped buffer that this {@link BufferView} has a need for it.
     * Use {@link #release()} to do the opposite. See the main documentation on this interface for recommendations on how
     * to use these to methods. Also refer to the implementing class' documentation for any additional details.
     */
    default void retain() {
        // Default implementation intentionally left blank. Any derived class may implement if needed.
    }

    /**
     * When implemented in a derived class, notifies any wrapped buffer that this {@link BufferView} no longer has a
     * need for it. After invoking this method, this object should no longer be considered safe to access as the underlying
     * buffer may have been deallocated (the actual behavior may vary based on the wrapped buffer, please refer to the
     * implementing class' documentation for any additional details).
     */
    default void release() {
        // Default implementation intentionally left blank. Any derived class may implement if needed.
    }

    /**
     * Gets a list of {@link ByteBuffer} that represent the contents of this {@link BufferView}.
     *
     * @return A List of {@link ByteBuffer}.
     */
    List<ByteBuffer> getContents();

    /**
     * Wraps the given {@link BufferView} into a single instance.
     *
     * @param components The components to wrap.
     * @return An empty {@link BufferView} (if the component list is empty), the first item in the list (if the component
     * list has 1 element) or a {@link CompositeBufferView} wrapping all the given instances otherwise.
     */
    static BufferView wrap(List<BufferView> components) {
        if (components.size() == 0) {
            return new ByteArraySegment(new byte[0]);
        } else if (components.size() == 1) {
            return components.get(0).slice();
        } else {
            return new CompositeBufferView(components);
        }
    }

    /**
     * Defines a reader for a {@link BufferView}.
     */
    interface Reader {
        /**
         * Gets a value indicating the number of bytes available to read.
         *
         * @return The number of bytes available to read.
         */
        int available();

        /**
         * Reads a number of bytes into the given {@link ByteArraySegment} using the most efficient method for the
         * implementation of this {@link BufferView}.
         *
         * @param segment The target {@link ByteArraySegment}.
         * @return The number of bytes copied. This should be <pre><code>Math.min(available(), segment.getLength())</code></pre>.
         * If this returns 0, then either the given {@link ByteArraySegment} has {@link ByteArraySegment#getLength()} equal
         * to 0, or there are no more bytes to be read ({@link #available()} is 0).
         */
        int readBytes(ByteArraySegment segment);

        /**
         * Reads all the remaining bytes from this {@link BufferView.Reader} into a new {@link ByteArraySegment}.
         *
         * @param bufferSize The maximum number of bytes to copy at each iteration. Set to {@link Integer#MAX_VALUE}
         *                   to attempt to copy the whole buffer at once.
         * @return A new {@link ByteArraySegment} with the remaining contents of {@link BufferView.Reader}.
         */
        @VisibleForTesting
        default ByteArraySegment readFully(int bufferSize) {
            ByteArraySegment readBuffer = new ByteArraySegment(new byte[available()]);
            int readOffset = 0;
            while (readOffset < readBuffer.getLength()) {
                int readLength = Math.min(available(), readBuffer.getLength() - readOffset);
                int readBytes = readBytes(readBuffer.slice(readOffset, Math.min(bufferSize, readLength)));
                readOffset += readBytes;
            }
            assert available() == 0;
            return readBuffer;
        }
    }
}
