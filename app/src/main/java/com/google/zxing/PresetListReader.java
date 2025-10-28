package com.google.zxing;

import com.google.zxing.aztec.AztecReader;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.maxicode.MaxiCodeReader;
import com.google.zxing.oned.CodaBarReader;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.Code39Reader;
import com.google.zxing.oned.Code93Reader;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.oned.EAN8Reader;
import com.google.zxing.oned.ITFReader;
import com.google.zxing.oned.UPCAReader;
import com.google.zxing.oned.UPCEReader;
import com.google.zxing.oned.rss.RSS14Reader;
import com.google.zxing.oned.rss.expanded.RSSExpandedReader;
import com.google.zxing.pdf417.PDF417Reader;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.ArrayList;
import java.util.Map;

public class PresetListReader  implements Reader {
    private final ArrayList<Reader> readers = new ArrayList<>();

    public void add(BarcodeFormat format){
        switch (format){
            case AZTEC:
                readers.add(new AztecReader());
                break;
            case CODABAR:
                readers.add(new CodaBarReader());
                break;
            case CODE_39:
                readers.add(new Code39Reader());
                break;
            case CODE_93:
                readers.add(new Code93Reader());
                break;
            case CODE_128:
                readers.add(new Code128Reader());
                break;
            case DATA_MATRIX:
                readers.add(new DataMatrixReader());
                break;
            case EAN_8:
                readers.add(new EAN8Reader());
                break;
            case EAN_13:
                readers.add(new EAN13Reader());
                break;
            case ITF:
                readers.add(new ITFReader());
                break;
            case MAXICODE:
                readers.add(new MaxiCodeReader());
                break;
            case PDF_417:
                readers.add(new PDF417Reader());
                break;
            case QR_CODE:
                readers.add(new QRCodeReader());
                break;
            case RSS_14:
                readers.add(new RSS14Reader());
                break;
            case RSS_EXPANDED:
                readers.add(new RSSExpandedReader());
                break;
            case UPC_A:
                readers.add(new UPCAReader());
                break;
            case UPC_E:
                readers.add(new UPCEReader());
                break;
        }
    }

    @Override
    public Result decode(BinaryBitmap image) throws NotFoundException, ChecksumException, FormatException {
        for (Reader reader : readers) {
            try {
                var result = reader.decode(image);
                if (result != null) return result;
            } catch (Exception e) {
                // Ignore
            }
        }
        return null;
    }

    @Override
    public Result decode(BinaryBitmap image, Map<DecodeHintType, ?> hints) throws NotFoundException, ChecksumException, FormatException {
        for (Reader reader : readers) {
            try {
                var result = reader.decode(image);
                if (result != null) return result;
            } catch (Exception e) {
                // Ignore
            }
        }
        return null;
    }

    @Override
    public void reset() {
        for (Reader reader : readers) {
            reader.reset();
        }
    }
}
