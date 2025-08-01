/*
 * Copyright 2009 ZXing authors
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

package com.google.zxing.pdf417.decoder;

import com.google.zxing.FormatException;
import com.google.zxing.common.ECIStringBuilder;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.pdf417.PDF417ResultMetadata;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * <p>This class contains the methods for decoding the PDF417 codewords.</p>
 *
 * @author SITA Lab (kevin.osullivan@sita.aero)
 * @author Guenther Grau
 */
final class DecodedBitStreamParser {

  private enum Mode {
    ALPHA,
    LOWER,
    MIXED,
    PUNCT,
    ALPHA_SHIFT,
    PUNCT_SHIFT
  }

  private static final int TEXT_COMPACTION_MODE_LATCH = 900;
  private static final int BYTE_COMPACTION_MODE_LATCH = 901;
  private static final int NUMERIC_COMPACTION_MODE_LATCH = 902;
  private static final int BYTE_COMPACTION_MODE_LATCH_6 = 924;
  private static final int ECI_USER_DEFINED = 925;
  private static final int ECI_GENERAL_PURPOSE = 926;
  private static final int ECI_CHARSET = 927;
  private static final int BEGIN_MACRO_PDF417_CONTROL_BLOCK = 928;
  private static final int BEGIN_MACRO_PDF417_OPTIONAL_FIELD = 923;
  private static final int MACRO_PDF417_TERMINATOR = 922;
  private static final int MODE_SHIFT_TO_BYTE_COMPACTION_MODE = 913;
  private static final int MAX_NUMERIC_CODEWORDS = 15;

  private static final int MACRO_PDF417_OPTIONAL_FIELD_FILE_NAME = 0;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_SEGMENT_COUNT = 1;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_TIME_STAMP = 2;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_SENDER = 3;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_ADDRESSEE = 4;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_FILE_SIZE = 5;
  private static final int MACRO_PDF417_OPTIONAL_FIELD_CHECKSUM = 6;

  private static final int PL = 25;
  private static final int LL = 27;
  private static final int AS = 27;
  private static final int ML = 28;
  private static final int AL = 28;
  private static final int PS = 29;
  private static final int PAL = 29;

  private static final char[] PUNCT_CHARS =
      ";<>@[\\]_`~!\r\t,:\n-.$/\"|*()?{}'".toCharArray();

  private static final char[] MIXED_CHARS =
      "0123456789&\r\t,:#-.$/+%*=^".toCharArray();

  /**
   * Table containing values for the exponent of 900.
   * This is used in the numeric compaction decode algorithm.
   */
  private static final BigInteger[] EXP900;

  static {
    EXP900 = new BigInteger[16];
    EXP900[0] = BigInteger.ONE;
    BigInteger nineHundred = BigInteger.valueOf(900);
    EXP900[1] = nineHundred;
    for (int i = 2; i < EXP900.length; i++) {
      EXP900[i] = EXP900[i - 1].multiply(nineHundred);
    }
  }

  private static final int NUMBER_OF_SEQUENCE_CODEWORDS = 2;

  private DecodedBitStreamParser() {
  }

  static DecoderResult decode(int[] codewords, String ecLevel) throws FormatException {
    ECIStringBuilder result = new ECIStringBuilder(codewords.length * 2);
    int codeIndex = textCompaction(codewords, 1, result);
    PDF417ResultMetadata resultMetadata = new PDF417ResultMetadata();
    while (codeIndex < codewords[0]) {
      int code = codewords[codeIndex++];
      switch (code) {
        case TEXT_COMPACTION_MODE_LATCH:
          codeIndex = textCompaction(codewords, codeIndex, result);
          break;
        case BYTE_COMPACTION_MODE_LATCH:
        case BYTE_COMPACTION_MODE_LATCH_6:
          codeIndex = byteCompaction(code, codewords, codeIndex, result);
          break;
        case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
          result.append((char) codewords[codeIndex++]);
          break;
        case NUMERIC_COMPACTION_MODE_LATCH:
          codeIndex = numericCompaction(codewords, codeIndex, result);
          break;
        case ECI_CHARSET:
          result.appendECI(codewords[codeIndex++]);
          break;
        case ECI_GENERAL_PURPOSE:
          // Can't do anything with generic ECI; skip its 2 characters
          codeIndex += 2;
          break;
        case ECI_USER_DEFINED:
          // Can't do anything with user ECI; skip its 1 character
          codeIndex++;
          break;
        case BEGIN_MACRO_PDF417_CONTROL_BLOCK:
          codeIndex = decodeMacroBlock(codewords, codeIndex, resultMetadata);
          break;
        case BEGIN_MACRO_PDF417_OPTIONAL_FIELD:
        case MACRO_PDF417_TERMINATOR:
          // Should not see these outside a macro block
          throw FormatException.getFormatInstance();
        default:
          // Default to text compaction. During testing numerous barcodes
          // appeared to be missing the starting mode. In these cases defaulting
          // to text compaction seems to work.
          codeIndex--;
          codeIndex = textCompaction(codewords, codeIndex, result);
          break;
      }
    }
    if (result.isEmpty() && resultMetadata.getFileId() == null) {
      throw FormatException.getFormatInstance();
    }
    DecoderResult decoderResult = new DecoderResult(null, result.toString(), null, ecLevel);
    decoderResult.setOther(resultMetadata);
    return decoderResult;
  }

