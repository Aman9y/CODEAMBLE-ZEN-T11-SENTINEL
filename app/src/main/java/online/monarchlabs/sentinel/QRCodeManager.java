package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.provider.Settings;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QRCodeManager {
    private static final String PREF_NAME = "ParentQR";
    private static final String KEY_QR_KEY = "permanent_qr_key";

    private SharedPreferences prefs;
    private Context context;

    public QRCodeManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getPermanentQRKey() {
        String existingKey = prefs.getString(KEY_QR_KEY, null);
        if (existingKey != null) {
            return existingKey;
        }

        // Generate new permanent key - FIXED: Remove incorrect BlockService.ParentUtils reference
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String permanentKey = "parent_" + deviceId + "_" + System.currentTimeMillis();

        prefs.edit().putString(KEY_QR_KEY, permanentKey).apply();
        return permanentKey;
    }

    public void clearPermanentQR() {
        prefs.edit().clear().apply();
    }

    // Static method for generating QR code bitmap
    public static Bitmap generateQRCodeBitmap(String data, int width, int height) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Overloaded method for square QR codes
    public static Bitmap generateQRCodeBitmap(String data, int size) {
        return generateQRCodeBitmap(data, size, size);
    }

    // Method with custom colors
    public static Bitmap generateQRCodeBitmap(String data, int width, int height,
                                              int foregroundColor, int backgroundColor) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? foregroundColor : backgroundColor);
                }
            }

            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}