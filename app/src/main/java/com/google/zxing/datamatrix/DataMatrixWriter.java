/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.datamatrix;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.Writer;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.encoder.DefaultPlacement;
import com.google.zxing.Dimension;
import com.google.zxing.datamatrix.encoder.ErrorCorrection;
import com.google.zxing.datamatrix.encoder.HighLevelEncoder;
import com.google.zxing.datamatrix.encoder.MinimalEncoder;
import com.google.zxing.datamatrix.encoder.SymbolInfo;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import com.google.zxing.qrcode.encoder.ByteMatrix;

import java.util.Map;
import java.nio.charset.Charset;

/**
 * This object renders a Data Matrix code as a BitMatrix 2D array of greyscale values.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Guillaume Le Biller Added to zxing lib.
 */
public final class DataMatrixWriter implements Writer {

  @Override
  public BitMatrix encode(String contents, BarcodeFormat format, int width, int height) {
    return encode(contents, format, width, height, null);
  }

  @Override
  public BitMatrix encode(String contents, BarcodeFormat format, int width, int height, Map<EncodeHintType,?> hints) {

    if (contents.isEmpty()) {
      throw new IllegalArgumentException("Found empty contents");
    }

    if (format != BarcodeFormat.DATA_MATRIX) {
      throw new IllegalArgumentException("Can only encode DATA_MATRIX, but got " + format);
    }

    if (width < 0 || height < 0) {
      throw new IllegalArgumentException("Requested dimensions can't be negative: " + width + 'x' + height);
    }

    // Try to get force shape & min / max size
    SymbolShapeHint shape = SymbolShapeHint.FORCE_NONE;
    Dimension minSize = null;
    Dimension maxSize = null;
    if (hints != null) {
      SymbolShapeHint requestedShape = (SymbolShapeHint) hints.get(EncodeHintType.DATA_MATRIX_SHAPE);
      if (requestedShape != null) {
        shape = requestedShape;
      }
      @SuppressWarnings("deprecation")
      Dimension requestedMinSize = (Dimension) hints.get(EncodeHintType.MIN_SIZE);
      if (requestedMinSize != null) {
        minSize = requestedMinSize;
      }
      @SuppressWarnings("deprecation")
      Dimension requestedMaxSize = (Dimension) hints.get(EncodeHintType.MAX_SIZE);
      if (requestedMaxSize != null) {
        maxSize = requestedMaxSize;
      }
    }


    //1. step: Data encodation
    String encoded;

    boolean hasCompactionHint = hints != null && hints.containsKey(EncodeHintType.DATA_MATRIX_COMPACT) &&
        Boolean.parseBoolean(hints.get(EncodeHintType.DATA_MATRIX_COMPACT).toString());
    if (hasCompactionHint) {

      boolean hasGS1FormatHint = hints.containsKey(EncodeHintType.GS1_FORMAT) &&
          Boolean.parseBoolean(hints.get(EncodeHintType.GS1_FORMAT).toString());

      Charset charset = null;
      boolean hasEncodingHint = hints.containsKey(EncodeHintType.CHARACTER_SET);
      if (hasEncodingHint) {
        charset = Charset.forName(hints.get(EncodeHintType.CHARACTER_SET).toString());
      }
      encoded = MinimalEncoder.encodeHighLevel(contents, charset, hasGS1FormatHint ? 0x1D : -1, shape);
    } else {
      boolean hasForceC40Hint = hints != null && hints.containsKey(EncodeHintType.FORCE_C40) &&
          Boolean.parseBoolean(hints.get(EncodeHintType.FORCE_C40).toString());
      encoded = HighLevelEncoder.encodeHighLevel(contents, shape, minSize, maxSize, hasForceC40Hint);
    }

    SymbolInfo symbolInfo = SymbolInfo.lookup(encoded.length(), shape, minSize, maxSize, true);

    //2. step: ECC generation
    String codewords = ErrorCorrection.encodeECC200(encoded, symbolInfo);

    //3. step: Module placement in Matrix
    DefaultPlacement placement =
        new DefaultPlacement(codewords, symbolInfo.getSymbolDataWidth(), symbolInfo.getSymbolDataHeight());
    placement.place();

    //4. step: low-level encoding
    return encodeLowLevel(placement, symbolInfo, width, height);
  }