  @SuppressWarnings("deprecation")
  static int decodeMacroBlock(int[] codewords, int codeIndex, PDF417ResultMetadata resultMetadata)
      throws FormatException {
    if (codeIndex + NUMBER_OF_SEQUENCE_CODEWORDS > codewords[0]) {
      // we must have at least two bytes left for the segment index
      throw FormatException.getFormatInstance();
    }
    int[] segmentIndexArray = new int[NUMBER_OF_SEQUENCE_CODEWORDS];
    for (int i = 0; i < NUMBER_OF_SEQUENCE_CODEWORDS; i++, codeIndex++) {
      segmentIndexArray[i] = codewords[codeIndex];
    }
    String segmentIndexString = decodeBase900toBase10(segmentIndexArray, NUMBER_OF_SEQUENCE_CODEWORDS);
    if (segmentIndexString.isEmpty()) {
      resultMetadata.setSegmentIndex(0);
    } else {
      try {
        resultMetadata.setSegmentIndex(Integer.parseInt(segmentIndexString));
      } catch (NumberFormatException nfe) {
        // too large; bad input?
        throw FormatException.getFormatInstance();
      }
    }

    // Decoding the fileId codewords as 0-899 numbers, each 0-filled to width 3. This follows the spec
    // (See ISO/IEC 15438:2015 Annex H.6) and preserves all info, but some generators (e.g. TEC-IT) write
    // the fileId using text compaction, so in those cases the fileId will appear mangled.
    StringBuilder fileId = new StringBuilder();
    while (codeIndex < codewords[0] &&
           codeIndex < codewords.length &&
           codewords[codeIndex] != MACRO_PDF417_TERMINATOR &&
           codewords[codeIndex] != BEGIN_MACRO_PDF417_OPTIONAL_FIELD) {
      fileId.append(String.format("%03d", codewords[codeIndex]));
      codeIndex++;
    }
    if (fileId.length() == 0) {
      // at least one fileId codeword is required (Annex H.2)
      throw FormatException.getFormatInstance();
    }
    resultMetadata.setFileId(fileId.toString());

    int optionalFieldsStart = -1;
    if (codewords[codeIndex] == BEGIN_MACRO_PDF417_OPTIONAL_FIELD) {
      optionalFieldsStart = codeIndex + 1;
    }

    while (codeIndex < codewords[0]) {
      switch (codewords[codeIndex]) {
        case BEGIN_MACRO_PDF417_OPTIONAL_FIELD:
          codeIndex++;
          switch (codewords[codeIndex]) {
            case MACRO_PDF417_OPTIONAL_FIELD_FILE_NAME:
              ECIStringBuilder fileName = new ECIStringBuilder();
              codeIndex = textCompaction(codewords, codeIndex + 1, fileName);
              resultMetadata.setFileName(fileName.toString());
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_SENDER:
              ECIStringBuilder sender = new ECIStringBuilder();
              codeIndex = textCompaction(codewords, codeIndex + 1, sender);
              resultMetadata.setSender(sender.toString());
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_ADDRESSEE:
              ECIStringBuilder addressee = new ECIStringBuilder();
              codeIndex = textCompaction(codewords, codeIndex + 1, addressee);
              resultMetadata.setAddressee(addressee.toString());
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_SEGMENT_COUNT:
              ECIStringBuilder segmentCount = new ECIStringBuilder();
              codeIndex = numericCompaction(codewords, codeIndex + 1, segmentCount);
              try {
                resultMetadata.setSegmentCount(Integer.parseInt(segmentCount.toString()));
              } catch (NumberFormatException nfe) {
                throw FormatException.getFormatInstance();
              }
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_TIME_STAMP:
              ECIStringBuilder timestamp = new ECIStringBuilder();
              codeIndex = numericCompaction(codewords, codeIndex + 1, timestamp);
              try {
                resultMetadata.setTimestamp(Long.parseLong(timestamp.toString()));
              } catch (NumberFormatException nfe) {
                throw FormatException.getFormatInstance();
              }
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_CHECKSUM:
              ECIStringBuilder checksum = new ECIStringBuilder();
              codeIndex = numericCompaction(codewords, codeIndex + 1, checksum);
              try {
                resultMetadata.setChecksum(Integer.parseInt(checksum.toString()));
              } catch (NumberFormatException nfe) {
                throw FormatException.getFormatInstance();
              }
              break;
            case MACRO_PDF417_OPTIONAL_FIELD_FILE_SIZE:
              ECIStringBuilder fileSize = new ECIStringBuilder();
              codeIndex = numericCompaction(codewords, codeIndex + 1, fileSize);
              try {
                resultMetadata.setFileSize(Long.parseLong(fileSize.toString()));
              } catch (NumberFormatException nfe) {
                throw FormatException.getFormatInstance();
              }
              break;
            default:
              throw FormatException.getFormatInstance();
          }
          break;
        case MACRO_PDF417_TERMINATOR:
          codeIndex++;
          resultMetadata.setLastSegment(true);
          break;
        default:
          throw FormatException.getFormatInstance();
      }
    }

    // copy optional fields to additional options
    if (optionalFieldsStart != -1) {
      int optionalFieldsLength = codeIndex - optionalFieldsStart;
      if (resultMetadata.isLastSegment()) {
        // do not include terminator
        optionalFieldsLength--;
      }
      if (optionalFieldsLength > 0) {
        resultMetadata.setOptionalData(Arrays.copyOfRange(codewords,
            optionalFieldsStart, optionalFieldsStart + optionalFieldsLength));
      }
    }

    return codeIndex;
  }

