package com.ieb.zxingtest;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;

public class UnsharpMaskBinarizer extends Binarizer {
    private final boolean invert;
    private final int scale;
    private final int bias;

    /**
     * Create a running-threshold binarizer
     *
     * @param source Luminance image source
     * @param scale Scale of running average. Adjust to pick out different detail levels.
     *              Range 1..8 inclusive. 5 or 6 are good defaults.
     * @param exposure Negative for lighter image, positive for darker. Zero is no bias. Between -16 and 16 seem to work in most cases.
     */
    protected UnsharpMaskBinarizer(LuminanceSource source, boolean invert, int scale, int exposure) {
        super(source);
        this.invert = invert;
        this.scale = scale;
        this.bias = exposure;
    }

    private static byte[] rowLuminances = null;
    private static int[] colLuminances = null;

    private static final int UPPER_LIMIT = 251;
    private static final int LOWER_LIMIT = 4;


    /** Get a single row. This is optimised for 1D bar codes, and only averages across scan lines */
    @Override
    public BitArray getBlackRow(int y, BitArray row) {
        // we split these up to keep the JIT happy. Being efficient with code slows this right down.
        if (invert) return getBlackRowInverted(y, row);
        return getBlackRowNormal(y, row);
    }

    public BitArray getBlackRowNormal(int y, BitArray row) {
        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        if (row == null || row.getSize() < width) {
            row = new BitArray(width);
        } else {
            row.clear();
        }

        if (rowLuminances == null || rowLuminances.length < width) {
            rowLuminances = new byte[width];
        }
        var srcRow = source.getRow(y, rowLuminances);

        int radius = 1 << scale;
        int diam = scale + 1;
        int right = width - 1;
        int sum = 0;

        // feed in
        for (int i = -radius; i < radius; i++) {
            int x = Math.max(i, 0);
            sum += srcRow[x] & 0xFF;
        }

        // running average threshold
        for (int x = 0; x < width; x++) {
            // calculate threshold values
            int actual = (srcRow[x] & 0xFF) - bias;
            int target = sum >>> diam;

            // don't let the target be too extreme (this stops us turning white rows into black)
            if (target > UPPER_LIMIT) target = UPPER_LIMIT;
            if (target < LOWER_LIMIT) target = LOWER_LIMIT;

            // Decide what side of the threshold we are on
            if (actual < target) row.set(x);

            // update running average
            int xr = Math.min(x + radius, right);
            int xl = Math.max(x - radius, 0);
            int incoming = srcRow[xr] & 0xFF;
            int outgoing = srcRow[xl] & 0xFF;

            sum += incoming - outgoing;
        }

        return row;
    }

    public BitArray getBlackRowInverted(int y, BitArray row) {
        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        if (row == null || row.getSize() < width) {
            row = new BitArray(width);
        } else {
            row.clear();
        }

        if (rowLuminances == null || rowLuminances.length < width) {
            rowLuminances = new byte[width];
        }
        var srcRow = source.getRow(y, rowLuminances);

        int radius = 1 << scale;
        int diam = scale + 1;
        int right = width - 1;
        int sum = 0;

        // feed in
        for (int i = -radius; i < radius; i++) {
            int x = Math.max(i, 0);
            sum += srcRow[x] & 0xFF;
        }

        // running average threshold
        for (int x = 0; x < width; x++) {
            // calculate threshold values
            int actual = (srcRow[x] & 0xFF) - bias;
            int target = sum >>> diam;

            // don't let the target be too extreme (this stops us turning white rows into black)
            if (target > UPPER_LIMIT) target = UPPER_LIMIT;
            if (target < LOWER_LIMIT) target = LOWER_LIMIT;

            // Decide what side of the threshold we are on
            if (actual > target) row.set(x);

            // update running average
            int xr = Math.min(x + radius, right);
            int xl = Math.max(x - radius, 0);
            int incoming = srcRow[xr] & 0xFF;
            int outgoing = srcRow[xl] & 0xFF;

            sum += incoming - outgoing;
        }

        return row;
    }


    /** Get a whole image. This is optimised for 2D bar codes, and averages in X and Y bases */
    @Override
    public BitMatrix getBlackMatrix() {
        // we split these up to keep the JIT happy. Being efficient with code slows this right down.
        if (invert) return getBlackMatrixInverted();
        return getBlackMatrixNormal();
    }

    private BitMatrix getBlackMatrixNormal() {
        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        int height = source.getHeight();
        BitMatrix matrix = new BitMatrix(width, height);

        byte[] image = source.getMatrix();

        if (colLuminances == null || colLuminances.length < image.length) colLuminances = new int[image.length+32];

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
                colLuminances[row + x] = sum >>> diam;

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
                sum += colLuminances[yOff + x];
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
                if (actual < target) matrix.set(x, y);

                // update running average
                int xr = Math.min(x + radius, right);
                int xl = Math.max(x - radius, 0);
                int incoming = colLuminances[yOff + xr];
                int outgoing = colLuminances[yOff + xl];

                sum += incoming - outgoing;
            }
        }

        return matrix;
    }

    private BitMatrix getBlackMatrixInverted() {
        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        int height = source.getHeight();
        BitMatrix matrix = new BitMatrix(width, height);

        byte[] image = source.getMatrix();

        if (colLuminances == null || colLuminances.length < image.length) colLuminances = new int[image.length+32];

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
                colLuminances[row + x] = sum >>> diam;

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
                sum += colLuminances[yOff + x];
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
                if (actual > target) matrix.set(x,y);

                // update running average
                int xr = Math.min(x + radius, right);
                int xl = Math.max(x - radius, 0);
                int incoming = colLuminances[yOff + xr];
                int outgoing = colLuminances[yOff + xl];

                sum += incoming - outgoing;
            }
        }

        return matrix;
    }

    @Override
    public Binarizer createBinarizer(LuminanceSource source) {
        return new UnsharpMaskBinarizer(source, false, 5, 0);
    }
}
