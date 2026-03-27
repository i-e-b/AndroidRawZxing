package com.ieb.zxingtest;

// Derived from source - https://stackoverflow.com/a/7817663
// Posted by Gerry Beauregard, modified by community. See post 'Timeline' for change history
// Retrieved 2026-03-26, License - CC BY-SA 4.0
/*
 * <p>
 * Released under the MIT License
 * <p>
 * Copyright (c) 2010 Gerald T. Beauregard
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Performs an in-place complex FFT.
 */
public class FFT
{
    private final double[] _re; // Real components
    private final double[] _im; // Imaginary components
    private final int[]    _revTgt; // Target position post bit-reversal

    private final int _logN; // log2 of FFT size
    private final int _n; // FFT size

    private static int Log2(int n) {return (int)(Math.log(n) / Math.log(2));} // For Java
    //private static int Log2(int n) {return (int)Math.Log2(n);} // For C#

    private static int NextPower2(int v){v--;v |= v >> 1;v |= v >> 2;v |= v >> 4;v |= v >> 8;v |= v >> 16;v++;return v;}

    private static final Map<Integer, FFT> _cachedScales = new HashMap<>();


    /// <summary>
    /// Set up a FFT transform for given input size
    /// </summary>
    /// <param name="n">size of the input data</param>
    public static FFT ForSize(int n)
    {
        var logN = Log2(NextPower2(n));
        if (_cachedScales.containsKey(logN)){
            return _cachedScales.get(logN);
        }

        var result = new FFT(logN);
        _cachedScales.put(logN, result);
        return result;
    }

    /// <summary>
    /// Set up a FFT transform for given scale
    /// </summary>
    /// <param name="logN">log2 of FFT size</param>
    @SuppressWarnings("unused")
    public static FFT ForScale(int logN)
    {
        if (_cachedScales.containsKey(logN)){
            return _cachedScales.get(logN);
        }

        var result = new FFT(logN);
        _cachedScales.put(logN, result);
        return result;
    }

    /// <summary>
    /// Set up a FFT transform for given scale
    /// </summary>
    /// <param name="logN">log2 of FFT size</param>
    private FFT(int logN)
    {
        _logN = logN;
        _n = 1 << _logN;

        // Allocate elements for linked list of complex numbers.
        _re = new double[_n];
        _im = new double[_n];
        _revTgt = new int[_n];

        // Specify target for bitwise reversal re-ordering.
        for (var k = 0; k < _n; k++)
        {
            _revTgt[k] = BitReverse(k, logN);
        }
    }

    /// <summary>
    /// Basic row/column transposition.
    /// This does <b>not</b> require a power-of-two size.
    /// </summary>
    public static double[] Transpose(double[] samples, int a, int b)
    {
        var length = b * a;
        if (samples.length < length){
            Log.w("FFT", "Invalid transpose");
            return samples;
        }

        var result = new double[length];
        var end = length - 1;

        var src = 0;
        var dst = 0;
        while (src < length)
        {
            result[dst] = samples[src++];
            dst += a;
            if (dst > end) dst -= end;
        }

        return result;
    }

    /// <summary>
    /// Performs in-place complex FFT
    /// </summary>
    /// <param name="xRe">Real part of input/output</param>
    /// <param name="xIm">Imaginary part of input/output</param>
    public void SpaceToFrequency(double[] xRe, double[] xIm)
    {
        Run(xRe, xIm, false);
    }

    /// <summary>
    /// Performs in-place complex iFFT
    /// </summary>
    /// <param name="xRe">Real part of input/output</param>
    /// <param name="xIm">Imaginary part of input/output</param>
    public void FrequencyToSpace(double[] xRe, double[] xIm)
    {
        Run(xRe, xIm, true);
    }