  /**
   * Text Compaction mode (see 5.4.1.5) permits all printable ASCII characters to be
   * encoded, i.e. values 32 - 126 inclusive in accordance with ISO/IEC 646 (IRV), as
   * well as selected control characters.
   *
   * @param codewords The array of codewords (data + error)
   * @param codeIndex The current index into the codeword array.
   * @param result    The decoded data is appended to the result.
   * @return The next index into the codeword array.
   */
  private static int textCompaction(int[] codewords, int codeIndex, ECIStringBuilder result) throws FormatException {
    // 2 character per codeword
    int[] textCompactionData = new int[(codewords[0] - codeIndex) * 2];
    // Used to hold the byte compaction value if there is a mode shift
    int[] byteCompactionData = new int[(codewords[0] - codeIndex) * 2];

    int index = 0;
    boolean end = false;
    Mode subMode = Mode.ALPHA;
    while ((codeIndex < codewords[0]) && !end) {
      int code = codewords[codeIndex++];
      if (code < TEXT_COMPACTION_MODE_LATCH) {
        textCompactionData[index] = code / 30;
        textCompactionData[index + 1] = code % 30;
        index += 2;
      } else {
        switch (code) {
          case TEXT_COMPACTION_MODE_LATCH:
            // reinitialize text compaction mode to alpha sub mode
            textCompactionData[index++] = TEXT_COMPACTION_MODE_LATCH;
            break;
          case BYTE_COMPACTION_MODE_LATCH:
          case BYTE_COMPACTION_MODE_LATCH_6:
          case NUMERIC_COMPACTION_MODE_LATCH:
          case BEGIN_MACRO_PDF417_CONTROL_BLOCK:
          case BEGIN_MACRO_PDF417_OPTIONAL_FIELD:
          case MACRO_PDF417_TERMINATOR:
            codeIndex--;
            end = true;
            break;
          case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
            // The Mode Shift codeword 913 shall cause a temporary
            // switch from Text Compaction mode to Byte Compaction mode.
            // This switch shall be in effect for only the next codeword,
            // after which the mode shall revert to the prevailing sub-mode
            // of the Text Compaction mode. Codeword 913 is only available
            // in Text Compaction mode; its use is described in 5.4.2.4.
            textCompactionData[index] = MODE_SHIFT_TO_BYTE_COMPACTION_MODE;
            code = codewords[codeIndex++];
            byteCompactionData[index] = code;
            index++;
            break;
          case ECI_CHARSET:
            subMode = decodeTextCompaction(textCompactionData, byteCompactionData, index, result, subMode);
            result.appendECI(codewords[codeIndex++]);
            if (codeIndex > codewords[0]) {
              throw FormatException.getFormatInstance();
            }
            textCompactionData = new int[(codewords[0] - codeIndex) * 2];
            byteCompactionData = new int[(codewords[0] - codeIndex) * 2];
            index = 0;
            break;
        }
      }
    }
    decodeTextCompaction(textCompactionData, byteCompactionData, index, result, subMode);
    return codeIndex;
  }

