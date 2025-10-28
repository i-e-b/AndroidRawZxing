package com.ieb.zxingtest;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;

public class HorizontalAverageBinarizer extends Binarizer {
    private final int radius;
    private final int bias;

    /**
     * Create a running-threshold binarizer
     *
     * @param source luminance image source
     * @param radius threshold radius
     * @param bias   negative for lighter image, positive for darker. Zero is no bias.
     */
    protected HorizontalAverageBinarizer(LuminanceSource source, int radius, int bias) {
        super(source);
        this.radius = radius;
        this.bias = bias;
    }

    private byte[] rowLuminances;

    @Override
    public BitArray getBlackRow(int y, BitArray row) {
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

        int diam = radius * 2;
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
            int target = sum / diam;

            // don't let the target be too extreme (this stops us turning white rows into black)
            if (target > 192) target = 192;

            // Decide what side of the threshold we are on
            if (actual <= target) row.set(x);

            // update running average
            int xr = Math.min(x + radius, right);
            int xl = Math.max(x - radius, 0);
            int incoming = srcRow[xr] & 0xFF;
            int outgoing = srcRow[xl] & 0xFF;

            sum += incoming - outgoing;
        }

        return row;
    }

    @Override
    public BitMatrix getBlackMatrix() {
        LuminanceSource source = getLuminanceSource();
        int width = source.getWidth();
        int height = source.getHeight();
        BitMatrix matrix = new BitMatrix(width, height);

        byte[] image = source.getMatrix();

        int diam = radius * 2;
        int right = width - 1;
        for (int y = 0; y < height; y++) { // for each scanline
            int yOff = y * width;
            int sum = 0;

            // feed in
            for (int i = -radius; i < radius; i++) {
                int x = Math.max(i, 0);
                sum += image[yOff + x] & 0xFF;
            }

            // running average threshold
            for (int x = 0; x < width; x++) {
                // calculate threshold values
                int actual = (image[yOff + x] & 0xFF) - bias;
                int target = sum / diam;

                // don't let the target be too extreme
                if (target > 192) target = 192;

                // Decide what side of the threshold we are on
                if (actual <= target) matrix.set(x, y);

                // update running average
                int xr = Math.min(x + radius, right);
                int xl = Math.max(x - radius, 0);
                int incoming = image[yOff + xr] & 0xFF;
                int outgoing = image[yOff + xl] & 0xFF;

                sum += incoming - outgoing;
            }
        }

        return matrix;
    }

    @Override
    public Binarizer createBinarizer(LuminanceSource source) {
        return new HorizontalAverageBinarizer(source, 32, 0);
    }
}
