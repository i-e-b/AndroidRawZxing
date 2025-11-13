package com.ieb.zxingtest;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;

public class UnsharpMaskBinarizer extends Binarizer {
    private final int scale;
    private final int bias;
    private final int morphRadius;

    private BitMatrix matrix = null;

    /**
     * Create a running-threshold binarizer
     *
     * @param source Luminance image source
     * @param scale Scale of running average. Adjust to pick out different detail levels.
     *              Range 1..8 inclusive. 5 or 6 are good defaults.
     * @param exposure Negative for lighter image, positive for darker. Zero is no bias. Between -16 and 16 seem to work in most cases.
     * @param closingRadius Radius to remove speckling. Set to zero to disable
     */
    protected UnsharpMaskBinarizer(LuminanceSource source, boolean invert, int scale, int exposure, int closingRadius) {
        super(invert ? source.invert() : source);
        this.scale = scale;
        this.bias = exposure;
        this.morphRadius = closingRadius;
    }

    // Work buffers
    private static int[] unsharpBuffer = null;
    private static int[] closingBuffer = null;
    private static int[] closingSamples = null;
    private static int[] closingTemp = null;

    private static final int UPPER_LIMIT = 240;
    private static final int LOWER_LIMIT = 16;


    /** Get a single row. This is optimised for 1D bar codes, and only averages across scan lines */
    @Override
    public BitArray getBlackRow(int y, BitArray row) {
        if (matrix == null) matrix = getBlackMatrix();
        return matrix.getRow(y, row);
    }

    /** Get a whole image. This is optimised for 2D bar codes, and averages in X and Y bases */
    @Override
    public BitMatrix getBlackMatrix() {
        if (matrix != null) return matrix;

        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        int height = source.getHeight();
        matrix = new BitMatrix(width, height);

        byte[] image = source.getMatrix();

        if (unsharpBuffer == null || unsharpBuffer.length < image.length) unsharpBuffer = new int[image.length+32];
        if (closingBuffer == null || closingBuffer.length < image.length) closingBuffer = new int[image.length+32];

        int radius = 1 << scale;
        int diam = scale + 1;
        int right = width - 1;
        int span = width * radius;
        int leadIn = (-radius) * width;

        for (int x = 0; x < width; x++) { // for each column
            int sum = 0;

            // feed in
            int row = leadIn;
            for (int i = -radius; i < radius; i++) {
                int y = Math.max(row, 0);
                sum += image[y + x] & 0xFF;
                row += width;
            }

            row = 0;
            var end = image.length - x - 1;
            for (int y = 0; y < height; y++) {
                unsharpBuffer[row + x] = sum >>> diam;

                // update running average
                int yr = Math.min(row + span, end);
                int yl = Math.max(row - span, 0);
                int incoming = image[yr + x] & 0xFF;
                int outgoing = image[yl + x] & 0xFF;

                sum += incoming - outgoing;
                row += width;
            }
        }

        for (int y = 0; y < height; y++) { // for each scanline
            int yOff = y * width;
            int sum = 0;

            // feed in
            for (int i = -radius; i < radius; i++) {
                int x = Math.max(i, 0);
                sum += unsharpBuffer[yOff + x];
            }

            // running average threshold
            for (int x = 0; x < width; x++) {
                // calculate threshold values
                int actual = (image[yOff + x] & 0xFF) - bias;
                int target = sum >>> diam;

                // don't let the target be too extreme
                if (target > UPPER_LIMIT) target = UPPER_LIMIT;
                if (target < LOWER_LIMIT) target = LOWER_LIMIT;

                // Decide what side of the threshold we are on
                // Write back to another temp so we can do closing transform
                closingBuffer[yOff + x] = (actual < target) ? 0 : -1;

                // update running average
                int xr = Math.min(x + radius, right);
                int xl = Math.max(x - radius, 0);
                int incoming = unsharpBuffer[yOff + xr];
                int outgoing = unsharpBuffer[yOff + xl];

                sum += incoming - outgoing;
            }
        }

        // Apply closing transform if radius is valid
        if (morphRadius > 0 && morphRadius < 10) {
            ErodeColumns(morphRadius, width, height);
            ErodeRows(morphRadius, width, height);

            DilateColumns(morphRadius, width, height);
            DilateRows(morphRadius, width, height);
        }

        // Copy the final into the matrix
        for (int y = 0; y < height; y++) {
            int yOff = y * width;
            for (int x = 0; x < width; x++) {
                if (closingBuffer[yOff + x] == 0) matrix.set(x, y);
            }
        }

        return matrix;
    }

    @Override
    public Binarizer createBinarizer(LuminanceSource source) {
        return new UnsharpMaskBinarizer(source, false, 5, 0, 0);
    }

    /** Dilate samples only horizontally */
    private static void DilateRows(int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcWidth < radius) return;

        var src = closingBuffer;

        var diameter = radius * 2;
        if (closingSamples == null || closingSamples.length < diameter) closingSamples = new int[diameter]; // search window
        if (closingTemp == null || closingTemp.length < srcWidth) closingTemp = new int[srcWidth]; // temporary row values
        var end     = srcWidth - 1;