  /**
   * The Text Compaction mode includes all the printable ASCII characters
   * (i.e. values from 32 to 126) and three ASCII control characters: HT or tab
   * (ASCII value 9), LF or line feed (ASCII value 10), and CR or carriage
   * return (ASCII value 13). The Text Compaction mode also includes various latch
   * and shift characters which are used exclusively within the mode. The Text
   * Compaction mode encodes up to 2 characters per codeword. The compaction rules
   * for converting data into PDF417 codewords are defined in 5.4.2.2. The sub-mode
   * switches are defined in 5.4.2.3.
   *
   * @param textCompactionData The text compaction data.
   * @param byteCompactionData The byte compaction data if there
   *                           was a mode shift.
   * @param length             The size of the text compaction and byte compaction data.
   * @param result             The decoded data is appended to the result.
   * @param startMode          The mode in which decoding starts
   * @return The mode in which decoding ended
   */
  private static Mode decodeTextCompaction(int[] textCompactionData,
                                           int[] byteCompactionData,
                                           int length,
                                           ECIStringBuilder result,
                                           Mode startMode) {
    // Beginning from an initial state
    // The default compaction mode for PDF417 in effect at the start of each symbol shall always be Text
    // Compaction mode Alpha sub-mode (uppercase alphabetic). A latch codeword from another mode to the Text
    // Compaction mode shall always switch to the Text Compaction Alpha sub-mode.
    Mode subMode = startMode;
    Mode priorToShiftMode = startMode;
    Mode latchedMode = startMode;
    int i = 0;
    while (i < length) {
      int subModeCh = textCompactionData[i];
      char ch = 0;
      switch (subMode) {
        case ALPHA:
          // Alpha (uppercase alphabetic)
          if (subModeCh < 26) {
            // Upper case Alpha Character
            ch = (char) ('A' + subModeCh);
          } else {
            switch (subModeCh) {
              case 26:
                ch = ' ';
                break;
              case LL:
                subMode = Mode.LOWER;
                latchedMode = subMode;
                break;
              case ML:
                subMode = Mode.MIXED;
                latchedMode = subMode;
                break;
              case PS:
                // Shift to punctuation
                priorToShiftMode = subMode;
                subMode = Mode.PUNCT_SHIFT;
                break;
              case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
                result.append((char) byteCompactionData[i]);
                break;
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                latchedMode = subMode;
                break;
            }
          }
          break;

        case LOWER:
          // Lower (lowercase alphabetic)
          if (subModeCh < 26) {
            ch = (char) ('a' + subModeCh);
          } else {
            switch (subModeCh) {
              case 26:
                ch = ' ';
                break;
              case AS:
                // Shift to alpha
                priorToShiftMode = subMode;
                subMode = Mode.ALPHA_SHIFT;
                break;
              case ML:
                subMode = Mode.MIXED;
                latchedMode = subMode;
                break;
              case PS:
                // Shift to punctuation
                priorToShiftMode = subMode;
                subMode = Mode.PUNCT_SHIFT;
                break;
              case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
                result.append((char) byteCompactionData[i]);
                break;
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                latchedMode = subMode;
                break;
            }
          }
          break;

        case MIXED:
          // Mixed (numeric and some punctuation)
          if (subModeCh < PL) {
            ch = MIXED_CHARS[subModeCh];
          } else {
            switch (subModeCh) {
              case PL:
                subMode = Mode.PUNCT;
                latchedMode = subMode;
                break;
              case 26:
                ch = ' ';
                break;
              case LL:
                subMode = Mode.LOWER;
                latchedMode = subMode;
                break;
              case AL:
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                latchedMode = subMode;
                break;
              case PS:
                // Shift to punctuation
                priorToShiftMode = subMode;
                subMode = Mode.PUNCT_SHIFT;
                break;
              case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
                result.append((char) byteCompactionData[i]);
                break;
            }
          }
          break;

        case PUNCT:
          // Punctuation
          if (subModeCh < PAL) {
            ch = PUNCT_CHARS[subModeCh];
          } else {
            switch (subModeCh) {
              case PAL:
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                latchedMode = subMode;
                break;
              case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
                result.append((char) byteCompactionData[i]);
                break;
            }
          }
          break;

        case ALPHA_SHIFT:
          // Restore sub-mode
          subMode = priorToShiftMode;
          if (subModeCh < 26) {
            ch = (char) ('A' + subModeCh);
          } else {
            switch (subModeCh) {
              case 26:
                ch = ' ';
                break;
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                break;
            }
          }
          break;

        case PUNCT_SHIFT:
          // Restore sub-mode
          subMode = priorToShiftMode;
          if (subModeCh < PAL) {
            ch = PUNCT_CHARS[subModeCh];
          } else {
            switch (subModeCh) {
              case PAL:
              case TEXT_COMPACTION_MODE_LATCH:
                subMode = Mode.ALPHA;
                break;
              case MODE_SHIFT_TO_BYTE_COMPACTION_MODE:
                // PS before Shift-to-Byte is used as a padding character,
                // see 5.4.2.4 of the specification
                result.append((char) byteCompactionData[i]);
                break;
            }
          }
          break;
      }
      if (ch != 0) {
        // Append decoded character to result
        result.append(ch);
      }
      i++;
    }
    return latchedMode;
  }

