/*
 * Copyright 2013 ZXing authors
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

package com.google.zxing.aztec.encoder;

import com.google.zxing.common.BitArray;

final class BinaryShiftToken extends Token {

  private final int binaryShiftStart;
  private final int binaryShiftByteCount;

  BinaryShiftToken(Token previous,
                   int binaryShiftStart,
                   int binaryShiftByteCount) {
    super(previous);
    this.binaryShiftStart = binaryShiftStart;
    this.binaryShiftByteCount = binaryShiftByteCount;
  }

  @Override
  public void appendTo(BitArray bitArray, byte[] text) {
    int bsbc = binaryShiftByteCount;
    for (int i = 0; i < bsbc; i++) {
      if (i == 0 || (i == 31 && bsbc <= 62)) {
        // We need a header before the first character, and before
        // character 31 when the total byte code is <= 62
        bitArray.appendBits(31, 5);  // BINARY_SHIFT
        if (bsbc > 62) {
          bitArray.appendBits(bsbc - 31, 16);
        } else if (i == 0) {
          // 1 <= binaryShiftByteCode <= 62
          bitArray.appendBits(Math.min(bsbc, 31), 5);
        } else {
          // 32 <= binaryShiftCount <= 62 and i == 31
          bitArray.appendBits(bsbc - 31, 5);
        }
      }
      bitArray.appendBits(text[binaryShiftStart + i], 8);
    }
  }

  @Override
  public String toString() {
    return "<" + binaryShiftStart + "::" + (binaryShiftStart + binaryShiftByteCount - 1) + '>';
  }

}
