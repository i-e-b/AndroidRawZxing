package com.ieb.zxingtest;

public class MorphologicalTransforms {

    /// <summary>
    /// for each pixel, select the lightest luminance within <paramref name="radius"/>
    /// </summary>
    public static byte[] Dilate2D(byte[] luminance, int width, int height, int radius)
    {
        DilateColumns(luminance, radius, width, height);
        DilateRows(luminance, radius, width, height);

        return luminance;
    }

    /// <summary>
    /// for each pixel, select the darkest luminance within <paramref name="radius"/>
    /// </summary>
    public static byte[] Erode2D(byte[] luminance, int width, int height, int radius)
    {
        ErodeColumns(luminance, radius, width, height);
        ErodeRows(luminance, radius, width, height);

        return luminance;
    }

    /// <summary>
    /// Perform an erode then dilate with the same radius.
    /// This should remove small speckles of light on dark backgrounds
    /// </summary>
    public static byte[] Opening2D(byte[] luminance, int width, int height, int radius)
    {
        ErodeColumns(luminance, radius, width, height);
        ErodeRows(luminance, radius, width, height);

        DilateColumns(luminance, radius, width, height);
        DilateRows(luminance, radius, width, height);

        return luminance;
    }

    /// <summary>
    /// Perform an dilate then erode with the same radius.
    /// This should remove small speckles of dark on light backgrounds
    /// </summary>
    public static byte[] Closing2D(byte[] luminance, int width, int height, int radius)
    {
        DilateColumns(luminance, radius, width, height);
        DilateRows(luminance, radius, width, height);

        ErodeColumns(luminance, radius, width, height);
        ErodeRows(luminance, radius, width, height);

        return luminance;
    }

    /// <summary>
    /// Dilate samples only horizontally
    /// </summary>
    public static void DilateRows(byte[] src, int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcWidth < radius) return;

        var samples = new byte[radius * 2]; // search window
        var temp    = new byte[srcWidth]; // temporary row values
        var end     = srcWidth - 1;

        for (var y = 0; y < srcHeight; y++)
        {
            var yOff = y * srcWidth;

            // pre-load samples
            for (var i = 0; i < radius; i++)
            {
                samples[i] = src[yOff]; // left side
                samples[i + radius] = src[yOff + i]; // right side
            }

            var si = samples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcWidth - radius;
            for (var x = 0; x < safeEnd; x++)
            {
                samples[si++] = src[yOff + x + radius];
                if (si >= samples.length) si = 0;

                var max = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] > max) max = samples[i]; }
                temp[x] = max;
            }

            // runoff
            for (var x = safeEnd; x < srcWidth; x++)
            {
                samples[si++] = src[yOff + end];
                if (si >= samples.length) si = 0;

                var max = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] > max) max = samples[i]; }
                temp[x] = max;
            }

            // copy back
            for (var x = 0; x < srcWidth; x++)
            {
                src[yOff + x] = temp[x];
            }
        }
    }

    /// <summary>
    /// Dilate samples only vertically
    /// </summary>
    public static void DilateColumns(byte[] src, int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcHeight < radius) return;

        var samples = new byte[radius * 2]; // search window
        var temp    = new byte[srcHeight]; // temporary column values

        for (var x = 0; x < srcWidth; x++)
        {
            // pre-load samples
            var yOff    = x;
            for (var i = 0; i < radius; i++)
            {
                samples[i] = src[x]; // top side
                samples[i + radius] = src[yOff]; // bottom side
                yOff += srcWidth;
            }

            var si = samples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcHeight - radius - 1;
            yOff = x + (radius * srcWidth);
            for (var y = 0; y < safeEnd; y++)
            {
                samples[si++] = src[yOff];
                if (si >= samples.length) si = 0;
                yOff += srcWidth;

                var max  = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] > max) max = samples[i]; }
                temp[y] = max;
            }

            // runoff
            for (var y = safeEnd; y < srcHeight; y++)
            {
                samples[si++] = src[yOff];
                if (si >= samples.length) si = 0;

                var max  = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] > max) max = samples[i]; }
                temp[y] = max;
            }

            // copy back
            yOff = x;
            for (var y = 0; y < srcHeight; y++)
            {
                src[yOff] = temp[y];
                yOff += srcWidth;
            }
        }
    }

    /// <summary>
    /// Erode samples only horizontally
    /// </summary>
    public static void ErodeRows(byte[] src, int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcWidth < radius) return;

        var samples = new byte[radius * 2]; // search window
        var temp    = new byte[srcWidth]; // temporary row values
        var end     = srcWidth - 1;

        for (var y = 0; y < srcHeight; y++)
        {
            var yOff = y * srcWidth;

            // pre-load samples
            for (var i = 0; i < radius; i++)
            {
                samples[i] = src[yOff]; // left side
                samples[i + radius] = src[yOff + i]; // right side
            }

            var si = samples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcWidth - radius;
            for (var x = 0; x < safeEnd; x++)
            {
                samples[si++] = src[yOff + x + radius];
                if (si >= samples.length) si = 0;

                var min = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] < min) min = samples[i]; }
                temp[x] = min;
            }

            // runoff
            for (var x = safeEnd; x < srcWidth; x++)
            {
                samples[si++] = src[yOff + end];
                if (si >= samples.length) si = 0;

                var min = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] < min) min = samples[i]; }
                temp[x] = min;
            }

            // copy back
            for (var x = 0; x < srcWidth; x++)
            {
                src[yOff + x] = temp[x];
            }
        }
    }

    /// <summary>
    /// Erode samples only vertically
    /// </summary>
    public static void ErodeColumns(byte[] src, int radius, int srcWidth, int srcHeight)
    {
        if (radius < 1) return;
        if (srcHeight < radius) return;

        var samples = new byte[radius * 2]; // search window
        var temp    = new byte[srcHeight]; // temporary column values

        for (var x = 0; x < srcWidth; x++)
        {
            // pre-load samples
            var yOff    = x;
            for (var i = 0; i < radius; i++)
            {
                samples[i] = src[x]; // top side
                samples[i + radius] = src[yOff]; // bottom side
                yOff += srcWidth;
            }

            var si = samples.length - 1; // index of next sample to overwrite.

            // main section
            var safeEnd = srcHeight - radius - 1;
            yOff = x + (radius * srcWidth);
            for (var y = 0; y < safeEnd; y++)
            {
                samples[si++] = src[yOff];
                if (si >= samples.length) si = 0;
                yOff += srcWidth;

                var min  = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] < min) min = samples[i]; }
                temp[y] = min;
            }

            // runoff
            for (var y = safeEnd; y < srcHeight; y++)
            {
                samples[si++] = src[yOff];
                if (si >= samples.length) si = 0;

                var min  = samples[0];
                for (var i = 1; i < samples.length; i++) { if (samples[i] < min) min = samples[i]; }
                temp[y] = min;
            }

            // copy back
            yOff = x;
            for (var y = 0; y < srcHeight; y++)
            {
                src[yOff] = temp[y];
                yOff += srcWidth;
            }
        }
    }
}