  /**
   * Encode the given symbol info to a bit matrix.
   *
   * @param placement  The DataMatrix placement.
   * @param symbolInfo The symbol info to encode.
   * @return The bit matrix generated.
   */
  private static BitMatrix encodeLowLevel(DefaultPlacement placement, SymbolInfo symbolInfo, int width, int height) {
    int symbolWidth = symbolInfo.getSymbolDataWidth();
    int symbolHeight = symbolInfo.getSymbolDataHeight();

    ByteMatrix matrix = new ByteMatrix(symbolInfo.getSymbolWidth(), symbolInfo.getSymbolHeight());

    int matrixY = 0;

    for (int y = 0; y < symbolHeight; y++) {
      // Fill the top edge with alternate 0 / 1
      int matrixX;
      if ((y % symbolInfo.matrixHeight) == 0) {
        matrixX = 0;
        for (int x = 0; x < symbolInfo.getSymbolWidth(); x++) {
          matrix.set(matrixX, matrixY, (x % 2) == 0);
          matrixX++;
        }
        matrixY++;
      }
      matrixX = 0;
      for (int x = 0; x < symbolWidth; x++) {
        // Fill the right edge with full 1
        if ((x % symbolInfo.matrixWidth) == 0) {
          matrix.set(matrixX, matrixY, true);
          matrixX++;
        }
        matrix.set(matrixX, matrixY, placement.getBit(x, y));
        matrixX++;
        // Fill the right edge with alternate 0 / 1
        if ((x % symbolInfo.matrixWidth) == symbolInfo.matrixWidth - 1) {
          matrix.set(matrixX, matrixY, (y % 2) == 0);
          matrixX++;
        }
      }
      matrixY++;
      // Fill the bottom edge with full 1
      if ((y % symbolInfo.matrixHeight) == symbolInfo.matrixHeight - 1) {
        matrixX = 0;
        for (int x = 0; x < symbolInfo.getSymbolWidth(); x++) {
          matrix.set(matrixX, matrixY, true);
          matrixX++;
        }
        matrixY++;
      }
    }

    return convertByteMatrixToBitMatrix(matrix, width, height);
  }

  /**
   * Convert the ByteMatrix to BitMatrix.
   *
   * @param reqHeight The requested height of the image (in pixels) with the Datamatrix code
   * @param reqWidth The requested width of the image (in pixels) with the Datamatrix code
   * @param matrix The input matrix.
   * @return The output matrix.
   */
  private static BitMatrix convertByteMatrixToBitMatrix(ByteMatrix matrix, int reqWidth, int reqHeight) {
    int matrixWidth = matrix.getWidth();
    int matrixHeight = matrix.getHeight();
    int outputWidth = Math.max(reqWidth, matrixWidth);
    int outputHeight = Math.max(reqHeight, matrixHeight);

    int multiple = Math.min(outputWidth / matrixWidth, outputHeight / matrixHeight);

    int leftPadding = (outputWidth - (matrixWidth * multiple)) / 2 ;
    int topPadding = (outputHeight - (matrixHeight * multiple)) / 2 ;

    BitMatrix output;

    // remove padding if requested width and height are too small
    if (reqHeight < matrixHeight || reqWidth < matrixWidth) {
      leftPadding = 0;
      topPadding = 0;
      output = new BitMatrix(matrixWidth, matrixHeight);
    } else {
      output = new BitMatrix(reqWidth, reqHeight);
    }

    output.clear();
    for (int inputY = 0, outputY = topPadding; inputY < matrixHeight; inputY++, outputY += multiple) {
      // Write the contents of this row of the bytematrix
      for (int inputX = 0, outputX = leftPadding; inputX < matrixWidth; inputX++, outputX += multiple) {
        if (matrix.get(inputX, inputY) == 1) {
          output.setRegion(outputX, outputY, multiple, multiple);
        }
      }
    }

    return output;
  }

}