    /// <summary>
    /// Performs in-place complex FFT
    /// </summary>
    /// <param name="xRe">Real part of input/output</param>
    /// <param name="xIm">Imaginary part of input/output</param>
    /// <param name="inverse">If true, do an inverse FFT</param>
    private void Run(
        double[] xRe,
        double[] xIm,
        boolean inverse )
    {
        var numFlies   = _n >> 1; // Number of butterflies per sub-FFT
        var span       = _n >> 1; // Width of the butterfly
        var spacing    = _n; // Distance between start of sub-FFTs
        var wIndexStep = 1; // Increment for twiddle table index

        // Copy data into linked complex number objects
        // If it's an iFFT, we divide by N while we're at it
        var scale = inverse ? 1.0 / _n : 1.0;

        // copy image into Fourier buffer with reflection if it's not a power of 2
        var sigLen = xRe.length;
        for (var i = 0; i < sigLen; i++)
        {
            _re[i] = scale * xRe[i];
            _im[i] = scale * xIm[i];
        }
        for (var i = sigLen; i < _re.length; i++)
        {
            var x = sigLen - (i - sigLen) - 1;
            _re[i] = scale * xRe[x];
            _im[i] = 0;
        }

        // For each stage of the FFT
        for (var stage = 0; stage < _logN; stage++)
        {
            // Compute a multiplier factor for the "twiddle factors".
            // The twiddle factors are complex unit vectors spaced at
            // regular angular intervals. The angle by which the twiddle
            // factor advances depends on the FFT stage. In many FFT
            // implementations the twiddle factors are cached, but because
            // array lookup is relatively slow in C#, it's just
            // as fast to compute them on the fly.
            var wAngleInc = wIndexStep * 2.0 * Math.PI / _n;
            if (!inverse) wAngleInc *= -1;
            var wMulRe = Math.cos(wAngleInc);
            var wMulIm = Math.sin(wAngleInc);

            for (var start = 0; start < _n; start += spacing)
            {
                var xTop = start;
                var xBot = start+span;

                var wRe = 1.0;
                var wIm = 0.0;

                // For each butterfly in this stage
                for (var flyCount = 0; flyCount < numFlies; ++flyCount)
                {
                    if (xTop < 0 || xBot < 0) break;

                    // Get the top & bottom values
                    var xTopRe = _re[xTop];
                    var xTopIm = _im[xTop];
                    var xBotRe = _re[xBot];
                    var xBotIm = _im[xBot];

                    // Top branch of butterfly has addition
                    _re[xTop] = xTopRe + xBotRe;
                    _im[xTop] = xTopIm + xBotIm;

                    // Bottom branch of butterfly has subtraction,
                    // followed by multiplication by twiddle factor
                    xBotRe = xTopRe - xBotRe;
                    xBotIm = xTopIm - xBotIm;
                    _re[xBot] = xBotRe * wRe - xBotIm * wIm;
                    _im[xBot] = xBotRe * wIm + xBotIm * wRe;

                    // Advance butterfly to next top & bottom positions
                    xTop++;
                    xBot++;

                    // Update the twiddle factor, via complex multiply
                    // by unit vector with the appropriate angle
                    // (wRe + j wIm) = (wRe + j wIm) x (wMulRe + j wMulIm)
                    var tRe = wRe;
                    wRe = wRe * wMulRe - wIm * wMulIm;
                    wIm = tRe * wMulIm + wIm * wMulRe;
                }
            }

            numFlies >>= 1;     // Divide by 2 by right shift
            span >>= 1;
            spacing >>= 1;
            wIndexStep <<= 1;   // Multiply by 2 by left shift
        }

        // The algorithm leaves the result in a scrambled order.
        // Unscramble while copying values back
        for (var i = 0; i < _re.length; i++)
        {
            var target = _revTgt[i];
            if (target >= xRe.length) continue;

            xRe[target] = _re[i];
            xIm[target] = _im[i];
        }
    }

    /// <summary>
    /// Do bit reversal of specified number of places of an int.
    /// For example, 1101 bit-reversed is 1011
    /// </summary>
    /// <param name="x">Number to be bit-reverse</param>
    /// <param name="numBits">Number of bits in the number</param>
    private static int BitReverse(int x, int numBits)
    {
        var y = 0;
        for (var i = 0; i < numBits; i++)
        {
            y <<= 1;
            y |= x & 0x0001;
            x >>= 1;
        }
        return y;
    }

    /// <summary>
    /// Adjust values to fill range
    /// </summary>
    public static void Normalise(double[] values, double max)
    {
        var top = values[0];
        var bot = values[0];

        for (var i = 1; i < values.length; i++)
        {
            top = Math.max(top, values[i]);
            bot = Math.min(bot, values[i]);
        }

        var range = top - bot;
        var scale = max / range;


        for (var i = 0; i < values.length; i++)
        {
            values[i] = (values[i] - bot) * scale;
        }
    }
}