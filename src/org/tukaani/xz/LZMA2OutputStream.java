/*
 * LZMA2OutputStream
 *
 * Authors: Lasse Collin <lasse.collin@tukaani.org>
 *          Igor Pavlov <http://7-zip.org/>
 *
 * This file has been put into the public domain.
 * You can do whatever you want with this file.
 */

package org.tukaani.xz;

import java.io.DataOutputStream;
import java.io.IOException;
import org.tukaani.xz.lz.LZEncoder;
import org.tukaani.xz.rangecoder.RangeEncoder;
import org.tukaani.xz.lzma.LZMAEncoder;

class LZMA2OutputStream extends FinishableOutputStream {
    static final int COMPRESSED_SIZE_MAX = 64 << 10;

    private final FinishableOutputStream out;
    private final DataOutputStream outData;

    private final LZEncoder lz;
    private final RangeEncoder rc;
    private final LZMAEncoder lzma;

    private int props;
    private boolean dictResetNeeded = true;
    private boolean stateResetNeeded = true;
    private boolean propsNeeded = true;

    private int pendingSize = 0;
    private boolean finished = false;
    private IOException exception = null;

    private static int getExtraSizeBefore(int dictSize) {
        return COMPRESSED_SIZE_MAX > dictSize
               ? COMPRESSED_SIZE_MAX - dictSize : 0;
    }

    static int getMemoryUsage(LZMA2Options options) {
        // 64 KiB buffer for the range encoder + a little extra + LZMAEncoder
        int dictSize = options.getDictSize();
        int extraSizeBefore = getExtraSizeBefore(dictSize);
        return 70 + LZMAEncoder.getMemoryUsage(options.getMode(),
                                               dictSize, extraSizeBefore,
                                               options.getMatchFinder());
    }

    LZMA2OutputStream(FinishableOutputStream out, LZMA2Options options) {
        this.out = out;
        outData = new DataOutputStream(out);
        rc = new RangeEncoder(COMPRESSED_SIZE_MAX);

        int dictSize = options.getDictSize();
        int extraSizeBefore = getExtraSizeBefore(dictSize);
        lzma = LZMAEncoder.getInstance(rc,
                options.getLc(), options.getLp(), options.getPb(),
                options.getMode(),
                dictSize, extraSizeBefore, options.getNiceLen(),
                options.getMatchFinder(), options.getDepthLimit());

        lz = lzma.getLZEncoder();
        lz.setPresetDict(dictSize, options.getPresetDict());

        props = (options.getPb() * 5 + options.getLp()) * 9 + options.getLc();
    }

    public void write(int b) throws IOException {
        byte[] buf = new byte[1];
        buf[0] = (byte)b;
        write(buf, 0, 1);
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len < 0 || off + len > buf.length)
            throw new IndexOutOfBoundsException();

        if (exception != null)
            throw exception;

        if (finished)
            throw new XZIOException("Cannot write to a finished stream");

        try {
            while (len > 0) {
                int used = lz.fillWindow(buf, off, len);
                off += used;
                len -= used;
                pendingSize += used;

                if (lzma.encodeForLZMA2())
                    writeChunk();
            }
        } catch (IOException e) {
            exception = e;
            throw e;
        }
    }

    private void writeChunk() throws IOException {
        int compressedSize = rc.finish();
        int uncompressedSize = lzma.getUncompressedSize();

        // +2 because the header of a compressed chunk is 2 bytes
        // bigger than the header of an uncompressed chunk.
        if (compressedSize + 2 < uncompressedSize) {
            writeLZMA(uncompressedSize, compressedSize);
        } else {
            lzma.reset();
            uncompressedSize = lzma.getUncompressedSize();
            writeUncompressed(uncompressedSize);
        }

        pendingSize -= uncompressedSize;
        lzma.resetUncompressedSize();
        rc.reset();
    }

    private void writeLZMA(int uncompressedSize, int compressedSize)
            throws IOException {
        int control;

        if (propsNeeded) {
            if (dictResetNeeded)
                control = 0x80 + (3 << 5);
            else
                control = 0x80 + (2 << 5);
        } else {
            if (stateResetNeeded)
                control = 0x80 + (1 << 5);
            else
                control = 0x80;
        }

        control |= (uncompressedSize - 1) >>> 16;
        outData.writeByte(control);

        outData.writeShort(uncompressedSize - 1);
        outData.writeShort(compressedSize - 1);

        if (propsNeeded)
            outData.writeByte(props);

        rc.write(out);

        propsNeeded = false;
        stateResetNeeded = false;
        dictResetNeeded = false;
    }

    private void writeUncompressed(int uncompressedSize) throws IOException {
        while (uncompressedSize > 0) {
            int chunkSize = Math.min(uncompressedSize, COMPRESSED_SIZE_MAX);
            outData.writeByte(dictResetNeeded ? 0x01 : 0x02);
            outData.writeShort(chunkSize - 1);
            lz.copyUncompressed(out, uncompressedSize, chunkSize);
            uncompressedSize -= chunkSize;
            dictResetNeeded = false;
        }

        stateResetNeeded = true;
    }

    private void writeEndMarker() throws IOException {
        if (exception != null)
            throw exception;

        if (!finished) {
            lz.setFinishing();

            while (pendingSize > 0) {
                lzma.encodeForLZMA2();
                writeChunk();
            }

            out.write(0x00);
            finished = true;
        }
    }

    public void flush() throws IOException {
        if (exception != null)
            throw exception;

        if (!finished) {
            lz.setFlushing();

            while (pendingSize > 0) {
                lzma.encodeForLZMA2();
                writeChunk();
            }
        }

        out.flush();
    }

    public void finish() throws IOException {
        writeEndMarker();
        out.finish();
    }

    public void close() throws IOException {
        writeEndMarker();
        out.close();
    }
}