        for (var y = 0; y < srcHeight; y++)
        {
            var yOff = y * srcWidth;

            // pre-load samples
            for (var i = 0; i < radius; i++)
            {
                closingSamples[i] = src[yOff]; // left side
                closingSamples[i + radius] = src[yOff + i]; // right side
            }

            var si = closingSamples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcWidth - radius;
            for (var x = 0; x < safeEnd; x++)
            {
                closingSamples[si++] = src[yOff + x + radius];
                if (si >= closingSamples.length) si = 0;

                var max = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] > max) max = closingSamples[i]; }
                closingTemp[x] = max;
            }

            // runoff
            for (var x = safeEnd; x < srcWidth; x++)
            {
                closingSamples[si++] = src[yOff + end];
                if (si >= closingSamples.length) si = 0;

                var max = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] > max) max = closingSamples[i]; }
                closingTemp[x] = max;
            }

            // copy back
            System.arraycopy(closingTemp, 0, src, yOff, srcWidth);
        }
    }

    /** Dilate samples only vertically */
    @SuppressWarnings({"ReassignedVariable", "SuspiciousNameCombination"})
    public static void DilateColumns(int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcHeight < radius) return;

        var src = closingBuffer;

        var diameter = radius * 2;
        if (closingSamples == null || closingSamples.length < diameter) closingSamples = new int[diameter]; // search window
        if (closingTemp == null || closingTemp.length < srcHeight) closingTemp = new int[srcHeight]; // temporary row values

        for (var x = 0; x < srcWidth; x++)
        {
            // pre-load samples
            var yOff    = x;
            for (var i = 0; i < radius; i++)
            {
                closingSamples[i] = src[x]; // top side
                closingSamples[i + radius] = src[yOff]; // bottom side
                yOff += srcWidth;
            }

            var si = closingSamples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcHeight - radius - 1;
            yOff = x + (radius * srcWidth);
            for (var y = 0; y < safeEnd; y++)
            {
                closingSamples[si++] = src[yOff];
                if (si >= closingSamples.length) si = 0;
                yOff += srcWidth;

                var max  = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] > max) max = closingSamples[i]; }
                closingTemp[y] = max;
            }

            // runoff
            for (var y = safeEnd; y < srcHeight; y++)
            {
                closingSamples[si++] = src[yOff];
                if (si >= closingSamples.length) si = 0;

                var max  = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] > max) max = closingSamples[i]; }
                closingTemp[y] = max;
            }

            // copy back
            yOff = x;
            for (var y = 0; y < srcHeight; y++)
            {
                src[yOff] = closingTemp[y];
                yOff += srcWidth;
            }
        }
    }


    /** Erode samples only horizontally */
    private static void ErodeRows(int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcWidth < radius) return;

        var src = closingBuffer;

        var diameter = radius * 2;
        if (closingSamples == null || closingSamples.length < diameter) closingSamples = new int[diameter]; // search window
        if (closingTemp == null || closingTemp.length < srcWidth) closingTemp = new int[srcWidth]; // temporary row values
        var end     = srcWidth - 1;

        for (var y = 0; y < srcHeight; y++)
        {
            var yOff = y * srcWidth;

            // pre-load samples
            for (var i = 0; i < radius; i++)
            {
                closingSamples[i] = src[yOff]; // left side
                closingSamples[i + radius] = src[yOff + i]; // right side
            }

            var si = closingSamples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcWidth - radius;
            for (var x = 0; x < safeEnd; x++)
            {
                closingSamples[si++] = src[yOff + x + radius];
                if (si >= closingSamples.length) si = 0;

                var min = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] < min) min = closingSamples[i]; }
                closingTemp[x] = min;
            }

            // runoff
            for (var x = safeEnd; x < srcWidth; x++)
            {
                closingSamples[si++] = src[yOff + end];
                if (si >= closingSamples.length) si = 0;

                var min = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] < min) min = closingSamples[i]; }
                closingTemp[x] = min;
            }

            // copy back
            System.arraycopy(closingTemp, 0, src, yOff, srcWidth);
        }
    }

    /** Erode samples only vertically */
    @SuppressWarnings({"ReassignedVariable", "SuspiciousNameCombination"})
    public static void ErodeColumns(int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcHeight < radius) return;

        var src = closingBuffer;

        var diameter = radius * 2;
        if (closingSamples == null || closingSamples.length < diameter) closingSamples = new int[diameter]; // search window
        if (closingTemp == null || closingTemp.length < srcHeight) closingTemp = new int[srcHeight]; // temporary row values

        for (var x = 0; x < srcWidth; x++)
        {
            // pre-load samples
            var yOff    = x;
            for (var i = 0; i < radius; i++)
            {
                closingSamples[i] = src[x]; // top side
                closingSamples[i + radius] = src[yOff]; // bottom side
                yOff += srcWidth;
            }

            var si = closingSamples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcHeight - radius - 1;
            yOff = x + (radius * srcWidth);
            for (var y = 0; y < safeEnd; y++)
            {
                closingSamples[si++] = src[yOff];
                if (si >= closingSamples.length) si = 0;
                yOff += srcWidth;

                var min  = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] < min) min = closingSamples[i]; }
                closingTemp[y] = min;
            }

            // runoff
            for (var y = safeEnd; y < srcHeight; y++)
            {
                closingSamples[si++] = src[yOff];
                if (si >= closingSamples.length) si = 0;

                var min  = closingSamples[0];
                for (var i = 1; i < closingSamples.length; i++) { if (closingSamples[i] < min) min = closingSamples[i]; }
                closingTemp[y] = min;
            }

            // copy back
            yOff = x;
            for (var y = 0; y < srcHeight; y++)
            {
                src[yOff] = closingTemp[y];
                yOff += srcWidth;
            }
        }
    }


}