  /**
   * Byte Compaction mode (see 5.4.3) permits all 256 possible 8-bit byte values to be encoded.
   * This includes all ASCII characters value 0 to 127 inclusive and provides for international
   * character set support.
   *
   * @param mode      The byte compaction mode i.e. 901 or 924
   * @param codewords The array of codewords (data + error)
   * @param codeIndex The current index into the codeword array.
   * @param result    The decoded data is appended to the result.
   * @return The next index into the codeword array.
   */
  private static int byteCompaction(int mode,
                                    int[] codewords,
                                    int codeIndex,
                                    ECIStringBuilder result) throws FormatException {
    boolean end = false;

    while (codeIndex < codewords[0] && !end) {
      //handle leading ECIs
      while (codeIndex < codewords[0] && codewords[codeIndex] == ECI_CHARSET) {
        result.appendECI(codewords[++codeIndex]);
        codeIndex++;
      }

      if (codeIndex >= codewords[0] || codewords[codeIndex] >= TEXT_COMPACTION_MODE_LATCH) {
        end = true;
      } else {
        //decode one block of 5 codewords to 6 bytes
        long value = 0;
        int count = 0;
        do {
          value = 900 * value + codewords[codeIndex++];
          count++;
        } while (count < 5 &&
                 codeIndex < codewords[0] &&
                 codewords[codeIndex] < TEXT_COMPACTION_MODE_LATCH);
        if (count == 5 && (mode == BYTE_COMPACTION_MODE_LATCH_6 ||
                           codeIndex < codewords[0] &&
                           codewords[codeIndex] < TEXT_COMPACTION_MODE_LATCH)) {
          for (int i = 0; i < 6; i++) {
            result.append((byte) (value >> (8 * (5 - i))));
          }
        } else {
          codeIndex -= count;
          while ((codeIndex < codewords[0]) && !end) {
            int code = codewords[codeIndex++];
            if (code < TEXT_COMPACTION_MODE_LATCH) {
              result.append((byte) code);
            } else if (code == ECI_CHARSET) {
              result.appendECI(codewords[codeIndex++]);
            } else {
              codeIndex--;
              end = true;
            }
          }
        }
      }
    }
    return codeIndex;
  }

  /**
   * Numeric Compaction mode (see 5.4.4) permits efficient encoding of numeric data strings.
   *
   * @param codewords The array of codewords (data + error)
   * @param codeIndex The current index into the codeword array.
   * @param result    The decoded data is appended to the result.
   * @return The next index into the codeword array.
   */
  private static int numericCompaction(int[] codewords, int codeIndex, ECIStringBuilder result) throws FormatException {
    int count = 0;
    boolean end = false;

    int[] numericCodewords = new int[MAX_NUMERIC_CODEWORDS];

    while (codeIndex < codewords[0] && !end) {
      int code = codewords[codeIndex++];
      if (codeIndex == codewords[0]) {
        end = true;
      }
      if (code < TEXT_COMPACTION_MODE_LATCH) {
        numericCodewords[count] = code;
        count++;
      } else {
        switch (code) {
          case TEXT_COMPACTION_MODE_LATCH:
          case BYTE_COMPACTION_MODE_LATCH:
          case BYTE_COMPACTION_MODE_LATCH_6:
          case BEGIN_MACRO_PDF417_CONTROL_BLOCK:
          case BEGIN_MACRO_PDF417_OPTIONAL_FIELD:
          case MACRO_PDF417_TERMINATOR:
          case ECI_CHARSET:
            codeIndex--;
            end = true;
            break;
        }
      }
      if ((count % MAX_NUMERIC_CODEWORDS == 0 || code == NUMERIC_COMPACTION_MODE_LATCH || end) && count > 0) {
        // Re-invoking Numeric Compaction mode (by using codeword 902
        // while in Numeric Compaction mode) serves  to terminate the
        // current Numeric Compaction mode grouping as described in 5.4.4.2,
        // and then to start a new one grouping.
        result.append(decodeBase900toBase10(numericCodewords, count));
        count = 0;
      }
    }
    return codeIndex;
  }

  /**
   * Convert a list of Numeric Compacted codewords from Base 900 to Base 10.
   *
   * @param codewords The array of codewords
   * @param count     The number of codewords
   * @return The decoded string representing the Numeric data.
   */
  /*
     EXAMPLE
     Encode the fifteen digit numeric string 000213298174000
     Prefix the numeric string with a 1 and set the initial value of
     t = 1 000 213 298 174 000
     Calculate codeword 0
     d0 = 1 000 213 298 174 000 mod 900 = 200

     t = 1 000 213 298 174 000 div 900 = 1 111 348 109 082
     Calculate codeword 1
     d1 = 1 111 348 109 082 mod 900 = 282

     t = 1 111 348 109 082 div 900 = 1 234 831 232
     Calculate codeword 2
     d2 = 1 234 831 232 mod 900 = 632

     t = 1 234 831 232 div 900 = 1 372 034
     Calculate codeword 3
     d3 = 1 372 034 mod 900 = 434

     t = 1 372 034 div 900 = 1 524
     Calculate codeword 4
     d4 = 1 524 mod 900 = 624

     t = 1 524 div 900 = 1
     Calculate codeword 5
     d5 = 1 mod 900 = 1
     t = 1 div 900 = 0
     Codeword sequence is: 1, 624, 434, 632, 282, 200

     Decode the above codewords involves
       1 x 900 power of 5 + 624 x 900 power of 4 + 434 x 900 power of 3 +
     632 x 900 power of 2 + 282 x 900 power of 1 + 200 x 900 power of 0 = 1000213298174000

     Remove leading 1 =>  Result is 000213298174000
   */
  private static String decodeBase900toBase10(int[] codewords, int count) throws FormatException {
    BigInteger result = BigInteger.ZERO;
    for (int i = 0; i < count; i++) {
      result = result.add(EXP900[count - i - 1].multiply(BigInteger.valueOf(codewords[i])));
    }
    String resultString = result.toString();
    if (resultString.charAt(0) != '1') {
      throw FormatException.getFormatInstance();
    }
    return resultString.substring(1);
  }

}
